package com.zapacit.cuzaai.auth

import com.zapacit.cuzaai.auth.signin.IdToken
import com.zapacit.cuzaai.auth.signin.IdTokenProvider
import com.zapacit.cuzaai.auth.signin.SignInCancelled
import com.zapacit.cuzaai.auth.signin.SignInFailed
import com.zapacit.cuzaai.auth.signin.SignInNotConfigured
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException

sealed interface SignInResult {
    data class SignedIn(val parent: Parent) : SignInResult

    /** Under 18 (minor_not_allowed): parent accounts are for adults only. */
    data object Blocked : SignInResult

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
    private val now: () -> Instant = Instant::now
) {
    /** The parent of a stored, unexpired session, without a network call. */
    fun current(): Parent? = validSession()?.parent

    @Suppress("SwallowedException", "ReturnCount") // errors become results; exceptions carry no data worth keeping
    suspend fun signIn(
        provider: Provider,
        sheet: IdTokenProvider
    ): SignInResult {
        val age = ageSignals.check()
        if (age.isMinor) return SignInResult.Blocked
        return try {
            val nonce = api.nonce()
            val token: IdToken =
                try {
                    sheet.signIn(Nonces.providerNonce(nonce))
                } catch (e: SignInCancelled) {
                    return SignInResult.Cancelled
                } catch (e: SignInNotConfigured) {
                    return SignInResult.Failed(SignInError.NOT_CONFIGURED)
                } catch (e: SignInFailed) {
                    return SignInResult.Failed(SignInError.PROVIDER)
                }
            val session = api.signIn(provider, token.token, nonce, token.displayName, age)
            store.save(session)
            SignInResult.SignedIn(session.parent)
        } catch (e: AuthApiException) {
            when {
                e.isMinor -> SignInResult.Blocked
                e.isAgeDeclined -> SignInResult.AgeDeclined
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
