package ro.zapakid.chat.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ro.zapakid.chat.ChatDevice

/**
 *   GET  /v1/conversations                           -> {conversations: [...]}
 *   POST /v1/conversations {members, title?}         -> conversation
 *   GET  /v1/conversations/{id}/devices              -> {devices: [{account, device}]}
 *   POST /v1/conversations/{id}/messages {id, sealed: [{toDevice, blob}]}  -> 202
 *   GET  /v1/messages/pending                        -> {messages: [...]}
 *   POST /v1/messages/ack {ids}                      -> 204
 *   POST /v1/conversations/{id}/attachments (octet-stream) -> {id}
 *   GET  /v1/attachments/{id}                        -> bytes
 *   GET  /v1/events (WebSocket)                      -> {"type":"message", ...WireMessage}
 */
@Suppress("TooManyFunctions") // one function per endpoint
class HttpChatApi(private val baseUrl: HttpUrl, private val client: OkHttpClient, private val tokens: ChatTokens) :
    ChatApi,
    ChatSocket {
    init {
        val loopback = baseUrl.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        require(baseUrl.isHttps || loopback) { "chat service must use HTTPS" }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val octets = "application/octet-stream".toMediaType()

    @Serializable private data class Conversations(val conversations: List<WireConversation>)

    @Serializable private data class Create(val members: List<String>, val title: String?)

    @Serializable private data class Devices(val devices: List<ChatDevice>)

    @Serializable private data class Send(val id: String, val sealed: List<SealedFor>)

    @Serializable private data class Pending(val messages: List<WireMessage>)

    @Serializable private data class Ack(val ids: List<String>)

    @Serializable private data class Uploaded(val id: String)

    override suspend fun conversations() =
        decode(Conversations.serializer(), call("GET", "conversations")).conversations

    override suspend fun createConversation(members: List<String>, title: String?) = decode(
        WireConversation.serializer(),
        call("POST", "conversations", body = encode(Create.serializer(), Create(members, title)))
    )

    override suspend fun devices(conversationId: String) =
        decode(Devices.serializer(), call("GET", "conversations", conversationId, "devices")).devices

    override suspend fun send(conversationId: String, messageId: String, sealed: List<SealedFor>) {
        call(
            "POST",
            "conversations",
            conversationId,
            "messages",
            body = encode(Send.serializer(), Send(messageId, sealed))
        )
    }

    override suspend fun pending() = decode(Pending.serializer(), call("GET", "messages", "pending")).messages

    override suspend fun ack(messageIds: List<String>) {
        call("POST", "messages", "ack", body = encode(Ack.serializer(), Ack(messageIds)))
    }

    override suspend fun upload(conversationId: String, encrypted: ByteArray): String = decode(
        Uploaded.serializer(),
        call("POST", "conversations", conversationId, "attachments", body = encrypted.toRequestBody(octets))
    ).id

    override suspend fun download(attachmentId: String): ByteArray =
        callBytes("GET", listOf("attachments", attachmentId), null)

    override fun connect(): Flow<ChatSignal> = callbackFlow {
        val request = Request.Builder().url(
            url(listOf("events"))
        ).header("Authorization", "Bearer ${tokens.token()}").build()
        val socket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        trySend(ChatSignal.Connected)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        decodeSignal(text)?.let { trySend(it) }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(NORMAL, null)
                        trySend(ChatSignal.Disconnected(code))
                        close()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        trySend(ChatSignal.Disconnected(null))
                        close()
                    }
                }
            )
        awaitClose { socket.close(NORMAL, null) }
    }

    internal fun decodeSignal(text: String): ChatSignal? = runCatching {
        val element = json.parseToJsonElement(text)
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "message" -> ChatSignal.Incoming(json.decodeFromJsonElement(WireMessage.serializer(), element))
            else -> null
        }
    }.getOrNull()

    private fun <T> encode(serializer: KSerializer<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(jsonType)

    private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        json.decodeFromString(serializer, bytes.decodeToString())

    private fun url(segments: List<String>): HttpUrl = baseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private suspend fun call(method: String, vararg segments: String, body: RequestBody? = null): ByteArray =
        callBytes(method, segments.toList(), body)

    private suspend fun callBytes(method: String, segments: List<String>, body: RequestBody?): ByteArray {
        val first = execute(method, segments, body, tokens.token())
        val reply = if (first.first == UNAUTHORIZED) execute(method, segments, body, tokens.refresh()) else first
        if (reply.first !in SUCCESS) {
            val text = reply.second.decodeToString()
            val error = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
            throw ChatApiException(
                reply.first,
                error?.get("code")?.jsonPrimitive?.content ?: "http_${reply.first}",
                error?.get("message")?.jsonPrimitive?.content ?: "Server error ${reply.first}"
            )
        }
        return reply.second
    }

    private suspend fun execute(
        method: String,
        segments: List<String>,
        body: RequestBody?,
        token: String
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val sent = body ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null
        val request = Request.Builder().url(
            url(segments)
        ).method(method, sent).header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { it.code to it.body.bytes() }
    }

    private companion object {
        const val NORMAL = 1000
        const val UNAUTHORIZED = 401
        val SUCCESS = 200..299
    }
}
