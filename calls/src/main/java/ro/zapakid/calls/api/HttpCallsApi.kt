package ro.zapakid.calls.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** [CallsApi] over HTTPS with OkHttp. Never logs bodies: they carry SDP and ICE credentials. */
class HttpCallsApi(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    private val tokens: ChatTokenProvider
) : CallsApi {
    init {
        requireSecure(baseUrl)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun create(conversationId: String): CallTicket = ticket(
        send("POST", listOf("calls"), encode(CreateCallRequest.serializer(), CreateCallRequest(conversationId)))
    )

    override suspend fun join(callId: String): CallTicket = ticket(send("POST", listOf("calls", callId, "join"), EMPTY))

    private fun ticket(text: String): CallTicket =
        decode(CallGrant.serializer(), text).let { CallTicket(it.call.callId, it.iceServers, it.call.epoch) }

    override suspend fun leave(callId: String) {
        send("POST", listOf("calls", callId, "leave"), EMPTY)
    }

    override suspend fun decline(callId: String) {
        send("POST", listOf("calls", callId, "decline"), EMPTY)
    }

    override suspend fun newSession(callId: String): String =
        decode(SessionResponse.serializer(), send("POST", listOf("calls", callId, "sessions"), EMPTY)).sessionId

    override suspend fun newTracks(callId: String, sessionId: String, request: TracksRequest): TracksResponse = decode(
        TracksResponse.serializer(),
        send(
            "POST",
            listOf("calls", callId, "sessions", sessionId, "tracks", "new"),
            encode(TracksRequest.serializer(), request)
        )
    )

    override suspend fun renegotiate(callId: String, sessionId: String, answer: SessionDescription) {
        val body = encode(RenegotiateRequest.serializer(), RenegotiateRequest(answer))
        send("PUT", listOf("calls", callId, "sessions", sessionId, "renegotiate"), body)
    }

    override suspend fun closeTracks(
        callId: String,
        sessionId: String,
        mids: List<String>,
        offer: SessionDescription
    ): TracksResponse {
        val body = encode(CloseTracksRequest.serializer(), CloseTracksRequest(offer, mids.map(::Mid)))
        return decode(
            TracksResponse.serializer(),
            send("PUT", listOf("calls", callId, "sessions", sessionId, "tracks", "close"), body)
        )
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(jsonType)

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    /** Path segments are encoded one by one, so an ID can never change the path. One retry after a 401. */
    private suspend fun send(method: String, segments: List<String>, body: RequestBody): String {
        val url = baseUrl.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
        val first = execute(method, url, body, tokens.token())
        if (first.status != UNAUTHORIZED) return first.orThrow()
        return execute(method, url, body, tokens.refresh()).orThrow()
    }

    private class Reply(val status: Int, val text: String, val retryAfter: String?, val json: Json) {
        fun orThrow(): String {
            if (status in SUCCESS) return text
            val error = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()?.error
            throw CallsApiException(
                status = status,
                code = error?.code ?: "http_$status",
                message = error?.message ?: "Server error $status",
                retryAfterSeconds = retryAfter?.trim()?.toLongOrNull()
            )
        }
    }

    private suspend fun execute(method: String, url: HttpUrl, body: RequestBody, token: String): Reply =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .method(method, body)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
            client.newCall(request).execute().use { response: Response ->
                Reply(response.code, response.body.string(), response.header("Retry-After"), json)
            }
        }

    private companion object {
        const val UNAUTHORIZED = 401
        val SUCCESS = 200..299
        val EMPTY = ByteArray(0).toRequestBody(null)
    }
}

/** HTTPS only; plain HTTP is allowed for loopback (tests and `wrangler dev`). */
internal fun requireSecure(url: HttpUrl) {
    val loopback = url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1" || url.host == "10.0.2.2"
    require(url.isHttps || loopback) { "calls service must use HTTPS" }
}
