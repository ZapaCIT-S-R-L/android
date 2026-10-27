package ro.zapakid.calls.api

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.KeyEnvelope
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The per-call event socket: roster, track names, key envelopes, joined/left/ended and the ring timeout. */
class CallEventsClientTest {
    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<String>()
    private val upgrades = LinkedBlockingQueue<okhttp3.Headers>()
    private lateinit var serverSocket: WebSocket
    private val opened = java.util.concurrent.CountDownLatch(1)

    private val self = CallDevice("acc-a", "dev-a")

    @Before
    fun setUp() {
        server.start()
        server.enqueue(
            MockResponse
                .Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            serverSocket = webSocket
                            upgrades.put(response.request.headers)
                            opened.countDown()
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            received.put(text)
                        }
                    }
                ).build()
        )
    }

    @After
    fun tearDown() = server.close()

    private fun client() = CallEventsClient(baseUrl = server.url("/v1/"), client = OkHttpClient(), tokens = FakeChatTokens())

    @Test
    fun parsesRosterKeysAndCallEvents() = runBlocking {
        val connection = client().connect("call-1")
        val events = async(connection, 5)
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val blob = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        serverSocket.send(
            """{"type":"roster","participants":[
                {"account":"acc-a","device":"dev-a","tracks":["mic-dev-a"],"sessionId":"sess-a"},
                {"account":"acc-b","device":"dev-b","tracks":[],"sessionId":null}],"unknownField":1}"""
        )
        serverSocket.send(
            """{"type":"key","epoch":2,"from":{"account":"acc-b","device":"dev-b"},
                "to":{"account":"acc-a","device":"dev-a"},"blob":"$blob"}"""
        )
        serverSocket.send("""{"type":"joined","account":"acc-c","device":"dev-c"}""")
        serverSocket.send("""{"type":"ring_timeout"}""")
        serverSocket.send("""{"type":"ended","reason":"max_duration"}""")

        val list = events.await()
        val roster = list[0] as CallSignal.Roster
        assertEquals(listOf(self, CallDevice("acc-b", "dev-b")), roster.participants.map { it.device })
        assertEquals(listOf("mic-dev-a"), roster.participants[0].tracks)
        assertEquals("sess-a", roster.participants[0].sessionId)
        val key = (list[1] as CallSignal.Key).envelope
        assertEquals(2, key.epoch)
        assertEquals(CallDevice("acc-b", "dev-b"), key.fromDevice)
        assertArrayEquals(byteArrayOf(1, 2, 3), key.blob)
        assertEquals(CallSignal.Joined(CallDevice("acc-c", "dev-c")), list[2])
        assertEquals(CallSignal.RingTimeout, list[3])
        assertEquals(CallSignal.Ended("max_duration"), list[4])
        connection.close()
    }

    @Test
    fun theSocketAuthenticatesWithTheChatTokenInAHeaderNotTheUrl() = runBlocking {
        val connection = client().connect("call-1")
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val request = server.takeRequest()

        assertEquals("/v1/calls/call-1/events", request.url.encodedPath)
        assertEquals(null, request.url.query)
        assertEquals("Bearer chat-token-1", request.headers["Authorization"])
        connection.close()
    }

    @Test
    fun sendsKeyEnvelopesAsBase64() = runBlocking {
        val connection = client().connect("call-1")
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        connection.sendKey(KeyEnvelope(3, self, CallDevice("acc-b", "dev-b"), byteArrayOf(9, 8)))

        val sent = Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("key", sent["type"]!!.jsonPrimitive.content)
        assertEquals(3, sent["epoch"]!!.jsonPrimitive.int)
        assertEquals("dev-b", sent["toDevice"]!!.jsonObject["device"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(9, 8)), sent["blob"]!!.jsonPrimitive.content)
        connection.close()
    }

    @Test
    fun unknownOrBrokenMessagesAreSkippedNotFatal() = runBlocking {
        val connection = client().connect("call-1")
        val next = async(connection, 1)
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        serverSocket.send("""{"type":"video_hint"}""")
        serverSocket.send("not json")
        serverSocket.send("""{"type":"left","account":"acc-b","device":"dev-b"}""")

        assertEquals(CallSignal.Left(CallDevice("acc-b", "dev-b")), next.await().single())
        connection.close()
    }

    @Test
    fun aDroppedSocketIsReportedSoTheCallCanReconnect() = runBlocking {
        val connection = client().connect("call-1")
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        serverSocket.close(1001, "going away")

        val last = withTimeout(5_000) { connection.signals.first { it is CallSignal.Disconnected } }
        assertEquals(CallSignal.Disconnected(1001), last)
    }

    private fun async(connection: CallEventsConnection, n: Int) =
        kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) {
            withTimeout(5_000) { connection.signals.take(n).toList() }
        }
}
