package ro.zapakid.calls.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The calls client against a mocked calls.zapakid.ro that follows the brief's API (services/calls/openapi.yaml). */
class HttpCallsApiTest {
    private val server = MockWebServer()
    private val tokens = FakeChatTokens()
    private lateinit var api: HttpCallsApi

    @Before
    fun setUp() {
        server.start()
        api = HttpCallsApi(baseUrl = server.url("/v1/"), client = OkHttpClient(), tokens = tokens)
    }

    @After
    fun tearDown() = server.close()

    private fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun expectError(block: suspend () -> Unit): CallsApiException {
        try {
            block()
        } catch (e: CallsApiException) {
            return e
        }
        fail("expected CallsApiException")
        error("unreachable")
    }

    private val iceJson =
        """[{"urls":["stun:stun.cloudflare.com:3478","turn:turn.cloudflare.com:3478?transport=udp"],
           "username":"u1","credential":"c1"}]"""

    private val limits = """{"maxParticipants":8,"ringTimeoutSeconds":45,"maxDurationSeconds":7200}"""

    private val callJson =
        """{"callId":"call-1","conversationId":"conv-9","state":"ringing","createdAt":"x","ringDeadline":"y","epoch":1,
           "keyLeader":{"accountId":"acc-a","deviceId":1},"participants":[],"declined":[],"endReason":null}"""

    @Test
    fun startingACallSendsTheConversationWithTheChatTokenAndReturnsIceServers() = runTest {
        server.enqueue(
            MockResponse(
                code = 201,
                body = """{"call":$callJson,"iceServers":$iceJson,"limits":$limits}"""
            )
        )

        val call = api.create("conv-9")

        assertEquals("call-1", call.callId)
        assertEquals(1, call.epoch)
        assertEquals(
            listOf("stun:stun.cloudflare.com:3478", "turn:turn.cloudflare.com:3478?transport=udp"),
            call.iceServers.single().urls
        )
        assertEquals("u1", call.iceServers.single().username)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/calls", request.url.encodedPath)
        assertEquals("Bearer chat-token-1", request.headers["Authorization"])
        assertEquals("conv-9", body(request.body!!.utf8())["conversationId"]!!.jsonPrimitive.content)
    }

