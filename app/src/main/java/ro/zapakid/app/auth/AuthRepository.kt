package ro.zapakid.app.auth

import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import ro.zapakid.app.auth.signin.AppleAuthorization
import ro.zapakid.app.auth.signin.AppleBrowser
import ro.zapakid.app.auth.signin.AppleReturn
import ro.zapakid.app.auth.signin.AppleSignInConfig
import ro.zapakid.app.auth.signin.IdTokenProvider
import ro.zapakid.app.auth.signin.SignInCancelled
import ro.zapakid.app.auth.signin.SignInFailed
import ro.zapakid.app.auth.signin.SignInNotConfigured

sealed interface SignInResult {
    data class SignedIn(val parent: Parent) : SignInResult

    /** Under 18 (minor_not_allowed): parent accounts are for adults only. */
    data object Blocked : SignInResult

    /** The API couldn't verify a genuine app on a genuine phone (attestation_failed). */
    data object AttestationFailed : SignInResult

    /** The parent declined to share an age range (age_declined). */
    data object AgeDeclined : SignInResult

    /** The parent closed the Google/Apple sheet. */
    data object Cancelled : SignInResult

    data class Failed(val error: SignInError, val retryAfterSeconds: Long? = null) : SignInResult
}

enum class SignInError { NETWORK, SERVER, NOT_CONFIGURED, PROVIDER, RATE_LIMITED }

/**
 * Parent sign-in, session restore and logout. Sign-in order (openapi.yaml, Sign-in flow): age signal (a clear minor
 * stops here), one-time nonce from the API, provider sheet with SHA-256(nonce), then the API with the raw nonce.
 * The session is only ever kept in [store] (encrypted on the device).
 */
class AuthRepository(
    private val api: AuthApi,
    private val store: SessionStore,
    private val ageSignals: AgeSignalsSource,
    private val appleConfig: AppleSignInConfig,
    private val now: () -> Instant = Instant::now
) {
    /** The parent of a stored, unexpired session, without a network call. */
    fun current(): Parent? = validSession()?.parent

    /** The stored, unexpired session token, for the account API calls (families). Never logged. */
    fun token(): String? = validSession()?.token
    /** The stored, unexpired session (for the API calls after sign-in), without a network call. */
    fun currentSession(): Session? = validSession()

    /** Google: Credential Manager sheet with SHA-256(nonce), then POST /v1/auth/google. */
    suspend fun signInWithGoogle(sheet: IdTokenProvider): SignInResult = signIn { nonce, age ->
        val token = sheet.signIn(Nonces.providerNonce(nonce))
        api.signInWithGoogle(token.token, nonce, age)
    }

    /** Apple web flow: PKCE, POST /v1/auth/apple/web, browser to Apple and back by App Link, then exchange. */
    suspend fun signInWithApple(browser: AppleBrowser): SignInResult {
        if (appleConfig.servicesId.isBlank()) return SignInResult.Failed(SignInError.NOT_CONFIGURED)
        return signIn { nonce, age ->
            val verifier = Pkce.verifier()
            val state = api.startAppleWeb(Pkce.challenge(verifier))
            val returned = browser.authorize(AppleAuthorization.url(appleConfig, state, Nonces.providerNonce(nonce)))
            when (val result = AppleAuthorization.parseReturn(returned, state)) {
                is AppleReturn.Code -> api.exchangeApple(result.code, verifier, nonce, age)
                else -> throw result.asFailure()
            }
        }
    }

    /** Shared by both providers: the age check, the one-time nonce, storing the session, and error mapping. */
    @Suppress("SwallowedException") // errors become results; exceptions carry no data worth keeping
    private suspend fun signIn(steps: suspend (nonce: String, age: AgeRange) -> Session): SignInResult {
        val age = ageSignals.check()
        // A clear minor stops here: no nonce, no sign-in sheet, nothing sent.
        if (age.isMinor) return SignInResult.Blocked
        return try {
            val session = steps(api.nonce(), age)
            store.save(session)
            SignInResult.SignedIn(session.parent)
        } catch (e: SignInCancelled) {
            SignInResult.Cancelled
        } catch (e: SignInNotConfigured) {
            SignInResult.Failed(SignInError.NOT_CONFIGURED)
        } catch (e: SignInFailed) {
            SignInResult.Failed(SignInError.PROVIDER)
        } catch (e: AuthApiException) {
            when {
                e.isMinor -> SignInResult.Blocked
                e.isAgeDeclined -> SignInResult.AgeDeclined
                e.isAttestationFailed -> SignInResult.AttestationFailed
                e.isRateLimited -> SignInResult.Failed(SignInError.RATE_LIMITED, e.retryAfterSeconds)
                e.isInvalidTokenOrNonce -> SignInResult.Failed(SignInError.PROVIDER)
                else -> SignInResult.Failed(SignInError.SERVER)
            }
        } catch (e: IOException) {
            SignInResult.Failed(SignInError.NETWORK)
        }
    }

    /** Refreshes the profile of a stored session. Drops the session if it expired or the API rejects it. */
    @Suppress("SwallowedException") // offline or server trouble: keep the local session
    suspend fun restore(): Parent? {
        val session = validSession() ?: return null
        return try {
            val parent = api.me(session.token)
            store.save(session.copy(parent = parent))
            parent
        } catch (e: AuthApiException) {
            if (e.isUnauthorized) {
                store.clear()
                null
            } else {
                session.parent
            }
        } catch (e: IOException) {
            session.parent
        }
    }

    /** Signs out on this phone always; tells the API when it can. */
    suspend fun logout() {
        val session = store.load()
        store.clear()
        if (session == null) return
        try {
            api.logout(session.token)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception
        ) {
            // The local session is already gone; the server session expires on its own.
        }
    }

    @Suppress("SwallowedException")
    private fun validSession(): Session? {
        val session = store.load() ?: return null
        val expired =
            try {
                !Instant.parse(session.expiresAt).isAfter(now())
            } catch (e: DateTimeParseException) {
                true
            }
        if (expired) store.clear()
        return session.takeUnless { expired }
    }
}

private const val PROVIDER_UNAVAILABLE = "provider_unavailable"
private const val HTTP_UNAVAILABLE = 503

/** A return link that isn't a code, as the exception the shared sign-in mapping understands. */
private fun AppleReturn.asFailure(): Exception = when (this) {
    AppleReturn.Cancelled -> SignInCancelled()

    is AppleReturn.Error ->
        if (code == PROVIDER_UNAVAILABLE) {
            AuthApiException(HTTP_UNAVAILABLE, code, "Apple is unavailable")
        } else {
            SignInFailed("Apple web flow: $code")
        }

    else -> SignInFailed("Apple return link rejected")
}
