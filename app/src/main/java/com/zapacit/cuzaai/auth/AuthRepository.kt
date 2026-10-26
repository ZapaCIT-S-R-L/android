package com.zapacit.cuzaai.auth

import java.io.IOException
import kotlinx.coroutines.CancellationException

sealed interface SignInResult {
    data class SignedIn(val parent: Parent) : SignInResult

    /** Under 18: parent accounts are for adults only. */
    data object Blocked : SignInResult

    data class Failed(val error: SignInError) : SignInResult
}

enum class SignInError { NETWORK, SERVER, NOT_CONFIGURED, PROVIDER }

/** Parent sign-in, session restore and logout. The session is only ever kept in [store] (encrypted on device). */
class AuthRepository(
    private val api: AuthApi,
    private val store: SessionStore,
    private val ageSignals: AgeSignalsSource
) {
    /** The parent of the stored session, without a network call. */
    fun current(): Parent? = store.load()?.parent

    @Suppress("SwallowedException") // errors become results; exceptions carry no data worth keeping
    suspend fun signIn(provider: Provider, idToken: String, nonce: String?, name: String? = null): SignInResult {
        val age = ageSignals.check()
        // A clear minor never reaches the server: the sign-in token is dropped here.
        if (age.isMinor) return SignInResult.Blocked
        return try {
            val session = api.signIn(provider, idToken, nonce, name, age)
            store.save(session)
            SignInResult.SignedIn(session.parent)
        } catch (e: AuthApiException) {
            if (e.isMinor) SignInResult.Blocked else SignInResult.Failed(SignInError.SERVER)
        } catch (e: IOException) {
            SignInResult.Failed(SignInError.NETWORK)
        }
    }

    /** Refreshes the profile of a stored session. Drops the session if the API no longer accepts it. */
    @Suppress("SwallowedException") // offline or server trouble: keep the local session
    suspend fun restore(): Parent? {
        val session = store.load() ?: return null
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
}
