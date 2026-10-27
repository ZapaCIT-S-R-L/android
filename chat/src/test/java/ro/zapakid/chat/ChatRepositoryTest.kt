package ro.zapakid.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.chat.crypto.AttachmentCipher
import ro.zapakid.chat.crypto.MessageSealer
import ro.zapakid.chat.crypto.MessageSealerException
import ro.zapakid.chat.data.InMemoryChatStore
import ro.zapakid.chat.net.ChatApi
import ro.zapakid.chat.net.SealedFor
import ro.zapakid.chat.net.WireConversation
import ro.zapakid.chat.net.WireMessage
import java.util.Base64

/** Two phones talking through a fake chat service: only sealed blobs and ciphertext ever reach it. */
class ChatRepositoryTest {
    private val ana = ChatDevice("ana", "ana-1")
    private val ion = ChatDevice("ion", "ion-1")
    private val ionTablet = ChatDevice("ion", "ion-2")

    /** Bound to sender and recipient like a Signal session. Not encryption; tests only. */
    private class Sealer(private val self: ChatDevice) : MessageSealer {
        override suspend fun seal(plaintext: ByteArray, toDevice: ChatDevice) = "${toDevice.device}|${self.device}|".encodeToByteArray() + plaintext.reversedArray()

        override suspend fun open(blob: ByteArray, fromDevice: ChatDevice): ByteArray {
            val header = "${self.device}|${fromDevice.device}|".encodeToByteArray()
            if (!blob.copyOfRange(0, minOf(header.size, blob.size)).contentEquals(header)) throw MessageSealerException("not for me")
            return blob.copyOfRange(header.size, blob.size).reversedArray()
        }
    }

    private class Server : ChatApi {
        val sent = mutableListOf<Triple<String, String, List<SealedFor>>>()
        val blobs = mutableMapOf<String, ByteArray>()
        var failSend = false
        var pending = listOf<WireMessage>()
        val acked = mutableListOf<String>()

        override suspend fun conversations() = listOf(WireConversation("c1", null, false, listOf("ana", "ion")))

        override suspend fun createConversation(members: List<String>, title: String?) = WireConversation("c2", title, members.size > 2, members)

        override suspend fun devices(conversationId: String) =
            listOf(ChatDevice("ana", "ana-1"), ChatDevice("ion", "ion-1"), ChatDevice("ion", "ion-2"))

        override suspend fun send(conversationId: String, messageId: String, sealed: List<SealedFor>) {
            check(!failSend) { "offline" }
            sent += Triple(conversationId, messageId, sealed)
        }

        override suspend fun pending() = pending

        override suspend fun ack(messageIds: List<String>) {
            acked += messageIds
        }

        override suspend fun upload(conversationId: String, encrypted: ByteArray) = "att-${blobs.size}".also { blobs[it] = encrypted }

        override suspend fun download(attachmentId: String) = blobs.getValue(attachmentId)
    }

    private val server = Server()
    private val anaStore = InMemoryChatStore()
    private val anaPhone = ChatRepository(server, anaStore, Sealer(ana), ana, clock = { 1000 })

    private fun phone(device: ChatDevice) = ChatRepository(server, InMemoryChatStore(), Sealer(device), device)

    private fun relayedTo(device: ChatDevice): WireMessage {
        val (conversation, id, sealed) = server.sent.last()
        return WireMessage(id, conversation, ana, sealed.single { it.toDevice == device }.blob, 1000)
    }

    @Test
    fun aTextIsSealedForEveryOtherDeviceAndOpensOnEach() = runTest {
        anaPhone.refreshConversations()
        anaPhone.sendText("c1", "  Hi Ion!  ")

        val sealed = server.sent.single().third
        assertEquals(setOf(ion, ionTablet), sealed.map { it.toDevice }.toSet())
        assertFalse("the server never sees the text", sealed.any { Base64.getDecoder().decode(it.blob).decodeToString().contains("Hi Ion") })
        assertEquals(MessageStatus.Sent, anaStore.messages("c1").first().single().status)

        for (device in listOf(ion, ionTablet)) {
            val repo = phone(device)
            assertTrue(repo.receive(relayedTo(device)))
            val got = repo.messages("c1").first().single()
            assertEquals("Hi Ion!", got.text)
            assertFalse(got.mine)
        }
    }

    @Test
    fun aMessageForAnotherDeviceOrReplayedIsNotStoredTwice() = runTest {
        anaPhone.sendText("c1", "hello")
        val ionPhone = phone(ion)

        assertFalse(ionPhone.receive(relayedTo(ionTablet)))
        assertTrue(ionPhone.receive(relayedTo(ion)))
        assertFalse("duplicate", ionPhone.receive(relayedTo(ion)))
        assertEquals(1, ionPhone.messages("c1").first().size)
    }

    @Test
    fun theServerCannotRelabelAMessageId() = runTest {
        anaPhone.sendText("c1", "hello")

        assertFalse(phone(ion).receive(relayedTo(ion).copy(id = "other")))
    }

    @Test
    fun aFailedSendIsMarkedAndCanBeRetried() = runTest {
        server.failSend = true
        anaPhone.sendText("c1", "hello")
        val failed = anaStore.messages("c1").first().single()
        assertEquals(MessageStatus.Failed, failed.status)

        server.failSend = false
        anaPhone.retry(failed)

        assertEquals(MessageStatus.Sent, anaStore.messages("c1").first().single().status)
        assertEquals(failed.id, server.sent.single().second)
    }

    @Test
    fun photosAndVoiceNotesTravelAsCiphertextWithTheKeyInsideTheSeal() = runTest {
        val photo = ByteArray(2048) { it.toByte() }
        anaPhone.sendMedia("c1", MessageKind.Image, photo, "image/jpeg")

        val stored = server.blobs.values.single()
        assertFalse(stored.toList().windowed(16).contains(photo.copyOfRange(0, 16).toList()))
        val ionPhone = phone(ion)
        ionPhone.receive(relayedTo(ion))
        val message = ionPhone.messages("c1").first().single()
        assertEquals(MessageKind.Image, message.kind)
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
    fun offlineMessagesAreStoredThenAcknowledged() = runTest {
        anaPhone.sendText("c1", "while you were away")
        server.pending = listOf(relayedTo(ion))
        val ionPhone = phone(ion)

        ionPhone.sync()

        assertEquals("while you were away", ionPhone.messages("c1").first().single().text)
        assertEquals(listOf(server.sent.single().second), server.acked)
        assertEquals(1, ionPhone.conversations().first().single().unread)
    }

    @Test
    fun emptyTextIsNotSent() = runTest {
        anaPhone.sendText("c1", "   ")

        assertTrue(server.sent.isEmpty())
    }
}
