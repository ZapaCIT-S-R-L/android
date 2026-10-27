package ro.zapakid.chat

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import ro.zapakid.chat.crypto.AttachmentCipher
import ro.zapakid.chat.crypto.CiphertextType
import ro.zapakid.chat.crypto.MessageSealer
import ro.zapakid.chat.crypto.MessageSealerException
import ro.zapakid.chat.data.ChatStore
import ro.zapakid.chat.net.ChatApi
import ro.zapakid.chat.net.ChatApiException
import ro.zapakid.chat.net.OutgoingEnvelope
import ro.zapakid.chat.net.WireConversation
import ro.zapakid.chat.net.WireEnvelope

/** Names for accounts (from the family and connections). */
fun interface ChatPeople {
    fun name(account: String): String
}

/**
 * Sending and receiving. Every message is sealed for each current device of every member except this one (this
 * account's other devices included), as services/chat requires; the service only relays those envelopes, and
 * attachments only as AES-GCM ciphertext. Received envelopes are opened, stored in the encrypted database and
 * then acknowledged by their delivery id, so the server drops them.
 */
@Suppress("TooManyFunctions")
class ChatRepository(
    private val api: ChatApi,
    private val store: ChatStore,
    private val sealer: MessageSealer,
    private val self: ChatDevice,
    private val people: ChatPeople = ChatPeople { it },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    /** Recipients per conversation: every device of every member, with its registration id. */
    private val recipients = mutableMapOf<String, List<Pair<ChatDevice, Int>>>()

    fun conversations(): Flow<List<Conversation>> = store.conversations()

    fun messages(conversationId: String): Flow<List<Message>> = store.messages(conversationId)

    /** Pulls the conversations from the server; titles are the other members' names. */
    suspend fun refreshConversations(): List<String> = api.conversations().map { remember(it) }

    private suspend fun remember(wire: WireConversation): String {
        recipients[wire.id] =
            wire.members.flatMap { m -> m.devices.map { ChatDevice(m.accountId, it.deviceId) to it.registrationId } }
        val others = wire.members.map { it.accountId }.filter { it != self.account }
        val title = others.joinToString(", ") { people.name(it) }
        val known = store.conversation(wire.id)
        store.upsertConversation(
            known?.copy(title = title, members = wire.members.map { it.accountId }, group = wire.kind == "group")
                ?: Conversation(
                    wire.id,
                    title,
                    Math.floorMod(wire.id.hashCode(), AVATARS),
                    wire.kind == "group",
                    wire.members.map {
                        it.accountId
                    }
                )
        )
        return wire.id
    }

    suspend fun startConversation(members: List<String>): Conversation {
        val id = remember(api.createConversation(members))
        return checkNotNull(store.conversation(id))
    }

    suspend fun sendText(conversationId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        send(conversationId, MessageKind.Text, trimmed, null, null)
    }

    /** Images and voice notes: encrypted on the phone, uploaded as ciphertext; the key travels in the seal. */
    suspend fun sendMedia(
        conversationId: String,
        kind: MessageKind,
        bytes: ByteArray,
        mime: String,
        durationMillis: Long? = null,
        localFile: String? = null
    ) {
        require(kind != MessageKind.Text)
        val sealed = AttachmentCipher.encrypt(bytes)
        val upload = api.createAttachment(conversationId, sealed.bytes.size.toLong())
        api.upload(upload.uploadUrl, sealed.bytes)
        val ref =
            AttachmentRef(
                upload.attachmentId,
                b64.encodeToString(sealed.key),
                b64.encodeToString(sealed.sha256),
                mime,
                bytes.size.toLong(),
                durationMillis
            )
        sealed.key.fill(0)
        send(conversationId, kind, null, ref, localFile)
    }

    private suspend fun send(
        conversationId: String,
        kind: MessageKind,
        text: String?,
        attachment: AttachmentRef?,
        localFile: String?
    ) {
        val body = MessageBody(newId(), kind, text, attachment, clock())
        store.add(
            Message(
                body.id,
                conversationId,
                self.account,
                true,
                kind,
                text,
                attachment,
                body.sentAt,
                MessageStatus.Sending,
                localFile
            )
        )
        deliver(conversationId, body)
    }

    /** Tries again after a failure (the same message ID: the service and the receivers drop duplicates). */
    suspend fun retry(message: Message) {
        if (!message.mine || message.status != MessageStatus.Failed) return
        store.setStatus(message.id, MessageStatus.Sending)
        deliver(
            message.conversationId,
            MessageBody(message.id, message.kind, message.text, message.attachment, message.sentAt)
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any failure marks the message Failed (retry)
    private suspend fun deliver(conversationId: String, body: MessageBody) {
        val plain = json.encodeToString(MessageBody.serializer(), body).encodeToByteArray()
        try {
            try {
                api.send(conversationId, body.id, seal(conversationId, plain))
            } catch (e: ChatApiException) {
                // 409/410: a device was added, removed or re-installed. Refresh the devices and seal again, once.
                if (e.status != CONFLICT && e.status != GONE) throw e
                remember(api.conversation(conversationId))
                api.send(conversationId, body.id, seal(conversationId, plain))
            }
            store.setStatus(body.id, MessageStatus.Sent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.setStatus(body.id, MessageStatus.Failed)
        } finally {
            plain.fill(0)
        }
    }

    private suspend fun seal(conversationId: String, plain: ByteArray): List<OutgoingEnvelope> {
        if (conversationId !in recipients) remember(api.conversation(conversationId))
        return recipients.getValue(conversationId).filter { it.first != self }.map { (device, registrationId) ->
            val sealed = sealer.seal(plain, device)
            OutgoingEnvelope(
                device.account,
                device.device,
                registrationId,
                sealed.type.wire,
                b64.encodeToString(sealed.content)
            )
        }
    }

    /** Opens and stores one envelope; false when it can't be opened or was already stored (ack it anyway). */
    @Suppress("ReturnCount", "SwallowedException") // unopenable envelopes are dropped, not errors
    suspend fun receive(envelope: WireEnvelope): Boolean {
        val from = ChatDevice(envelope.senderAccountId, envelope.senderDeviceId)
        val type = CiphertextType.of(envelope.type) ?: return false
        val plain =
            try {
                sealer.open(type, unb64.decode(envelope.content), from)
            } catch (e: MessageSealerException) {
                return false
            } catch (e: IllegalArgumentException) {
                return false
            }
        val body = runCatching { json.decodeFromString(MessageBody.serializer(), plain.decodeToString()) }.getOrNull()
        plain.fill(0)
        // The message id is inside the seal: the server can't relabel or replay one message as another.
        if (body == null || body.id != envelope.messageId) return false
        if (store.conversation(envelope.conversationId) == null) remember(api.conversation(envelope.conversationId))
        return store.add(
            Message(
                body.id,
                envelope.conversationId,
                from.account,
                from.account == self.account,
                body.kind,
                body.text,
                body.attachment,
                body.sentAt,
                MessageStatus.Received
            )
        )
    }

    /** Envelopes queued while this phone was away: open, store, then acknowledge them page by page. */
    suspend fun sync(conversationId: String) {
        do {
            val page = api.fetch(conversationId)
            page.envelopes.forEach { receive(it) }
            if (page.envelopes.isNotEmpty()) api.ack(conversationId, page.envelopes.map { it.id })
        } while (page.more && page.envelopes.isNotEmpty())
    }

    /** Downloads, checks and decrypts an attachment. */
    suspend fun openAttachment(ref: AttachmentRef): ByteArray =
        AttachmentCipher.decrypt(api.download(ref.id), unb64.decode(ref.key), unb64.decode(ref.sha256))

    suspend fun markRead(conversationId: String) = store.markRead(conversationId)

    /** Forgets cached devices (a devices_changed or members_changed event). */
    suspend fun refresh(conversationId: String) {
        remember(api.conversation(conversationId))
    }

    private companion object {
        const val AVATARS = 8
        const val CONFLICT = 409
        const val GONE = 410
    }
}
