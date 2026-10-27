package ro.zapakid.chat

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ro.zapakid.chat.net.ChatApiException
import ro.zapakid.chat.net.ChatSignal
import ro.zapakid.chat.net.ChatTokens
import ro.zapakid.chat.net.HttpChatApi
import ro.zapakid.chat.net.OutgoingEnvelope

/** The chat client against a mocked chat-staging.zapakid.ro (services/chat/openapi.yaml). */
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
    fun sendsOneEnvelopePerDeviceWithTheChatToken() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"messageId":"m1","serverTimestamp":7}"""))

        val at = api.send("c1", "m1", listOf(OutgoingEnvelope("ion", 2, 102, "prekey_message", "YmxvYg==")))

        assertEquals(7L, at)
        val request = server.takeRequest()
        assertEquals("/v1/conversations/c1/messages", request.url.encodedPath)
        assertEquals("Bearer chat-0", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("m1", body["messageId"]!!.jsonPrimitive.content)
        val envelope = body["envelopes"]!!.jsonArray[0].jsonObject
        assertEquals(102, envelope["recipientRegistrationId"]!!.jsonPrimitive.int)
        assertEquals("prekey_message", envelope["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aStaleDeviceListIsAClearError() = runTest {
        server.enqueue(MockResponse(code = 409, body = """{"error":{"code":"devices_mismatch","message":"x"},"missingDevices":[],"extraDevices":[]}"""))

        try {
            api.send("c1", "m1", emptyList())
            fail("expected an error")
        } catch (e: ChatApiException) {
            assertEquals(409, e.status)
            assertEquals("devices_mismatch", e.code)
        }
    }

    @Test
    fun refreshesTheTokenOnceAfter401() = runTest {
        server.enqueue(MockResponse(code = 401))
        server.enqueue(
            MockResponse(code = 200, body = """{"conversations":[{"id":"c1","kind":"direct","state":"open","createdAt":"x","members":[]}]}""")
        )

        assertEquals("c1", api.conversations().single().id)
        server.takeRequest()
        assertEquals("Bearer chat-1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun fetchesQueuedEnvelopesAndAcksThemByDeliveryId() = runTest {
        val envelope =
            """{"id":"e1","conversationId":"c1","messageId":"m1","senderAccountId":"ana","senderDeviceId":1,""" +
                """"type":"message","content":"eA==","serverTimestamp":5}"""
        server.enqueue(MockResponse(code = 200, body = """{"envelopes":[$envelope],"more":false}"""))
        server.enqueue(MockResponse(code = 204))

        val page = api.fetch("c1")
        api.ack("c1", page.envelopes.map { it.id })

        assertEquals("m1", page.envelopes.single().messageId)
        assertEquals("/v1/conversations/c1/messages", server.takeRequest().url.encodedPath)
        val ack = server.takeRequest()
        assertEquals("/v1/conversations/c1/acks", ack.url.encodedPath)
        assertEquals("e1", Json.parseToJsonElement(ack.body!!.utf8()).jsonObject["envelopeIds"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun attachmentsGoThroughOneTimeUrlsWithoutTheToken() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"attachmentId":"a_1","uploadUrl":"${server.url("/up?token=t")}","expiresAt":"x"}"""))
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 200, body = """{"attachmentId":"a_1","size":3,"downloadUrl":"${server.url("/down?token=u")}","expiresAt":"x"}"""))
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(byteArrayOf(9, 8, 7))).build())

        val upload = api.createAttachment("c1", 3)
        api.upload(upload.uploadUrl, byteArrayOf(1, 2, 3))
        val bytes = api.download("a_1")

        val reserve = server.takeRequest()
        assertEquals("/v1/attachments", reserve.url.encodedPath)
        assertEquals(3, Json.parseToJsonElement(reserve.body!!.utf8()).jsonObject["size"]!!.jsonPrimitive.int)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertNull("the one-time URL is the credential", put.headers["Authorization"])
        assertEquals(byteArrayOf(1, 2, 3).toByteString(), put.body)
        assertEquals("/v1/attachments/a_1", server.takeRequest().url.encodedPath)
        assertNull(server.takeRequest().headers["Authorization"])
        assertArrayEquals(byteArrayOf(9, 8, 7), bytes)
    }

    @Test
    fun streamFramesDecodeAndUnknownOnesAreSkipped() {
        val envelope =
            """{"id":"e1","conversationId":"c1","messageId":"m1","senderAccountId":"a","senderDeviceId":1,""" +
                """"type":"message","content":"eA==","serverTimestamp":5}"""
        val signal = api.decodeSignal("""{"type":"envelope","envelope":$envelope}""")
        assertEquals("m1", (signal as ChatSignal.Incoming).envelope.messageId)
        assertEquals(
            ChatSignal.Event("devices_changed", "c1"),
            api.decodeSignal("""{"type":"event","event":{"kind":"devices_changed","conversationId":"c1"}}""")
        )
        assertEquals(null, api.decodeSignal("""{"type":"pong"}"""))
        assertEquals(null, api.decodeSignal("junk"))
    }
}
