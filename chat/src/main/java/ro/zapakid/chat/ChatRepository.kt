package ro.zapakid.chat

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import ro.zapakid.chat.crypto.AttachmentCipher
import ro.zapakid.chat.crypto.MessageSealer
import ro.zapakid.chat.crypto.MessageSealerException
import ro.zapakid.chat.data.ChatStore
import ro.zapakid.chat.net.ChatApi
import ro.zapakid.chat.net.SealedFor
import ro.zapakid.chat.net.WireMessage

/** Names and robots for accounts (from the family and connections). */
fun interface ChatPeople {
    fun name(account: String): String
}

/**
 * Sending and receiving. Every message is sealed per recipient device (and this account's other devices); the
 * chat service stores only those blobs, and attachments only as AES-GCM ciphertext. Received messages are opened,
 * stored in the encrypted database and then acknowledged, so the server can drop them.
 */
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

    fun conversations(): Flow<List<Conversation>> = store.conversations()

    fun messages(conversationId: String): Flow<List<Message>> = store.messages(conversationId)

    /** Pulls the conversation list from the server (titles fall back to the other members' names). */
    suspend fun refreshConversations() {
        for (wire in api.conversations()) {
            val known = store.conversation(wire.id)
            val others = wire.members.filter { it != self.account }
            val title = wire.title ?: others.joinToString(", ") { people.name(it) }
            store.upsertConversation(
                known?.copy(title = title, members = wire.members, group = wire.group)
                    ?: Conversation(
                        wire.id,
                        title,
                        Math.floorMod(wire.id.hashCode(), AVATARS),
                        wire.group,
                        wire.members
                    )
            )
        }
    }

    suspend fun startConversation(members: List<String>, title: String?): Conversation {
        val wire = api.createConversation(members, title)
        val conversation =
            Conversation(
                wire.id,
                title ?: members.joinToString(", ") {
                    people.name(it)
                },
                Math.floorMod(wire.id.hashCode(), AVATARS),
                members.size > 1,
                wire.members
            )
        store.upsertConversation(conversation)
        return conversation
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
        val id = api.upload(conversationId, sealed.bytes)
        val ref =
            AttachmentRef(
                id,
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

    /** Tries again after a failure (the same message ID: the receivers drop duplicates). */
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
            val devices = api.devices(conversationId).filter { it != self }
            val sealed = devices.map { SealedFor(it, b64.encodeToString(sealer.seal(plain, it))) }
            api.send(conversationId, body.id, sealed)
            store.setStatus(body.id, MessageStatus.Sent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.setStatus(body.id, MessageStatus.Failed)
        } finally {
            plain.fill(0)
        }
    }

    /** Opens and stores one relayed message; returns false when it can't be opened (it is still acknowledged). */
    @Suppress("ReturnCount", "SwallowedException") // unopenable messages are dropped, not errors
    suspend fun receive(wire: WireMessage): Boolean {
        val plain =
            try {
                sealer.open(unb64.decode(wire.blob), wire.from)
            } catch (e: MessageSealerException) {
                return false
            } catch (e: IllegalArgumentException) {
                return false
            }
        val body = runCatching { json.decodeFromString(MessageBody.serializer(), plain.decodeToString()) }.getOrNull()
        plain.fill(0)
        if (body == null || body.id != wire.id) return false
        if (store.conversation(wire.conversationId) == null) refreshConversations()
        return store.add(
            Message(
                body.id,
                wire.conversationId,
                wire.from.account,
                wire.from.account == self.account,
                body.kind,
                body.text,
                body.attachment,
                body.sentAt,
                MessageStatus.Received
            )
        )
    }

    /** Messages that came while offline: open, store, then acknowledge them all. */
    suspend fun sync() {
        val pending = api.pending()
        pending.forEach { receive(it) }
        if (pending.isNotEmpty()) api.ack(pending.map { it.id })
    }

    /** Downloads, checks and decrypts an attachment. */
    suspend fun openAttachment(ref: AttachmentRef): ByteArray =
        AttachmentCipher.decrypt(api.download(ref.id), unb64.decode(ref.key), unb64.decode(ref.sha256))

    suspend fun markRead(conversationId: String) = store.markRead(conversationId)

    private companion object {
        const val AVATARS = 8
    }
}
