package ro.zapakid.app.auth

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.app.auth.signin.SignInFailed
import ro.zapakid.app.auth.signin.SignInNotConfigured
import ro.zapakid.app.testing.ANA
import ro.zapakid.app.testing.APPLE_CODE
import ro.zapakid.app.testing.APPLE_CONFIG
import ro.zapakid.app.testing.APPLE_STATE
import ro.zapakid.app.testing.FAR_FUTURE
import ro.zapakid.app.testing.FakeAgeSignals
import ro.zapakid.app.testing.FakeAppleBrowser
import ro.zapakid.app.testing.FakeAuthApi
import ro.zapakid.app.testing.FakeIdTokenProvider
import ro.zapakid.app.testing.InMemorySessionStore
import ro.zapakid.app.testing.NONCE

class AuthRepositoryTest {
    private val api = FakeAuthApi()
    private val store = InMemorySessionStore()
    private val ages = FakeAgeSignals()
    private val sheet = FakeIdTokenProvider()
    private val browser = FakeAppleBrowser()
    private var now = Instant.parse("2026-10-26T10:00:00Z")
    private val repository = AuthRepository(api, store, ages, APPLE_CONFIG, now = { now })

    @Test
    fun signInUsesAFreshNonceSendsTheAgeSignalAndStoresTheSession() = runTest {
        ages.range = AgeRange.shared(18, null, "SHARED")

        val result = repository.signInWithGoogle(sheet)

        assertEquals(SignInResult.SignedIn(ANA), result)
        assertEquals(listOf(Nonces.providerNonce(NONCE)), sheet.providerNonces)
        val call = api.signIns.single()
        assertEquals("the raw nonce goes to the API", NONCE, call.nonce)
        assertEquals("provider-id-token", call.token)
        assertEquals(ages.range, call.ageRange)
        assertEquals("zks_session-token-1", store.session?.token)
    }

    @Test
    fun aMinorOnThePhoneIsBlockedBeforeAnySignInSheetOrServerCall() = runTest {
        ages.range = AgeRange.shared(13, 15, "SHARED")

        val result = repository.signInWithGoogle(sheet)

        assertEquals(SignInResult.Blocked, result)
        assertTrue(sheet.providerNonces.isEmpty())
        assertEquals(0, api.noncesIssued)
        assertTrue(api.signIns.isEmpty())
        assertNull(store.session)
    }

    @Test
    fun minorNotAllowedFromTheServerIsBlockedAndNoSessionIsKept() = runTest {
        api.signInError = AuthApiException(403, "minor_not_allowed", "Adults only.")

        assertEquals(SignInResult.Blocked, repository.signInWithApple(browser))
        assertNull(store.session)
    }

    @Test
    fun ageDeclinedFromTheServerIsItsOwnResult() = runTest {
        api.signInError = AuthApiException(403, "age_declined", "Declined.")

        assertEquals(SignInResult.AgeDeclined, repository.signInWithGoogle(sheet))
        assertNull(store.session)
    }

    @Test
    fun rateLimitedKeepsTheRetryAfter() = runTest {
        api.nonceError = AuthApiException(429, "rate_limited", "Slow down.", retryAfterSeconds = 90)

        assertEquals(SignInResult.Failed(SignInError.RATE_LIMITED, 90), repository.signInWithGoogle(sheet))
        assertTrue("no sheet without a nonce", sheet.providerNonces.isEmpty())
    }

    @Test
    fun anInvalidNonceAsksToSignInAgain() = runTest {
        api.signInError = AuthApiException(401, "invalid_nonce", "Replay.")

        assertEquals(SignInResult.Failed(SignInError.PROVIDER), repository.signInWithGoogle(sheet))
    }

    @Test
    fun cancellingTheSheetIsACancel() = runTest {
        sheet.token = null

        assertEquals(SignInResult.Cancelled, repository.signInWithGoogle(sheet))
        assertTrue(api.signIns.isEmpty())
    }

