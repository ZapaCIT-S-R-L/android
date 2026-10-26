package com.zapacit.cuzaai.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The auth client against a mocked API that follows the contract (openapi.yaml in ZapaCIT-S-R-L/api). */
class HttpAuthApiTest {
    private val server = MockWebServer()
    private lateinit var api: HttpAuthApi

    private val signedIn =
        """{"token":"tok-123","parent":{"id":"p_1","name":"Ana Pop","email":"ana@example.com"},"extra":"ignored"}"""

    @Before
    fun setUp() {
        server.start()
        api = HttpAuthApi(baseUrl = server.url("/v1/"), client = OkHttpClient())
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun googleSignInPostsTheTokenAndAgeRangeAndReturnsTheSession() = runTest {
        server.enqueue(MockResponse(code = 200, body = signedIn))

        val session =
            api.signIn(
                provider = Provider.GOOGLE,
                idToken = "google-id-token",
                nonce = "n-1",
                name = null,
                ageRange = AgeRange(source = AgeRange.SOURCE_GOOGLE_PLAY, lower = 18, upper = null)
            )

        assertEquals(Session("tok-123", Parent("p_1", "Ana Pop", "ana@example.com")), session)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/google", request.url.encodedPath)
        assertNull("no session yet, so no Authorization header", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("google-id-token", body["id_token"]!!.jsonPrimitive.content)
        assertEquals("n-1", body["nonce"]!!.jsonPrimitive.content)
        val age = body["age_range"]!!.jsonObject
        assertEquals("google_play", age["source"]!!.jsonPrimitive.content)
        assertEquals(18, age["lower"]!!.jsonPrimitive.int)
        assertFalse("unknown values are left out, not sent as null", age.containsKey("upper"))
        assertFalse(body.containsKey("name"))
    }

    @Test
    fun appleSignInPostsToTheAppleEndpointWithTheName() = runTest {
        server.enqueue(MockResponse(code = 200, body = signedIn))

        api.signIn(Provider.APPLE, "apple-id-token", "n-2", "Ana Pop", AgeRange.unknown())

        val request = server.takeRequest()
        assertEquals("/v1/auth/apple", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("apple-id-token", body["id_token"]!!.jsonPrimitive.content)
        assertEquals("Ana Pop", body["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun aMinorIsRejectedWithTheServerErrorCode() = runTest {
        server.enqueue(
            MockResponse(code = 403, body = """{"error":"minor","message":"Parents must be 18 or older."}""")
        )

        try {
            api.signIn(Provider.GOOGLE, "t", null, null, AgeRange.unknown())
            fail("expected AuthApiException")
        } catch (e: AuthApiException) {
            assertEquals(403, e.status)
            assertEquals("minor", e.code)
            assertEquals(true, e.isMinor)
        }
    }

    @Test
    fun anErrorWithoutAJsonBodyStillHasACode() = runTest {
        server.enqueue(MockResponse(code = 502, body = "<html>Bad gateway</html>"))

        try {
            api.me("tok")
            fail("expected AuthApiException")
        } catch (e: AuthApiException) {
            assertEquals(502, e.status)
            assertEquals("http_502", e.code)
        }
    }

    @Test
    fun meSendsTheSessionAsABearerToken() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"parent":{"id":"p_1","name":"Ana Pop","email":null}}"""))

        val parent = api.me("tok-123")

        assertEquals(Parent("p_1", "Ana Pop", null), parent)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me", request.url.encodedPath)
        assertEquals("Bearer tok-123", request.headers["Authorization"])
    }

    @Test
    fun logoutPostsWithTheBearerToken() = runTest {
        server.enqueue(MockResponse(code = 204, body = ""))

        api.logout("tok-123")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/logout", request.url.encodedPath)
        assertEquals("Bearer tok-123", request.headers["Authorization"])
    }

    @Test
    fun anExpiredSessionIsReportedAsUnauthorized() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"unauthorized","message":"Sign in again."}"""))

        try {
            api.me("old")
            fail("expected AuthApiException")
        } catch (e: AuthApiException) {
            assertEquals(true, e.isUnauthorized)
        }
    }
}
