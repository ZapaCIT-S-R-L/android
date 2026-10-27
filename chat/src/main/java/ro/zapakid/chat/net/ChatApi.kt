package ro.zapakid.chat.net

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import ro.zapakid.chat.ChatDevice

// chat.zapakid.ro (staging: chat-staging.zapakid.ro), coded against the planned services/chat API; kept small so
// it is easy to align with services/chat/openapi.yaml when it lands. Auth: the account service's chat token.

@Serializable
data class WireConversation(
    val id: String,
    val title: String? = null,
    val group: Boolean = false,
    val members: List<String> = emptyList()
)

@Serializable
data class SealedFor(val toDevice: ChatDevice, val blob: String)

/** A message as the server relays it: who sent it, when, and the blob sealed for this device. */
@Serializable
data class WireMessage(
    val id: String,
    val conversationId: String,
    val from: ChatDevice,
    val blob: String,
    val sentAt: Long
)

interface ChatApi {
    suspend fun conversations(): List<WireConversation>

    suspend fun createConversation(members: List<String>, title: String?): WireConversation

    /** Every device of every member (the sender fans out one sealed copy per device, its own other devices too). */
    suspend fun devices(conversationId: String): List<ChatDevice>

    suspend fun send(conversationId: String, messageId: String, sealed: List<SealedFor>)

    /** Messages queued while this phone was offline; [ack] removes them from the server. */
    suspend fun pending(): List<WireMessage>

    suspend fun ack(messageIds: List<String>)

    /** Uploads an encrypted attachment; returns its ID. */
    suspend fun upload(conversationId: String, encrypted: ByteArray): String

    suspend fun download(attachmentId: String): ByteArray
}

sealed interface ChatSignal {
    data class Incoming(val message: WireMessage) : ChatSignal

    data object Connected : ChatSignal

    data class Disconnected(val code: Int?) : ChatSignal
}

/** The live socket (`GET /v1/events`): new messages as they arrive. */
interface ChatSocket {
    fun connect(): Flow<ChatSignal>
}

class ChatApiException(val status: Int, val code: String, message: String) : Exception(message)

interface ChatTokens {
    suspend fun token(): String

    suspend fun refresh(): String
}
