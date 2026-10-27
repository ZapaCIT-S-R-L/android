package ro.zapakid.chat

import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.chat.crypto.AttachmentCipher
import ro.zapakid.chat.crypto.CiphertextType
import ro.zapakid.chat.crypto.MessageSealer
import ro.zapakid.chat.crypto.MessageSealerException
import ro.zapakid.chat.crypto.Sealed
import ro.zapakid.chat.data.InMemoryChatStore
import ro.zapakid.chat.net.ChatApi
import ro.zapakid.chat.net.ChatApiException
import ro.zapakid.chat.net.EnvelopePage
import ro.zapakid.chat.net.OutgoingEnvelope
import ro.zapakid.chat.net.Upload
import ro.zapakid.chat.net.WireConversation
import ro.zapakid.chat.net.WireEnvelope
import ro.zapakid.chat.net.WireMember
import ro.zapakid.chat.net.WireMemberDevice

/** Phones talking through a fake chat service (services/chat/openapi.yaml): only sealed blobs reach it. */
class ChatRepositoryTest {
    private val ana = ChatDevice("ana", 1)
    private val ion = ChatDevice("ion", 1)
    private val ionTablet = ChatDevice("ion", 2)

    /** Bound to sender and recipient like a Signal session. Not encryption; tests only. */
    private class Sealer(private val self: ChatDevice) : MessageSealer {
        override suspend fun seal(plaintext: ByteArray, toDevice: ChatDevice) =
            Sealed(CiphertextType.Message, "${toDevice.id}|${self.id}|".encodeToByteArray() + plaintext.reversedArray())

        override suspend fun open(type: CiphertextType, content: ByteArray, fromDevice: ChatDevice): ByteArray {
            val header = "${self.id}|${fromDevice.id}|".encodeToByteArray()
            if (!content.copyOfRange(0, minOf(header.size, content.size)).contentEquals(header)) {
                throw MessageSealerException("not for me")
            }
            return content.copyOfRange(header.size, content.size).reversedArray()
        }
    }

    private class Server : ChatApi {
        val sent = mutableListOf<Triple<String, String, List<OutgoingEnvelope>>>()
        val blobs = mutableMapOf<String, ByteArray>()
        var failSend: Exception? = null
        var ionDevices = listOf(WireMemberDevice(1, 101), WireMemberDevice(2, 102))
        var queued = listOf<WireEnvelope>()
        val acked = mutableListOf<String>()

        private fun c1() = WireConversation(
            "c1",
            "direct",
            "open",
            listOf(
                WireMember("ana", devices = listOf(WireMemberDevice(1, 11))),
                WireMember("ion", devices = ionDevices)
            )
        )

        override suspend fun conversations() = listOf(c1())

        override suspend fun conversation(id: String) = c1()

        override suspend fun createConversation(members: List<String>) = c1()

        override suspend fun send(conversationId: String, messageId: String, envelopes: List<OutgoingEnvelope>): Long {
            failSend?.let {
                failSend = null
                throw it
            }
            sent += Triple(conversationId, messageId, envelopes)
            return 5
        }

        override suspend fun fetch(conversationId: String, limit: Int) = EnvelopePage(queued, false)

        override suspend fun ack(conversationId: String, envelopeIds: List<String>) {
            acked += envelopeIds
        }

        override suspend fun createAttachment(conversationId: String, size: Long) =
            Upload("a_${blobs.size}", "https://up/${blobs.size}")

        override suspend fun upload(uploadUrl: String, encrypted: ByteArray) {
            blobs["a_" + uploadUrl.substringAfterLast('/')] = encrypted
        }

        override suspend fun download(attachmentId: String) = blobs.getValue(attachmentId)
    }

    private val server = Server()
    private val anaStore = InMemoryChatStore()
    private val anaPhone = ChatRepository(server, anaStore, Sealer(ana), ana, clock = { 1000 })

    private fun phone(device: ChatDevice) = ChatRepository(server, InMemoryChatStore(), Sealer(device), device)

    private fun relayedTo(device: ChatDevice, n: Int = 0): WireEnvelope {
        val (conversation, id, envelopes) = server.sent.last()
        val copy = envelopes.single { it.recipientAccountId == device.account && it.recipientDeviceId == device.device }
        return WireEnvelope("e-$n-${device.id}", conversation, id, ana.account, ana.device, copy.type, copy.content, 5)
    }

