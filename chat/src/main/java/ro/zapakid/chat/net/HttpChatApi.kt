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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * services/chat/openapi.yaml:
 *   GET  /v1/conversations, GET /v1/conversations/{id}, POST /v1/conversations {members}
 *   POST /v1/conversations/{id}/messages {messageId, envelopes}   GET .../messages?limit   POST .../acks {envelopeIds}
 *   GET  /v1/conversations/{id}/stream (WebSocket: envelope / event frames, ping)
 *   POST /v1/attachments {conversationId, size} -> one-time uploadUrl; GET /v1/attachments/{id} -> one-time downloadUrl
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

    @Serializable private data class Create(val members: List<String>)

    @Serializable private data class Send(val messageId: String, val envelopes: List<OutgoingEnvelope>)

    @Serializable private data class Sent(val messageId: String, val serverTimestamp: Long)

    @Serializable private data class Page(val envelopes: List<WireEnvelope>, val more: Boolean = false)

    @Serializable private data class Acks(val envelopeIds: List<String>)

    @Serializable private data class Reserve(val conversationId: String, val size: Long)

    @Serializable private data class Reserved(val attachmentId: String, val uploadUrl: String)

    @Serializable private data class Download(val attachmentId: String, val downloadUrl: String)

    @Serializable
    private data class Frame(val type: String, val envelope: WireEnvelope? = null, val event: EventBody? = null)

    @Serializable private data class EventBody(val kind: String, val conversationId: String)

    override suspend fun conversations() =
        decode(Conversations.serializer(), call("GET", "conversations")).conversations

    override suspend fun conversation(id: String) =
        decode(WireConversation.serializer(), call("GET", "conversations", id))

    override suspend fun createConversation(members: List<String>) = decode(
        WireConversation.serializer(),
        call("POST", "conversations", body = encode(Create.serializer(), Create(members)))
    )

    override suspend fun send(conversationId: String, messageId: String, envelopes: List<OutgoingEnvelope>): Long {
        val body = encode(Send.serializer(), Send(messageId, envelopes))
        return decode(
            Sent.serializer(),
            call("POST", "conversations", conversationId, "messages", body = body)
        ).serverTimestamp
    }

    override suspend fun fetch(conversationId: String, limit: Int): EnvelopePage {
        val page = decode(
            Page.serializer(),
            call(
                "GET",
                "conversations",
                conversationId,
                "messages",
                query =
                    "limit" to "$limit"
            )
        )
        return EnvelopePage(page.envelopes, page.more)
    }

    override suspend fun ack(conversationId: String, envelopeIds: List<String>) {
        call("POST", "conversations", conversationId, "acks", body = encode(Acks.serializer(), Acks(envelopeIds)))
    }

    override suspend fun createAttachment(conversationId: String, size: Long): Upload {
        val reserved =
            decode(
                Reserved.serializer(),
                call("POST", "attachments", body = encode(Reserve.serializer(), Reserve(conversationId, size)))
            )
        return Upload(reserved.attachmentId, reserved.uploadUrl)
    }

    /** One-time URLs carry their own token: no chat token is sent with them. */
    override suspend fun upload(uploadUrl: String, encrypted: ByteArray) {
        oneTime(Request.Builder().url(secure(uploadUrl)).put(encrypted.toRequestBody(octets)).build())
    }

    override suspend fun download(attachmentId: String): ByteArray {
        val url = decode(Download.serializer(), call("GET", "attachments", attachmentId)).downloadUrl
        return oneTime(Request.Builder().url(secure(url)).get().build())
    }

    private fun secure(url: String): HttpUrl = url.toHttpUrl().also {
        require(it.isHttps || it.host == baseUrl.host) { "attachment URLs must use HTTPS" }
    }

    private suspend fun oneTime(request: Request): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ChatApiException(
                    response.code,
                    "http_${response.code}",
                    "attachment transfer failed"
                )
            }
            response.body.bytes()
        }
    }

    override fun connect(conversationId: String): Flow<ChatSignal> = callbackFlow {
        val request =
            Request
                .Builder()
                .url(url(listOf("conversations", conversationId, "stream"), null))
                .header("Authorization", "Bearer ${tokens.token()}")
                .build()
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
        val frame = json.decodeFromString(Frame.serializer(), text)
        when (frame.type) {
            "envelope" -> frame.envelope?.let { ChatSignal.Incoming(it) }
            "event" -> frame.event?.let { ChatSignal.Event(it.kind, it.conversationId) }
            else -> null
        }
    }.getOrNull()

    private fun <T> encode(serializer: KSerializer<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(jsonType)

    private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        json.decodeFromString(serializer, bytes.decodeToString())

    private fun url(segments: List<String>, query: Pair<String, String>?): HttpUrl = baseUrl
        .newBuilder()
        .apply {
            segments.forEach(::addPathSegment)
            query?.let { addQueryParameter(it.first, it.second) }
        }.build()

    private suspend fun call(
        method: String,
        vararg segments: String,
        body: RequestBody? = null,
        query: Pair<String, String>? = null
    ): ByteArray {
        val first = execute(method, url(segments.toList(), query), body, tokens.token())
        val reply = if (first.first ==
            UNAUTHORIZED
        ) {
            execute(method, url(segments.toList(), query), body, tokens.refresh())
        } else {
            first
        }
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

    private suspend fun execute(method: String, url: HttpUrl, body: RequestBody?, token: String): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            val sent = body ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null
            val request = Request.Builder().url(
                url
            ).method(method, sent).header("Authorization", "Bearer $token").build()
            client.newCall(request).execute().use { it.code to it.body.bytes() }
        }

    private companion object {
        const val NORMAL = 1000
        const val UNAUTHORIZED = 401
        val SUCCESS = 200..299
    }
}