    @Test
    fun aSheetThatIsNotConfiguredOrFailsIsReported() = runTest {
        val notConfigured = FakeIdTokenProviderThrowing(SignInNotConfigured("Google"))
        val failed = FakeIdTokenProviderThrowing(SignInFailed("boom"))

        assertEquals(
            SignInResult.Failed(SignInError.NOT_CONFIGURED),
            repository.signInWithGoogle(notConfigured)
        )
        assertEquals(SignInResult.Failed(SignInError.PROVIDER), repository.signInWithGoogle(failed))
    }

    @Test
    fun otherServerErrorsAreFailuresAndOfflineIsNetwork() = runTest {
        api.signInError = AuthApiException(503, "provider_unavailable", "Later.")
        assertEquals(SignInResult.Failed(SignInError.SERVER), repository.signInWithGoogle(sheet))

        api.nonceError = IOException("offline")
        assertEquals(SignInResult.Failed(SignInError.NETWORK), repository.signInWithGoogle(sheet))
        assertNull(store.session)
    }

    @Test
    fun appleWebFlowRunsNoncePkceStartBrowserAndExchangeInOrder() = runTest {
        ages.range = AgeRange.shared(18, null, "SHARED")

        val result = repository.signInWithApple(browser)

        assertEquals(SignInResult.SignedIn(ANA), result)
        val url = browser.opened.single().toHttpUrl()
        assertEquals("ro.zapakid.signin", url.queryParameter("client_id"))
        assertEquals(APPLE_STATE, url.queryParameter("state"))
        assertEquals("Apple gets only the hash of the nonce", Nonces.providerNonce(NONCE), url.queryParameter("nonce"))
        val exchange = api.appleExchanges.single()
        assertEquals(APPLE_CODE, exchange.code)
        assertEquals(NONCE, exchange.nonce)
        assertEquals(ages.range, exchange.ageRange)
        assertEquals(
            "the verifier matches the challenge sent at start",
            api.appleChallenges.single(),
            Pkce.challenge(exchange.codeVerifier)
        )
        assertEquals("zks_session-token-1", store.session?.token)
    }

    @Test
    fun appleMinorIsBlockedBeforeTheBrowserOpens() = runTest {
        ages.range = AgeRange.shared(13, 15, "SHARED")

        assertEquals(SignInResult.Blocked, repository.signInWithApple(browser))
        assertTrue(browser.opened.isEmpty())
        assertTrue(api.appleChallenges.isEmpty())
    }

    @Test
    fun appleCancelledInTheBrowserOrAtAppleIsACancel() = runTest {
        browser.cancel = true
        assertEquals(SignInResult.Cancelled, repository.signInWithApple(browser))

        browser.cancel = false
        browser.reply = { state -> "error=user_cancelled_authorize&state=$state" }
        assertEquals(SignInResult.Cancelled, repository.signInWithApple(browser))
        assertTrue(api.appleExchanges.isEmpty())
    }

    @Test
    fun appleReturnWithAnotherStateIsNeverExchanged() = runTest {
        browser.reply = { _ -> "code=$APPLE_CODE&state=forged" }

        assertEquals(SignInResult.Failed(SignInError.PROVIDER), repository.signInWithApple(browser))
        assertTrue(api.appleExchanges.isEmpty())
    }

    @Test
    fun appleErrorsFromTheCallbackAreReported() = runTest {
        browser.reply = { state -> "error=provider_unavailable&state=$state" }
        assertEquals(SignInResult.Failed(SignInError.SERVER), repository.signInWithApple(browser))

        browser.reply = { state -> "error=invalid_state&state=$state" }
        assertEquals(SignInResult.Failed(SignInError.PROVIDER), repository.signInWithApple(browser))
    }

    @Test
    fun anInvalidAppleCodeAsksToSignInAgain() = runTest {
        api.appleExchangeError = AuthApiException(401, "invalid_code", "Used.")

        assertEquals(SignInResult.Failed(SignInError.PROVIDER), repository.signInWithApple(browser))
        assertNull(store.session)
    }