    @Test
    fun joinLeaveAndDeclineHitTheirPaths() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"call":$callJson,"iceServers":$iceJson}"""))
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204))

        assertEquals("call-1", api.join("call-1").callId)
        api.leave("call-1")
        api.decline("call-1")

        assertEquals("/v1/calls/call-1/join", server.takeRequest().url.encodedPath)
        assertEquals("/v1/calls/call-1/leave", server.takeRequest().url.encodedPath)
        assertEquals("/v1/calls/call-1/decline", server.takeRequest().url.encodedPath)
    }

    @Test
    fun callIdsArePathEncoded() = runTest {
        server.enqueue(MockResponse(code = 204))

        api.leave("../admin")

        assertEquals("/v1/calls/..%2Fadmin/leave", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aConversationTheTokenDoesNotAllowIsAClearError() = runTest {
        server.enqueue(
            MockResponse(
                code = 403,
                body = """{"error":{"code":"conversation_not_allowed","message":"Not a member"}}"""
            )
        )

        val e = expectError { api.create("conv-x") }

        assertEquals(403, e.status)
        assertEquals("conversation_not_allowed", e.code)
    }

    @Test
    fun anExpiredChatTokenIsRefreshedOnceAndTheRequestRetried() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"token_expired","message":"x"}}"""))
        server.enqueue(MockResponse(code = 204))

        api.leave("call-1")

        assertEquals("Bearer chat-token-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer chat-token-2", server.takeRequest().headers["Authorization"])
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun aSecond401IsReturnedNotLooped() = runTest {
        repeat(2) {
            server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"bad_token","message":"x"}}"""))
        }

        assertEquals(401, expectError { api.leave("call-1") }.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimitsCarryRetryAfter() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .code(429)
                .body("""{"error":{"code":"rate_limited","message":"slow down"}}""")
                .addHeader("Retry-After", "30")
                .build()
        )

        val e = expectError { api.create("conv-9") }

        assertEquals("rate_limited", e.code)
        assertEquals(30L, e.retryAfterSeconds)
    }

    @Test
    fun theRealtimeProxyCreatesASessionAndPublishesTheMicrophoneTrack() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"sessionId":"sess-1"}"""))
        server.enqueue(
            MockResponse(
                code = 200,
                body =
                    """{"sessionDescription":{"type":"answer","sdp":"v=0 answer"},
                       "tracks":[{"mid":"0","trackName":"mic-dev-a"}],"requiresImmediateRenegotiation":false}"""
            )
        )

        val session = api.newSession("call-1")
        val answer =
            api.newTracks(
                "call-1",
                session,
                TracksRequest(
                    sessionDescription = SessionDescription("offer", "v=0 offer"),
                    tracks = listOf(TrackRef(location = "local", mid = "0", trackName = "mic-dev-a"))
                )
            )

        assertEquals("sess-1", session)
        assertEquals("v=0 answer", answer.sessionDescription!!.sdp)
        assertEquals("mic-dev-a", answer.tracks.single().trackName)
        assertEquals("/v1/calls/call-1/sessions", server.takeRequest().url.encodedPath)
        val tracks = server.takeRequest()
        assertEquals("/v1/calls/call-1/sessions/sess-1/tracks/new", tracks.url.encodedPath)
        val sent = body(tracks.body!!.utf8())
        assertEquals("offer", sent["sessionDescription"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("local", sent["tracks"]!!.jsonArray[0].jsonObject["location"]!!.jsonPrimitive.content)
    }

    @Test
    fun subscribingToARemoteTrackNamesItsSessionAndRenegotiates() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body =
                    """{"sessionDescription":{"type":"offer","sdp":"v=0 sfu offer"},
                       "tracks":[{"mid":"1","trackName":"mic-dev-b","sessionId":"sess-b"}],
                       "requiresImmediateRenegotiation":true}"""
            )
        )
        server.enqueue(MockResponse(code = 200, body = """{}"""))
        server.enqueue(MockResponse(code = 200, body = """{"requiresImmediateRenegotiation":false}"""))

        val offer =
            api.newTracks(
                "call-1",
                "sess-1",
                TracksRequest(
                    tracks = listOf(TrackRef(location = "remote", trackName = "mic-dev-b", sessionId = "sess-b"))
                )
            )
        api.renegotiate("call-1", "sess-1", SessionDescription("answer", "v=0 my answer"))
        api.closeTracks("call-1", "sess-1", listOf("1"), SessionDescription("offer", "v=0 close"))

        assertTrue(offer.requiresImmediateRenegotiation)
        val subscribe = body(server.takeRequest().body!!.utf8())
        assertEquals(null, subscribe["sessionDescription"])
        assertEquals("sess-b", subscribe["tracks"]!!.jsonArray[0].jsonObject["sessionId"]!!.jsonPrimitive.content)
        val renegotiate = server.takeRequest()
        assertEquals("PUT", renegotiate.method)
        assertEquals("/v1/calls/call-1/sessions/sess-1/renegotiate", renegotiate.url.encodedPath)
        val close = server.takeRequest()
        assertEquals("PUT", close.method)
        assertEquals("/v1/calls/call-1/sessions/sess-1/tracks/close", close.url.encodedPath)
        val closeBody = body(close.body!!.utf8())
        assertEquals("1", closeBody["tracks"]!!.jsonArray[0].jsonObject["mid"]!!.jsonPrimitive.content)
        assertEquals("force is required by the calls API", "false", closeBody["force"]!!.jsonPrimitive.content)
    }

    @Test
    fun theBaseUrlMustBeHttpsOutsideLoopback() {
        try {
            HttpCallsApi(
                okhttp3.HttpUrl.Builder().scheme("http").host("calls.zapakid.ro").addPathSegment("v1").build(),
                OkHttpClient(),
                tokens
            )
            fail("plain http accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