    @Test
    fun aTextIsSealedForEveryOtherDeviceWithItsRegistrationId() = runTest {
        anaPhone.refreshConversations()
        anaPhone.sendText("c1", "  Hi Ion!  ")

        val envelopes = server.sent.single().third
        assertEquals(listOf(1 to 101, 2 to 102), envelopes.map { it.recipientDeviceId to it.recipientRegistrationId })
        assertTrue(envelopes.all { it.type == "message" })
        assertFalse(envelopes.any { Base64.getDecoder().decode(it.content).decodeToString().contains("Hi Ion") })
        assertEquals(MessageStatus.Sent, anaStore.messages("c1").first().single().status)

        for (device in listOf(ion, ionTablet)) {
            val repo = phone(device)
            assertTrue(repo.receive(relayedTo(device)))
            assertEquals("Hi Ion!", repo.messages("c1").first().single().text)
        }
    }

    @Test
    fun aStaleDeviceListIsRefreshedAndTheSendRetriedOnce() = runTest {
        anaPhone.refreshConversations()
        server.ionDevices = listOf(WireMemberDevice(1, 101), WireMemberDevice(3, 103))
        server.failSend = ChatApiException(409, "devices_mismatch", "missing ion.3")

        anaPhone.sendText("c1", "hello")

        assertEquals(listOf(1, 3), server.sent.single().third.map { it.recipientDeviceId })
        assertEquals(MessageStatus.Sent, anaStore.messages("c1").first().single().status)
    }

    @Test
    fun aMessageForAnotherDeviceDuplicateOrRelabelledIsNotStored() = runTest {
        anaPhone.sendText("c1", "hello")
        val ionPhone = phone(ion)

        assertFalse(ionPhone.receive(relayedTo(ionTablet)))
        assertFalse("relabelled", ionPhone.receive(relayedTo(ion).copy(messageId = "other")))
        assertTrue(ionPhone.receive(relayedTo(ion)))
        assertFalse("duplicate", ionPhone.receive(relayedTo(ion, 1)))
        assertEquals(1, ionPhone.messages("c1").first().size)
    }

    @Test
    fun aFailedSendIsMarkedAndCanBeRetried() = runTest {
        server.failSend = ChatApiException(503, "unavailable", "x")
        anaPhone.sendText("c1", "hello")
        val failed = anaStore.messages("c1").first().single()
        assertEquals(MessageStatus.Failed, failed.status)

        anaPhone.retry(failed)

        assertEquals(MessageStatus.Sent, anaStore.messages("c1").first().single().status)
        assertEquals(failed.id, server.sent.single().second)
    }

    @Test
    fun photosTravelAsCiphertextThroughTheOneTimeUrl() = runTest {
        val photo = ByteArray(2048) { it.toByte() }
        anaPhone.sendMedia("c1", MessageKind.Image, photo, "image/jpeg")

        val stored = server.blobs.values.single()
        assertFalse(stored.toList().windowed(16).contains(photo.copyOfRange(0, 16).toList()))
        val ionPhone = phone(ion)
        ionPhone.receive(relayedTo(ion))
        val message = ionPhone.messages("c1").first().single()
        assertEquals("a_0", message.attachment?.id)
        assertArrayEquals(photo, ionPhone.openAttachment(message.attachment!!))
    }

    @Test
    fun aSwappedAttachmentIsRejected() = runTest {
        anaPhone.sendMedia("c1", MessageKind.Voice, ByteArray(100) { 1 }, "audio/mp4", durationMillis = 3000)
        val ionPhone = phone(ion)
        ionPhone.receive(relayedTo(ion))
        val ref = ionPhone.messages("c1").first().single().attachment!!
        server.blobs[ref.id] = AttachmentCipher.encrypt(ByteArray(100) { 2 }).bytes

        assertNull(runCatching { ionPhone.openAttachment(ref) }.getOrNull())
    }

    @Test
    fun queuedMessagesAreStoredThenAcknowledgedByDeliveryId() = runTest {
        anaPhone.sendText("c1", "while you were away")
        server.queued = listOf(relayedTo(ion))
        val ionPhone = phone(ion)

        ionPhone.sync("c1")

        assertEquals("while you were away", ionPhone.messages("c1").first().single().text)
        assertEquals(listOf("e-0-ion.1"), server.acked)
        assertEquals(1, ionPhone.conversations().first().single().unread)
    }

    @Test
    fun emptyTextIsNotSent() = runTest {
        anaPhone.sendText("c1", "   ")

        assertTrue(server.sent.isEmpty())
    }
}