    @Test
    fun appleAgeRulesAreTheSameAsGoogle() = runTest {
        api.appleExchangeError = AuthApiException(403, "age_declined", "Declined.")
        assertEquals(SignInResult.AgeDeclined, repository.signInWithApple(browser))

        api.appleExchangeError = AuthApiException(403, "minor_not_allowed", "Adults only.")
        assertEquals(SignInResult.Blocked, repository.signInWithApple(browser))
    }

    @Test
    fun appleWithoutAServicesIdIsNotConfigured() = runTest {
        val unconfigured = AuthRepository(api, store, ages, APPLE_CONFIG.copy(servicesId = ""), now = { now })

        assertEquals(SignInResult.Failed(SignInError.NOT_CONFIGURED), unconfigured.signInWithApple(browser))
        assertTrue(browser.opened.isEmpty())
    }

    @Test
    fun appleStartRateLimitedShowsTheWait() = runTest {
        api.appleStartError = AuthApiException(429, "rate_limited", "Slow down.", retryAfterSeconds = 60)

        assertEquals(SignInResult.Failed(SignInError.RATE_LIMITED, 60), repository.signInWithApple(browser))
    }

    @Test
    fun attestationFailedIsItsOwnResultForBothProviders() = runTest {
        api.signInError = AuthApiException(403, "attestation_failed", "Not genuine.")

        assertEquals(SignInResult.AttestationFailed, repository.signInWithGoogle(sheet))
        assertEquals(SignInResult.AttestationFailed, repository.signInWithApple(browser))
        assertNull(store.session)
    }

    @Test
    fun logoutClearsTheSessionAndTellsTheServer() = runTest {
        store.session = Session("zks_tok0000000000000000000000000000000000000000", FAR_FUTURE, ANA)

        repository.logout()

        assertEquals(listOf("zks_tok0000000000000000000000000000000000000000"), api.logouts)
        assertNull(store.session)
    }

    @Test
    fun logoutClearsTheSessionEvenWhenTheServerIsUnreachable() = runTest {
        store.session = Session("zks_tok0000000000000000000000000000000000000000", FAR_FUTURE, ANA)
        api.logoutError = IOException("offline")

        repository.logout()

        assertNull(store.session)
    }

    @Test
    fun restoreRefreshesTheProfileForAStoredSession() = runTest {
        store.session =
            Session("zks_tok0000000000000000000000000000000000000000", FAR_FUTURE, ANA.copy(displayName = "Old name"))

        assertEquals(ANA, repository.restore())
        assertEquals(ANA, store.session?.parent)
    }

    @Test
    fun anExpiredSessionIsDroppedWithoutACall() = runTest {
        store.session = Session("zks_tok0000000000000000000000000000000000000000", "2026-10-26T09:59:59Z", ANA)
        api.meError = IllegalStateException("must not be called")

        assertNull(repository.current())
        assertNull(repository.restore())
        assertNull(store.session)
    }

    @Test
    fun restoreDropsASessionTheServerNoLongerAccepts() = runTest {
        store.session = Session("zks_tok0000000000000000000000000000000000000000", FAR_FUTURE, ANA)
        api.meError = AuthApiException(401, "unauthorized", "Sign in again.")

        assertNull(repository.restore())
        assertNull(store.session)
    }

    @Test
    fun restoreKeepsTheSessionWhenOfflineOrTheServerIsDown() = runTest {
        store.session = Session("zks_tok0000000000000000000000000000000000000000", FAR_FUTURE, ANA)
        api.meError = AuthApiException(503, "http_503", "Unavailable")
        assertEquals(ANA, repository.restore())

        api.meError = IOException("offline")
        assertEquals(ANA, repository.restore())
        assertEquals("zks_tok0000000000000000000000000000000000000000", store.session?.token)
    }
}

private class FakeIdTokenProviderThrowing(private val error: Exception) :
    ro.zapakid.app.auth.signin.IdTokenProvider {
    override suspend fun signIn(providerNonce: String): ro.zapakid.app.auth.signin.IdToken = throw error
}
