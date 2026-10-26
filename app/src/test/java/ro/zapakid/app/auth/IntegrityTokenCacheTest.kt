package ro.zapakid.app.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Play Integrity standard requests: prepare once, re-prepare when Play says the provider expired, else fail open. */
class IntegrityTokenCacheTest {
    private class Invalid : Exception("INTEGRITY_TOKEN_PROVIDER_INVALID")

    private var prepares = 0
    private val requests = mutableListOf<Pair<Int, String>>()
    private var failNext: Exception? = null

    private val cache =
        IntegrityTokenCache(
            prepare = { ++prepares },
            request = { provider, hash ->
                requests += provider to hash
                failNext?.let {
                    failNext = null
                    throw it
                }
                "token-$provider-$hash"
            },
            isProviderInvalid = { it is Invalid },
        )

    @Test
    fun preparesOnceAndReusesTheProvider() = runTest {
        assertEquals("token-1-a", cache.token("a"))
        assertEquals("token-1-b", cache.token("b"))
        assertEquals(1, prepares)
    }

    @Test
    fun anExpiredProviderIsPreparedAgainAndTheRequestRetriedOnce() = runTest {
        cache.token("a")
        failNext = Invalid()

        assertEquals("token-2-b", cache.token("b"))
        assertEquals(2, prepares)
        assertEquals(listOf(1 to "a", 1 to "b", 2 to "b"), requests)
    }

    @Test
    fun otherErrorsGiveNoTokenSoTheServerDecides() = runTest {
        failNext = IllegalStateException("PLAY_STORE_NOT_FOUND")

        assertNull(cache.token("a"))
        assertEquals("the next call works again", "token-1-b", cache.token("b"))
    }

    @Test
    fun aFailingPrepareGivesNoTokenAndIsRetriedNextTime() = runTest {
        var failPrepare = true
        val flaky =
            IntegrityTokenCache(
                prepare = { if (failPrepare) error("offline") else 7 },
                request = { provider, hash -> "token-$provider-$hash" },
                isProviderInvalid = { false },
            )

        assertNull(flaky.token("a"))
        failPrepare = false
        assertEquals("token-7-b", flaky.token("b"))
    }

    @Test
    fun requestHashIsLowercaseHexSha256OfTheBody() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Attestation.requestHash("abc".encodeToByteArray()),
        )
    }
}
