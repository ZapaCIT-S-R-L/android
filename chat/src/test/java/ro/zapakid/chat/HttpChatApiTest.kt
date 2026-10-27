package ro.zapakid.chat

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ro.zapakid.chat.net.ChatSignal
import ro.zapakid.chat.net.ChatTokens
import ro.zapakid.chat.net.HttpChatApi
import ro.zapakid.chat.net.SealedFor

/** The chat client against a mocked chat-staging.zapakid.ro. */
class HttpChatApiTest {
    private val server = MockWebServer()
    private var refreshes = 0
    private lateinit var api: HttpChatApi

    @Before
    fun setUp() {
        server.start()
        api =
            HttpChatApi(
                server.url("/v1/"),
                OkHttpClient(),
                object : ChatTokens {
                    override suspend fun token() = "chat-$refreshes"

                    override suspend fun refresh() = "chat-${++refreshes}"
                }
            )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun sendsSealedCopiesWithTheChatToken() = runTest {
        server.enqueue(MockResponse(code = 202))

        api.send("c1", "m1", listOf(SealedFor(ChatDevice("ion", "ion-1"), "YmxvYg==")))

        val request = server.takeRequest()
        assertEquals("/v1/conversations/c1/messages", request.url.encodedPath)
        assertEquals("Bearer chat-0", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("m1", body["id"]!!.jsonPrimitive.content)
        assertEquals("ion-1", body["sealed"]!!.jsonArray[0].jsonObject["toDevice"]!!.jsonObject["device"]!!.jsonPrimitive.content)
    }

    @Test
    fun refreshesTheTokenOnceAfter401() = runTest {
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse(code = 200, body = """{"conversations":[{"id":"c1","members":["a","b"]}]}"""))

        assertEquals("c1", api.conversations().single().id)
        server.takeRequest()
        assertEquals("Bearer chat-1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun attachmentsAreUploadedAsOctetsAndDownloadedAsBytes() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"id":"att-1"}"""))
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(byteArrayOf(9, 8, 7))).build())

        assertEquals("att-1", api.upload("c1", byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(9, 8, 7), api.download("att-1"))
        val upload = server.takeRequest()
        assertEquals("application/octet-stream", upload.headers["Content-Type"])
        assertEquals(byteArrayOf(1, 2, 3).toByteString(), upload.body)
    }

    @Test
    fun socketMessagesDecodeAndUnknownOnesAreSkipped() {
        val signal = api.decodeSignal("""{"type":"message","id":"m1","conversationId":"c1","from":{"account":"a","device":"d"},"blob":"eA==","sentAt":5}""")
        assertEquals("m1", (signal as ChatSignal.Incoming).message.id)
        assertEquals(null, api.decodeSignal("""{"type":"typing"}"""))
        assertEquals(null, api.decodeSignal("junk"))
    }
}
