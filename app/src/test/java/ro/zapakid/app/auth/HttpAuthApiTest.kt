package ro.zapakid.app.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ro.zapakid.app.testing.APPLE_CODE
import ro.zapakid.app.testing.APPLE_STATE
import ro.zapakid.app.testing.ContractRecorder
import ro.zapakid.app.testing.NONCE

/** The auth client against a mocked API that follows openapi.yaml in ZapaCIT-S-R-L/api (PR #5). */
class HttpAuthApiTest {
    private val server = MockWebServer()
    private lateinit var api: HttpAuthApi

    private val parentJson =
        """{"id":"7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11","email":"ana@example.com","displayName":"Ana Pop",
           "ageRange":{"category":"adult","source":"google_play_age_signals","lowerBound":18,"upperBound":null,
           "platformStatus":"SHARED","checkedAt":"2026-10-26T10:00:00Z"},"createdAt":"2026-10-26T10:00:00Z"}"""
    private val signedIn =
        """{"session":{"token":"zks_tok0000000000000000000000000000000000000000","expiresAt":"2026-11-26T10:00:00Z"},"parent":$parentJson,"newAccount":true}"""

    @Before
    fun setUp() {
        server.start()
        api = HttpAuthApi(baseUrl = server.url("/v1/"), client = ContractRecorder.client())
    }

    @After
    fun tearDown() = server.close()

