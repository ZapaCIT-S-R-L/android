package ro.zapakid.app.shell

import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ro.zapakid.calls.api.ChatTokenProvider

/**
 * Trades the account session for a short-lived chat token (`POST /v1/chat/token`, ES256, ≤ 15 min) for the chat
 * and calls services, and caches it until a minute before it expires. The services verify it; the phone only
 * reads `sub` (account) and `dev` (device) from it to know who it is in a call or chat.
 *
 * The cache belongs to one session: it answers only for the session token it was fetched with, so an account
 * switch or a logout can never be served the previous account's token — which carries that account's `sub` and
 * `dev`. Only one token is held at a time, and a returning account fetches again.
 */
class ChatTokenClient(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    private val session: () -> String?,
    private val now: () -> Instant = Instant::now
) : ChatTokenProvider,
    ro.zapakid.chat.net.ChatTokens {
    @Serializable
    private data class Token(val token: String, val expiresAt: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cached: Token? = null
    private var cachedSession: String? = null

    class Identity(val account: String, val device: String)

    // Both entry points read the session, which costs an encrypted-file read and a Keystore decrypt, so neither
    // runs on the caller's thread — a cache hit must not put that work on the UI thread either.
    override suspend fun token(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val bearer = bearer()
            (cachedFor(bearer) ?: fetch(bearer)).token
        }
    }

    override suspend fun refresh(): String = withContext(Dispatchers.IO) { lock.withLock { fetch(bearer()) } }.token

    /** Who this phone is, from the token's claims (`sub`, `dev`). */
    suspend fun identity(): Identity {
        val payload = token().split('.').getOrNull(1) ?: error("not a JWT")
        val claims = json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()).jsonObject
        return Identity(claims.getValue("sub").jsonPrimitive.content, claims.getValue("dev").jsonPrimitive.content)
    }

    private fun bearer(): String = checkNotNull(session()) { "signed out" }

    /** The cache of this session only, and only while it is more than [MARGIN_SECONDS] from expiry. */
    private fun cachedFor(bearer: String): Token? = cached?.takeIf {
        cachedSession == bearer && now().plusSeconds(MARGIN_SECONDS).isBefore(Instant.parse(it.expiresAt))
    }

    /** Already on [Dispatchers.IO]: both callers wrap the lock in it. */
    private suspend fun fetch(bearer: String): Token {
        val request =
            Request
                .Builder()
                .url(baseUrl.newBuilder().addPathSegments("chat/token").build())
                .header("Authorization", "Bearer $bearer")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "chat token: HTTP ${response.code}" }
            json.decodeFromString(Token.serializer(), response.body.string()).also {
                cached = it
                cachedSession = bearer
            }
        }
    }

    private companion object {
        const val MARGIN_SECONDS = 60L
    }
}
