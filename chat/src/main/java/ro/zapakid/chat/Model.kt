package ro.zapakid.chat

import kotlinx.serialization.Serializable

/** One phone in a conversation: account (token `sub`) and libsignal device id (token `dev`, 1-127). */
@Serializable
data class ChatDevice(val account: String, val device: Int) {
    val id: String get() = "$account.$device"
}

/** [Call]: "I'm calling you" (shows as a call in the chat). [Unsupported]: sealed content this app can't read yet. */
enum class MessageKind { Text, Image, Voice, Call, Unsupported }

enum class MessageStatus { Sending, Sent, Failed, Received }

/**
 * An encrypted attachment's pointer, travelling only inside the sealed message (the server never sees the key).
 * Field names and units are the wire format shared with iOS (`AttachmentPointer`): see [ChatContent].
 */
@Serializable
data class AttachmentRef(
    val id: String,
    /** AES-256-GCM key, base64. */
    val key: String,
    /** SHA-256 of the ciphertext, base64. */
    val digest: String,
    /** Ciphertext size in bytes. */
    val size: Long,
    val mimeType: String,
    /** Voice messages: seconds. */
    val duration: Double? = null
) {
    override fun toString(): String = "AttachmentRef(id=$id, mimeType=$mimeType, size=$size)"
}

/** What goes inside the seal: never seen by the server. On the wire as [ChatContent] version 1. */
data class MessageBody(
    val id: String,
    val kind: MessageKind,
    val text: String? = null,
    val attachment: AttachmentRef? = null,
    val callId: String? = null,
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
    val localFile: String? = null,
    val callId: String? = null
) {
    val body: MessageBody get() = MessageBody(id, kind, text, attachment, callId, sentAt)
}

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