    private fun body(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private suspend fun expectError(block: suspend () -> Unit): AuthApiException {
        try {
            block()
        } catch (e: AuthApiException) {
            return e
        }
        fail("expected AuthApiException")
        error("unreachable")
    }

    @Test
    fun nonceIsFetchedWithoutASession() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"nonce":"$NONCE","expiresAt":"2026-10-26T10:10:00Z"}"""))

        assertEquals(NONCE, api.nonce())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/nonce", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun googleSignInSendsIdTokenNonceAndAgeRangeAndReturnsTheSession() = runTest {
        server.enqueue(MockResponse(code = 200, body = signedIn))

        val session =
            api.signInWithGoogle(
                idToken = "google-id-token",
                nonce = NONCE,
                ageRange = AgeRange.shared(lower = 18, upper = null, platformStatus = "SHARED")
            )

        assertEquals("zks_tok0000000000000000000000000000000000000000", session.token)
        assertEquals("2026-11-26T10:00:00Z", session.expiresAt)
        assertEquals(Parent("7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11", "ana@example.com", "Ana Pop"), session.parent)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/google", request.url.encodedPath)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertNull("no session yet, so no Authorization header", request.headers["Authorization"])
        val sent = body(request.body!!.utf8())
        assertEquals(setOf("idToken", "nonce", "ageRange"), sent.keys)
        assertEquals("google-id-token", sent["idToken"]!!.jsonPrimitive.content)
        assertEquals(NONCE, sent["nonce"]!!.jsonPrimitive.content)
        val age = sent["ageRange"]!!.jsonObject
        assertEquals("shared", age["availability"]!!.jsonPrimitive.content)
        assertEquals("google_play_age_signals", age["source"]!!.jsonPrimitive.content)
        assertEquals(18, age["lowerBound"]!!.jsonPrimitive.int)
        assertEquals("required, so sent as null", JsonNull, age["upperBound"])
        assertEquals("SHARED", age["platformStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun appleWebStartSendsThePkceChallengeAndReturnsTheState() = runTest {
        server.enqueue(
            MockResponse(code = 200, body = """{"state":"$APPLE_STATE","expiresAt":"2026-10-26T10:10:00Z"}""")
        )

        assertEquals(APPLE_STATE, api.startAppleWeb(codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/apple/web", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
        val sent = body(request.body!!.utf8())
        assertEquals(setOf("codeChallenge", "codeChallengeMethod"), sent.keys)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", sent["codeChallenge"]!!.jsonPrimitive.content)
        assertEquals("S256", sent["codeChallengeMethod"]!!.jsonPrimitive.content)
    }

    @Test
    fun appleExchangeTradesTheCodeAndVerifierForASession() = runTest {
        server.enqueue(MockResponse(code = 200, body = signedIn))

        val session =
            api.exchangeApple(
                code = APPLE_CODE,
                codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                nonce = NONCE,
                ageRange = AgeRange.unavailable(null)
            )

        assertEquals("zks_tok0000000000000000000000000000000000000000", session.token)
        val request = server.takeRequest()
        assertEquals("/v1/auth/apple/exchange", request.url.encodedPath)
        val sent = body(request.body!!.utf8())
        assertEquals(setOf("code", "codeVerifier", "nonce", "ageRange"), sent.keys)
        assertEquals(APPLE_CODE, sent["code"]!!.jsonPrimitive.content)
        assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", sent["codeVerifier"]!!.jsonPrimitive.content)
        assertEquals(NONCE, sent["nonce"]!!.jsonPrimitive.content)
        val age = sent["ageRange"]!!.jsonObject
        assertEquals("unavailable", age["availability"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, age["lowerBound"])
        assertFalse(age.containsKey("platformStatus"))
    }

    @Test
    fun aUsedOrWrongAppleCodeIsInvalidCode() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"invalid_code","message":"x"}}"""))

        val e = expectError { api.exchangeApple(APPLE_CODE, "v".repeat(43), NONCE, AgeRange.unavailable(null)) }

        assertEquals("invalid_code", e.code)
        assertEquals(true, e.isInvalidTokenOrNonce)
    }

    @Test
    fun minorNotAllowedIsReportedAsMinor() = runTest {
        server.enqueue(
            MockResponse(code = 403, body = """{"error":{"code":"minor_not_allowed","message":"Adults only."}}""")
        )

        val e = expectError { api.signInWithGoogle("t", NONCE, AgeRange.unavailable(null)) }

        assertEquals(403, e.status)
        assertEquals("minor_not_allowed", e.code)
        assertEquals(true, e.isMinor)
    }

    @Test
    fun ageDeclinedIsReportedAsDeclined() = runTest {
        server.enqueue(MockResponse(code = 403, body = """{"error":{"code":"age_declined","message":"x"}}"""))

        val e = expectError { api.exchangeApple(APPLE_CODE, "v".repeat(43), NONCE, AgeRange.declined()) }

        assertEquals(true, e.isAgeDeclined)
        assertEquals(false, e.isMinor)
    }

    @Test
    fun aReplayedNonceIsInvalidNonce() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"invalid_nonce","message":"x"}}"""))

        val e = expectError { api.signInWithGoogle("t", NONCE, AgeRange.unavailable(null)) }

        assertEquals("invalid_nonce", e.code)
    }

    @Test
    fun rateLimitedCarriesRetryAfter() = runTest {
        server.enqueue(
            MockResponse(
                code = 429,
                headers = headersOf("Retry-After", "120"),
                body = """{"error":{"code":"rate_limited","message":"Slow down."}}"""
            )
        )

        val e = expectError { api.nonce() }

        assertEquals(true, e.isRateLimited)
        assertEquals(120L, e.retryAfterSeconds)
    }

    @Test
    fun anErrorWithoutAJsonBodyStillHasACode() = runTest {
        server.enqueue(
            MockResponse(
                code = 502,
                headers = headersOf(ContractRecorder.OFF_SPEC, "robustness: a proxy error page"),
                body = "<html>Bad gateway</html>"
            )
        )

        val e = expectError { api.me("zks_tok0000000000000000000000000000000000000000") }

        assertEquals(502, e.status)
        assertEquals("http_502", e.code)
    }

    @Test
    fun meSendsTheSessionAsABearerTokenAndReadsTheParent() = runTest {
        server.enqueue(MockResponse(code = 200, body = parentJson))

        val parent = api.me("zks_tok0000000000000000000000000000000000000000")

        assertEquals("Ana Pop", parent.displayName)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me", request.url.encodedPath)
        assertEquals("Bearer zks_tok0000000000000000000000000000000000000000", request.headers["Authorization"])
    }

    @Test
    fun logoutPostsWithTheBearerToken() = runTest {
        server.enqueue(MockResponse(code = 204, body = ""))

        api.logout("zks_tok0000000000000000000000000000000000000000")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/logout", request.url.encodedPath)
        assertEquals("Bearer zks_tok0000000000000000000000000000000000000000", request.headers["Authorization"])
    }

    @Test
    fun anExpiredSessionIsReportedAsUnauthorized() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"unauthorized","message":"x"}}"""))

        assertEquals(true, expectError { api.me("old") }.isUnauthorized)
    }

    private class FakeAttestation(var token: String? = "integrity-token-1", var fail: Boolean = false) :
        RequestAttestation {
        val hashes = mutableListOf<String>()

        override suspend fun token(requestHash: String): String? {
            hashes += requestHash
            if (fail) error("Play Integrity unavailable")
            return token
        }
    }

    private fun attested(attestation: RequestAttestation) =
        HttpAuthApi(baseUrl = server.url("/v1/"), client = ContractRecorder.client(), attestation = attestation)

    @Test
    fun googleSignInCarriesAPlayIntegrityTokenBoundToTheExactBody() = runTest {
        val attestation = FakeAttestation()
        server.enqueue(MockResponse(code = 200, body = signedIn))

        attested(attestation).signInWithGoogle("google-id-token", NONCE, AgeRange.unavailable(null))

        val request = server.takeRequest()
        assertEquals("integrity-token-1", request.headers["ZapaKid-Attestation"])
        assertEquals(listOf(Attestation.requestHash(request.body!!.toByteArray())), attestation.hashes)
    }

    @Test
    fun appleExchangeCarriesAPlayIntegrityTokenBoundToTheExactBody() = runTest {
        val attestation = FakeAttestation()
        server.enqueue(MockResponse(code = 200, body = signedIn))

        attested(attestation).exchangeApple(APPLE_CODE, "v".repeat(43), NONCE, AgeRange.unavailable(null))

        val request = server.takeRequest()
        assertEquals("integrity-token-1", request.headers["ZapaKid-Attestation"])
        assertEquals(listOf(Attestation.requestHash(request.body!!.toByteArray())), attestation.hashes)
    }

    @Test
    fun onlySignInRequestsAreAttested() = runTest {
        val attestation = FakeAttestation()
        val api = attested(attestation)
        server.enqueue(MockResponse(code = 200, body = """{"nonce":"$NONCE","expiresAt":"2026-10-26T10:10:00Z"}"""))
        server.enqueue(
            MockResponse(code = 200, body = """{"state":"$APPLE_STATE","expiresAt":"2026-10-26T10:10:00Z"}""")
        )
        server.enqueue(MockResponse(code = 200, body = parentJson))
        server.enqueue(MockResponse(code = 204, body = ""))

        api.nonce()
        api.startAppleWeb("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        api.me("zks_tok0000000000000000000000000000000000000000")
        api.logout("zks_tok0000000000000000000000000000000000000000")

        repeat(4) { assertNull(server.takeRequest().headers["ZapaKid-Attestation"]) }
        assertEquals(emptyList<String>(), attestation.hashes)
    }

    @Test
    fun withoutAnIntegrityTokenTheRequestGoesOutWithoutTheHeaderAndTheServerDecides() = runTest {
        for (attestation in listOf(FakeAttestation(token = null), FakeAttestation(fail = true))) {
            server.enqueue(MockResponse(code = 200, body = signedIn))

            attested(attestation).signInWithGoogle("google-id-token", NONCE, AgeRange.unavailable(null))

            assertNull(server.takeRequest().headers["ZapaKid-Attestation"])
        }
    }

    @Test
    fun attestationFailedIsReported() = runTest {
        server.enqueue(MockResponse(code = 403, body = """{"error":{"code":"attestation_failed","message":"x"}}"""))

        val e = expectError { api.signInWithGoogle("t", NONCE, AgeRange.unavailable(null)) }

        assertEquals(true, e.isAttestationFailed)
        assertEquals(false, e.isMinor)
    }
}
