package ro.zapakid.chat.net

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// chat.zapakid.ro (staging: chat-staging.zapakid.ro), field for field with zapakid-api services/chat/openapi.yaml.
// Auth: the account service's chat token.

@Serializable
data class WireMemberDevice(val deviceId: Int, val registrationId: Int)

@Serializable
data class WireMember(
    val accountId: String,
    val role: String = "parent",
    val guardian: String? = null,
    val devices: List<WireMemberDevice> = emptyList()
)

@Serializable
data class WireConversation(
    val id: String,
    val kind: String = "direct",
    val state: String = "open",
    val members: List<WireMember> = emptyList()
)

@Serializable
data class OutgoingEnvelope(
    val recipientAccountId: String,
    val recipientDeviceId: Int,
    val recipientRegistrationId: Int,
    val type: String,
    val content: String
)

/** One delivery copy for this device; acknowledged by [id]. */
@Serializable
data class WireEnvelope(
    val id: String,
    val conversationId: String,
    val messageId: String,
    val senderAccountId: String,
    val senderDeviceId: Int,
    val type: String,
    val content: String,
    val serverTimestamp: Long
)

data class EnvelopePage(val envelopes: List<WireEnvelope>, val more: Boolean)

data class Upload(val attachmentId: String, val uploadUrl: String)

interface ChatApi {
    suspend fun conversations(): List<WireConversation>

    suspend fun conversation(id: String): WireConversation

    /** `members` are the other accounts: one makes a direct chat, more a group. */
    suspend fun createConversation(members: List<String>): WireConversation

    /** One envelope per current device of every member but this one; `409`/`410` when the device list is stale. */
    suspend fun send(conversationId: String, messageId: String, envelopes: List<OutgoingEnvelope>): Long

    suspend fun fetch(conversationId: String, limit: Int = 100): EnvelopePage

    suspend fun ack(conversationId: String, envelopeIds: List<String>)

    /** Reserves an upload; the one-time URL takes the ciphertext with no other credentials. */
    suspend fun createAttachment(conversationId: String, size: Long): Upload

    suspend fun upload(uploadUrl: String, encrypted: ByteArray)

    suspend fun download(attachmentId: String): ByteArray
}

sealed interface ChatSignal {
    data class Incoming(val envelope: WireEnvelope) : ChatSignal

    /** `members_changed`, `devices_changed` or `conversation_closed`. */
    data class Event(val kind: String, val conversationId: String) : ChatSignal

    data object Connected : ChatSignal

    data class Disconnected(val code: Int?) : ChatSignal
}

/** The live socket per conversation (`GET /v1/conversations/{id}/stream`). */
interface ChatSocket {
    fun connect(conversationId: String): Flow<ChatSignal>
}

class ChatApiException(val status: Int, val code: String, message: String) : Exception(message)

interface ChatTokens {
    suspend fun token(): String

    suspend fun refresh(): String
}
