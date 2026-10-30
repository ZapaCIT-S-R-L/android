package ro.zapakid.app.shell

import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Chat tokens from the account service, cached until a minute before expiry. */
class ChatTokenClientTest {
    private val server = MockWebServer()
    private var now = Instant.parse("2026-10-27T10:00:00Z")
    private lateinit var tokens: ChatTokenClient

    private fun jwt(n: Int): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val claims = """{"sub":"acc-1","dev":"dev-$n","aud":["chat","calls"]}"""
        return "${b64.encodeToString("{}".toByteArray())}.${b64.encodeToString(claims.toByteArray())}.sig"
    }

    private fun enqueue(n: Int, expires: String) =
        server.enqueue(MockResponse(code = 200, body = """{"token":"${jwt(n)}","expiresAt":"$expires"}"""))

    @Before
    fun setUp() {
        server.start()
        tokens = ChatTokenClient(server.url("/v1/"), OkHttpClient(), session = { "zks_session" }, now = { now })
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun aTokenIsFetchedWithTheSessionAndReused() = runTest {
        enqueue(1, "2026-10-27T10:10:00Z")

        assertEquals(jwt(1), tokens.token())
        assertEquals(jwt(1), tokens.token())

        val request = server.takeRequest()
        assertEquals("/v1/chat/token", request.url.encodedPath)
        assertEquals("Bearer zks_session", request.headers["Authorization"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aTokenCloseToExpiryOrAfter401IsRenewed() = runTest {
        enqueue(1, "2026-10-27T10:10:00Z")
        enqueue(2, "2026-10-27T10:20:00Z")
        enqueue(3, "2026-10-27T10:30:00Z")
        tokens.token()

        now = Instant.parse("2026-10-27T10:09:30Z")
        assertEquals(jwt(2), tokens.token())
        assertEquals(jwt(3), tokens.refresh())
    }

    @Test
    fun theIdentityComesFromTheClaims() = runTest {
        enqueue(7, "2026-10-27T10:10:00Z")

        val me = tokens.identity()

        assertEquals("acc-1", me.account)
        assertEquals("dev-7", me.device)
    }

    @Test
    fun aRevokedSessionIsReported() = runTest {
        var revoked = 0
        val client =
            ChatTokenClient(server.url("/v1/"), OkHttpClient(), session = {
                "zkk_session"
            }, onUnauthorized = { revoked++ })
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"unauthorized","message":"x"}}"""))

        runCatching { client.token() }

        assertEquals(1, revoked)
    }
}
