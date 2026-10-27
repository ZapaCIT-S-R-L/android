package ro.zapakid.calls.api

import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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

/** The per-call event socket: roster, track names, key envelopes, joined/left/ended and the ring timeout. */
class CallEventsClientTest {
    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<String>()
    private val upgrades = LinkedBlockingQueue<okhttp3.Headers>()
    private lateinit var serverSocket: WebSocket
    private val opened = java.util.concurrent.CountDownLatch(1)

    private val self = CallDevice("acc-a", "1")

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

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    }
                ).build()
        )
    }

    @After
    fun tearDown() = server.close()

    private fun client() = CallEventsClient(server.url("/v1/"), OkHttpClient(), FakeChatTokens(), self)

    @Test
    fun parsesRosterKeysAndCallEvents() = runBlocking {
        val connection = client().connect("call-1")
        val events = firstSignals(connection, 6)
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val blob = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        serverSocket.send(
            """{"type":"roster","call":{"callId":"call-1","conversationId":"c","state":"active","createdAt":"x",
                "ringDeadline":"y","epoch":3,"keyLeader":{"accountId":"acc-a","deviceId":1},"declined":["acc-c"],
                "endReason":null,"participants":[
                {"accountId":"acc-a","deviceId":1,"role":"parent","joinedAt":"x","sessionId":"sess-a",
                 "tracks":["audio-a"],"connected":true},
                {"accountId":"acc-b","deviceId":2,"role":"kid","joinedAt":"x","sessionId":null,"tracks":[],
                 "connected":false}]},"unknownField":1}"""
        )
        serverSocket.send("""{"type":"key","epoch":3,"fromAccount":"acc-b","fromDevice":2,"blob":"$blob"}""")
        serverSocket.send("""{"type":"joined","accountId":"acc-c","deviceId":5,"epoch":4}""")
        serverSocket.send("""{"type":"left","accountId":"acc-c","deviceId":5,"epoch":5,"reason":"disconnected"}""")
        serverSocket.send("""{"type":"declined","accountId":"acc-d"}""")
        serverSocket.send("""{"type":"keys_ack","epoch":3,"delivered":1,"queued":0}""")
        serverSocket.send("""{"type":"ended","reason":"max_duration"}""")

        val list = events.await()
        val roster = list[0] as CallSignal.Roster
        assertEquals(listOf(self, CallDevice("acc-b", "2")), roster.participants.map { it.device })
        assertEquals(3, roster.epoch)
        assertEquals(listOf("audio-a"), roster.participants[0].tracks)
        assertEquals("sess-a", roster.participants[0].sessionId)
        val key = (list[1] as CallSignal.Key).envelope
        assertEquals(3, key.epoch)
        assertEquals(CallDevice("acc-b", "2"), key.fromDevice)
        assertEquals(self, key.toDevice)
        assertArrayEquals(byteArrayOf(1, 2, 3), key.blob)
        assertEquals(CallSignal.Joined(CallDevice("acc-c", "5")), list[2])
        assertEquals(CallSignal.Left(CallDevice("acc-c", "5")), list[3])
        assertEquals(CallSignal.Declined("acc-d"), list[4])
        assertEquals("keys_ack is skipped", CallSignal.Ended("max_duration"), list[5])
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
    fun sendsTheEpochsKeysInOneMessageAndPings() = runBlocking {
        val connection = client().connect("call-1")
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        connection.sendKeys(
            3,
            listOf(
                KeyEnvelope(3, self, CallDevice("acc-b", "2"), byteArrayOf(9, 8)),
                KeyEnvelope(3, self, CallDevice("acc-c", "7"), byteArrayOf(7))
            )
        )
        connection.ping()

        val sent = Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("keys", sent["type"]!!.jsonPrimitive.content)
        assertEquals(3, sent["epoch"]!!.jsonPrimitive.int)
        val first = sent["envelopes"]!!.jsonArray[0].jsonObject
        assertEquals("acc-b", first["toAccount"]!!.jsonPrimitive.content)
        assertEquals(2, first["toDevice"]!!.jsonPrimitive.int)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(9, 8)), first["blob"]!!.jsonPrimitive.content)
        assertEquals(2, sent["envelopes"]!!.jsonArray.size)
        assertEquals("""{"type":"ping"}""", received.poll(5, TimeUnit.SECONDS))
        connection.close()
    }

    @Test
    fun unknownOrBrokenMessagesAreSkippedNotFatal() = runBlocking {
        val connection = client().connect("call-1")
        val next = firstSignals(connection, 1)
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        serverSocket.send("""{"type":"video_hint"}""")
        serverSocket.send("not json")
        serverSocket.send("""{"type":"left","accountId":"acc-b","deviceId":2,"epoch":2,"reason":"left"}""")

        assertEquals(CallSignal.Left(CallDevice("acc-b", "2")), next.await().single())
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

    private fun CoroutineScope.firstSignals(connection: CallEventsConnection, n: Int): Deferred<List<CallSignal>> =
        async(Dispatchers.IO) { withTimeout(5_000) { connection.signals.take(n).toList() } }
}
