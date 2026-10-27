package ro.zapakid.chat

import kotlinx.serialization.Serializable

/** One phone in a conversation: account (token `sub`) and libsignal device id (token `dev`, 1-127). */
@Serializable
data class ChatDevice(val account: String, val device: Int) {
    val id: String get() = "$account.$device"
}

enum class MessageKind { Text, Image, Voice }

enum class MessageStatus { Sending, Sent, Failed, Received }

/** An encrypted attachment: its blob ID on the server and the key to open it (only inside the sealed message). */
@Serializable
data class AttachmentRef(
    val id: String,
    /** AES-256-GCM key, base64; travels only inside the end-to-end sealed message. */
    val key: String,
    val sha256: String,
    val mime: String,
    val size: Long,
    val durationMillis: Long? = null
) {
    override fun toString(): String = "AttachmentRef(id=$id, mime=$mime, size=$size)"
}

/** What goes inside the seal: never seen by the server. */
@Serializable
data class MessageBody(
    val id: String,
    val kind: MessageKind,
    val text: String? = null,
    val attachment: AttachmentRef? = null,
    val sentAt: Long
)

data class Message(
    val id: String,
    val conversationId: String,
    val sender: String,
    val mine: Boolean,
    val kind: MessageKind,
    val text: String?,
    val attachment: AttachmentRef?,
    val sentAt: Long,
    val status: MessageStatus,
    /** Decrypted attachment cached on the phone (inside the encrypted store directory), if loaded. */
    val localFile: String? = null
)

data class Conversation(
    val id: String,
    val title: String,
    val avatar: Int,
    val group: Boolean,
    val members: List<String>,
    val lastMessage: String? = null,
    val lastAt: Long = 0,
    val unread: Int = 0
)
