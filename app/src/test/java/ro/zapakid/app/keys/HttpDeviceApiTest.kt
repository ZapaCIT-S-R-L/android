package ro.zapakid.app.keys

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ro.zapakid.app.auth.Attestation
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.auth.RequestAttestation
import ro.zapakid.app.testing.ContractRecorder

/** Device registration against a mocked API following openapi.yaml ("Draft: Devices and keys"). */
class HttpDeviceApiTest {
    private val server = MockWebServer()
    private lateinit var api: HttpDeviceApi

    private val keys =
        DeviceKeys(
            registrationId = 4242,
            identityKey = "BQidentity",
            signedPreKey = SignedPreKey(7, "BQsigned", "c2ln"),
            pqLastResortPreKey = SignedPreKey(9, "CAkyber", "c2ln"),
            preKeys = listOf(PreKey(8, "BQone")),
            pqPreKeys = listOf(SignedPreKey(10, "CApq", "c2ln"))
        )

    @Before
    fun setUp() {
        server.start()
        api = HttpDeviceApi(server.url("/v1/"), ContractRecorder.client())
    }

    @After
    fun tearDown() = server.close()

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
    fun registerPostsTheDeviceKeysWithTheSessionAndReturnsTheDeviceId() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"deviceId":3}"""))

        assertEquals(3, api.register("zks_tok0000000000000000000000000000000000000000", keys))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/devices", request.url.encodedPath)
        assertEquals("Bearer zks_tok0000000000000000000000000000000000000000", request.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(
            setOf("registrationId", "identityKey", "signedPreKey", "pqLastResortPreKey", "preKeys", "pqPreKeys"),
            body.keys
        )
        assertEquals(4242, body["registrationId"]!!.jsonPrimitive.int)
        assertFalse("no push token yet", body.containsKey("pushToken"))
    }

    @Test
    fun aDeviceIdOutsideTheContractRangeIsRejected() = runTest {
        server.enqueue(
            MockResponse(code = 201, headers = offSpec("robustness: a server bug"), body = """{"deviceId":128}""")
        )

        assertEquals(
            "invalid_response",
            expectError {
                api.register("zks_tok0000000000000000000000000000000000000000", keys)
            }.code
        )
    }

    @Test
    fun registrationErrorsCarryTheApiCode() = runTest {
        server.enqueue(
            MockResponse(
                code = 409,
                headers = offSpec(API_GAP_DEVICE_ERRORS),
                body = """{"error":{"code":"identity_mismatch","message":"x"}}"""
            )
        )
        assertEquals(
            "identity_mismatch",
            expectError {
                api.register("zks_tok0000000000000000000000000000000000000000", keys)
            }.code
        )

        server.enqueue(
            MockResponse(
                code = 401,
                headers = offSpec(API_GAP_DEVICE_ERRORS),
                body = """{"error":{"code":"unauthorized","message":"x"}}"""
            )
        )
        assertTrue(expectError { api.register("zks_tok0000000000000000000000000000000000000000", keys) }.isUnauthorized)

        server.enqueue(
            MockResponse(
                code = 429,
                headers = headersOf("Retry-After", "60", ContractRecorder.OFF_SPEC, API_GAP_DEVICE_ERRORS),
                body = """{"error":{"code":"rate_limited","message":"x"}}"""
            )
        )
        assertEquals(
            60L,
            expectError {
                api.register("zks_tok0000000000000000000000000000000000000000", keys)
            }.retryAfterSeconds
        )
    }

    @Test
    fun theAccountsRegisteredIdentityKeyComesFromTheKeyDirectory() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"identityKey":"BQECAw==","changes":[]}"""))
        assertArrayEquals(
            byteArrayOf(5, 1, 2, 3),
            api.accountIdentityKey(
                "zks_tok0000000000000000000000000000000000000000",
                "7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11"
            )
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/accounts/7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11/identity", request.url.encodedPath)
        assertEquals("Bearer zks_tok0000000000000000000000000000000000000000", request.headers["Authorization"])

        server.enqueue(
            MockResponse(
                code = 404,
                headers = offSpec("API gap: 404 for an account without an identity is not in the contract"),
                body = """{"error":{"code":"not_found","message":"x"}}"""
            )
        )
        assertNull(
            api.accountIdentityKey(
                "zks_tok0000000000000000000000000000000000000000",
                "7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11"
            )
        )
    }

    private class FakeAttestation(val token: String? = "integrity-token-1") : RequestAttestation {
        val hashes = mutableListOf<String>()

        override suspend fun token(requestHash: String): String? {
            hashes += requestHash
            return token
        }
    }

    @Test
    fun registrationCarriesAPlayIntegrityTokenBoundToTheExactBody() = runTest {
        val attestation = FakeAttestation()
        val attested = HttpDeviceApi(server.url("/v1/"), ContractRecorder.client(), attestation)
        server.enqueue(MockResponse(code = 201, body = """{"deviceId":3}"""))

        attested.register("zks_tok0000000000000000000000000000000000000000", keys)

        val request = server.takeRequest()
        assertEquals("integrity-token-1", request.headers["ZapaKid-Attestation"])
        assertEquals(listOf(Attestation.requestHash(request.body!!.toByteArray())), attestation.hashes)
    }

    @Test
    fun theIdentityLookupIsNotAttested() = runTest {
        val attestation = FakeAttestation()
        val attested = HttpDeviceApi(server.url("/v1/"), ContractRecorder.client(), attestation)
        server.enqueue(MockResponse(code = 200, body = """{"identityKey":"BQECAw==","changes":[]}"""))

        attested.accountIdentityKey(
            "zks_tok0000000000000000000000000000000000000000",
            "7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11"
        )

        assertEquals(null, server.takeRequest().headers["ZapaKid-Attestation"])
        assertTrue(attestation.hashes.isEmpty())
    }

    @Test
    fun withoutATokenRegistrationGoesOutWithoutTheHeader() = runTest {
        val attested = HttpDeviceApi(server.url("/v1/"), ContractRecorder.client(), FakeAttestation(token = null))
        server.enqueue(MockResponse(code = 201, body = """{"deviceId":3}"""))

        attested.register("zks_tok0000000000000000000000000000000000000000", keys)

        assertEquals(null, server.takeRequest().headers["ZapaKid-Attestation"])
    }
}

private const val API_GAP_DEVICE_ERRORS = "API gap: POST /v1/devices declares only 201"

private fun offSpec(reason: String) = headersOf(ContractRecorder.OFF_SPEC, reason)
