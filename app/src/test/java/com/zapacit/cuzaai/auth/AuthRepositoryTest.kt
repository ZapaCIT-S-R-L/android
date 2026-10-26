package com.zapacit.cuzaai.auth

import com.zapacit.cuzaai.auth.signin.SignInFailed
import com.zapacit.cuzaai.auth.signin.SignInNotConfigured
import com.zapacit.cuzaai.testing.ANA
import com.zapacit.cuzaai.testing.FAR_FUTURE
import com.zapacit.cuzaai.testing.FakeAgeSignals
import com.zapacit.cuzaai.testing.FakeAuthApi
import com.zapacit.cuzaai.testing.FakeIdTokenProvider
import com.zapacit.cuzaai.testing.InMemorySessionStore
import com.zapacit.cuzaai.testing.NONCE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class AuthRepositoryTest {
    private val api = FakeAuthApi()
    private val store = InMemorySessionStore()
    private val ages = FakeAgeSignals()
    private val sheet = FakeIdTokenProvider()
    private var now = Instant.parse("2026-10-26T10:00:00Z")
    private val repository = AuthRepository(api, store, ages, now = { now })

    @Test
    fun signInUsesAFreshNonceSendsTheAgeSignalAndStoresTheSession() =
        runTest {
            ages.range = AgeRange.shared(18, null, "SHARED")

            val result = repository.signIn(Provider.GOOGLE, sheet)

            assertEquals(SignInResult.SignedIn(ANA), result)
            assertEquals(listOf(Nonces.providerNonce(NONCE)), sheet.providerNonces)
            val call = api.signIns.single()
            assertEquals("the raw nonce goes to the API", NONCE, call.nonce)
            assertEquals("provider-id-token", call.token)
            assertEquals(ages.range, call.ageRange)
            assertEquals("zks_session-token-1", store.session?.token)
        }

    @Test
    fun aMinorOnThePhoneIsBlockedBeforeAnySignInSheetOrServerCall() =
        runTest {
            ages.range = AgeRange.shared(13, 15, "SHARED")

            val result = repository.signIn(Provider.GOOGLE, sheet)

            assertEquals(SignInResult.Blocked, result)
            assertTrue(sheet.providerNonces.isEmpty())
            assertEquals(0, api.noncesIssued)
            assertTrue(api.signIns.isEmpty())
            assertNull(store.session)
        }

    @Test
    fun minorNotAllowedFromTheServerIsBlockedAndNoSessionIsKept() =
        runTest {
            api.signInError = AuthApiException(403, "minor_not_allowed", "Adults only.")

            assertEquals(SignInResult.Blocked, repository.signIn(Provider.APPLE, sheet))
            assertNull(store.session)
        }

    @Test
    fun ageDeclinedFromTheServerIsItsOwnResult() =
        runTest {
            api.signInError = AuthApiException(403, "age_declined", "Declined.")

            assertEquals(SignInResult.AgeDeclined, repository.signIn(Provider.GOOGLE, sheet))
            assertNull(store.session)
        }

    @Test
    fun rateLimitedKeepsTheRetryAfter() =
        runTest {
            api.nonceError = AuthApiException(429, "rate_limited", "Slow down.", retryAfterSeconds = 90)

            assertEquals(SignInResult.Failed(SignInError.RATE_LIMITED, 90), repository.signIn(Provider.GOOGLE, sheet))
            assertTrue("no sheet without a nonce", sheet.providerNonces.isEmpty())
        }

    @Test
    fun anInvalidNonceAsksToSignInAgain() =
        runTest {
            api.signInError = AuthApiException(401, "invalid_nonce", "Replay.")

            assertEquals(SignInResult.Failed(SignInError.PROVIDER), repository.signIn(Provider.GOOGLE, sheet))
        }

    @Test
    fun cancellingTheSheetIsACancel() =
        runTest {
            sheet.token = null

            assertEquals(SignInResult.Cancelled, repository.signIn(Provider.GOOGLE, sheet))
            assertTrue(api.signIns.isEmpty())
        }

    @Test
    fun aSheetThatIsNotConfiguredOrFailsIsReported() =
        runTest {
            val notConfigured = FakeIdTokenProviderThrowing(SignInNotConfigured("Google"))
            val failed = FakeIdTokenProviderThrowing(SignInFailed("boom"))

            assertEquals(SignInResult.Failed(SignInError.NOT_CONFIGURED), repository.signIn(Provider.GOOGLE, notConfigured))
            assertEquals(SignInResult.Failed(SignInError.PROVIDER), repository.signIn(Provider.GOOGLE, failed))
        }

    @Test
    fun otherServerErrorsAreFailuresAndOfflineIsNetwork() =
        runTest {
            api.signInError = AuthApiException(503, "provider_unavailable", "Later.")
            assertEquals(SignInResult.Failed(SignInError.SERVER), repository.signIn(Provider.GOOGLE, sheet))

            api.nonceError = IOException("offline")
            assertEquals(SignInResult.Failed(SignInError.NETWORK), repository.signIn(Provider.GOOGLE, sheet))
            assertNull(store.session)
        }

    @Test
    fun logoutClearsTheSessionAndTellsTheServer() =
        runTest {
            store.session = Session("zks_tok", FAR_FUTURE, ANA)

            repository.logout()

            assertEquals(listOf("zks_tok"), api.logouts)
            assertNull(store.session)
        }

    @Test
    fun logoutClearsTheSessionEvenWhenTheServerIsUnreachable() =
        runTest {
            store.session = Session("zks_tok", FAR_FUTURE, ANA)
            api.logoutError = IOException("offline")

            repository.logout()

            assertNull(store.session)
        }

    @Test
    fun restoreRefreshesTheProfileForAStoredSession() =
        runTest {
            store.session = Session("zks_tok", FAR_FUTURE, ANA.copy(displayName = "Old name"))

            assertEquals(ANA, repository.restore())
            assertEquals(ANA, store.session?.parent)
        }

    @Test
    fun anExpiredSessionIsDroppedWithoutACall() =
        runTest {
            store.session = Session("zks_tok", "2026-10-26T09:59:59Z", ANA)
            api.meError = IllegalStateException("must not be called")

            assertNull(repository.current())
            assertNull(repository.restore())
            assertNull(store.session)
        }

    @Test
    fun restoreDropsASessionTheServerNoLongerAccepts() =
        runTest {
            store.session = Session("zks_tok", FAR_FUTURE, ANA)
            api.meError = AuthApiException(401, "unauthorized", "Sign in again.")

            assertNull(repository.restore())
            assertNull(store.session)
        }

    @Test
    fun restoreKeepsTheSessionWhenOfflineOrTheServerIsDown() =
        runTest {
            store.session = Session("zks_tok", FAR_FUTURE, ANA)
            api.meError = AuthApiException(503, "http_503", "Unavailable")
            assertEquals(ANA, repository.restore())

            api.meError = IOException("offline")
            assertEquals(ANA, repository.restore())
            assertEquals("zks_tok", store.session?.token)
        }
}

private class FakeIdTokenProviderThrowing(
    private val error: Exception,
) : com.zapacit.cuzaai.auth.signin.IdTokenProvider {
    override suspend fun signIn(providerNonce: String): com.zapacit.cuzaai.auth.signin.IdToken = throw error
}
