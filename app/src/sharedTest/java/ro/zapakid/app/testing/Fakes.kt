package ro.zapakid.app.testing

import okhttp3.HttpUrl.Companion.toHttpUrl
import ro.zapakid.app.auth.AgeRange
import ro.zapakid.app.auth.AgeSignalsSource
import ro.zapakid.app.auth.AuthApi
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.auth.Parent
import ro.zapakid.app.auth.Provider
import ro.zapakid.app.auth.Session
import ro.zapakid.app.auth.SessionStore
import ro.zapakid.app.auth.signin.AppleBrowser
import ro.zapakid.app.auth.signin.AppleSignInConfig
import ro.zapakid.app.auth.signin.IdToken
import ro.zapakid.app.auth.signin.IdTokenProvider
import ro.zapakid.app.auth.signin.SignInCancelled

val ANA = Parent(id = "7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11", email = "ana@example.com", displayName = "Ana Pop")

const val FAR_FUTURE = "2099-01-01T00:00:00Z"

/** A nonce in the contract's format (43 base64url characters). */
const val NONCE = "n0nce-n0nce-n0nce-n0nce-n0nce-n0nce-n0nce-x"

/** The flow state and one-time code the API hands out in the Apple web flow (43 base64url characters each). */
const val APPLE_STATE = "st4te-st4te-st4te-st4te-st4te-st4te-st4te-x"
const val APPLE_CODE = "c0de0-c0de0-c0de0-c0de0-c0de0-c0de0-c0de0-x"

val APPLE_CONFIG =
    AppleSignInConfig(
        servicesId = "ro.zapakid.signin",
        redirectUri = "https://api-staging.zapakid.ro/v1/auth/apple/callback"
    )

/** Stub backend: answers like the API contract, records what the app sent. */
class FakeAuthApi(
    var nonceError: Exception? = null,
    var signInError: AuthApiException? = null,
    var meError: Exception? = null,
    var logoutError: Exception? = null
) : AuthApi {
    data class SignInCall(val provider: Provider, val token: String, val nonce: String, val ageRange: AgeRange)

    data class AppleExchange(val code: String, val codeVerifier: String, val nonce: String, val ageRange: AgeRange)

    var noncesIssued = 0
    var appleStartError: Exception? = null
    var appleExchangeError: AuthApiException? = null
    val signIns = mutableListOf<SignInCall>()
    val appleChallenges = mutableListOf<String>()
    val appleExchanges = mutableListOf<AppleExchange>()
    val logouts = mutableListOf<String>()

    override suspend fun nonce(): String {
        nonceError?.let { throw it }
        noncesIssued++
        return NONCE
    }

    override suspend fun signInWithGoogle(idToken: String, nonce: String, ageRange: AgeRange): Session {
        signIns += SignInCall(Provider.GOOGLE, idToken, nonce, ageRange)
        signInError?.let { throw it }
        return SESSION
    }

    override suspend fun startAppleWeb(codeChallenge: String): String {
        appleStartError?.let { throw it }
        appleChallenges += codeChallenge
        return APPLE_STATE
    }

    override suspend fun exchangeApple(code: String, codeVerifier: String, nonce: String, ageRange: AgeRange): Session {
        appleExchanges += AppleExchange(code, codeVerifier, nonce, ageRange)
        signIns += SignInCall(Provider.APPLE, code, nonce, ageRange)
        (appleExchangeError ?: signInError)?.let { throw it }
        return SESSION
    }

    override suspend fun me(token: String): Parent {
        meError?.let { throw it }
        return ANA
    }

    override suspend fun logout(token: String) {
        logouts += token
        logoutError?.let { throw it }
    }

    private companion object {
        val SESSION = Session(token = "zks_session-token-1", expiresAt = FAR_FUTURE, parent = ANA)
    }
}

class InMemorySessionStore(var session: Session? = null) : SessionStore {
    override fun load(): Session? = session

    override fun save(session: Session) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

class FakeAgeSignals(var range: AgeRange = AgeRange.unavailable("API_NOT_AVAILABLE")) : AgeSignalsSource {
    var calls = 0

    override suspend fun check(): AgeRange {
        calls++
        return range
    }
}

/** A sign-in sheet that returns a fixed token, or fails like a cancelled sheet. Records the provider nonce. */
class FakeIdTokenProvider(var token: IdToken? = IdToken(token = "provider-id-token")) : IdTokenProvider {
    val providerNonces = mutableListOf<String>()

    override suspend fun signIn(providerNonce: String): IdToken {
        providerNonces += providerNonce
        return token ?: throw SignInCancelled()
    }
}

/**
 * Stands in for the Custom Tab: records the Apple authorize URL and answers like the API's 303 to the App Link,
 * echoing the state from the URL. [reply] builds the fragment from that state.
 */
class FakeAppleBrowser(
    var reply: (state: String) -> String = { state -> "code=$APPLE_CODE&state=$state" },
    var cancel: Boolean = false
) : AppleBrowser {
    val opened = mutableListOf<String>()

    override suspend fun authorize(url: String): String {
        opened += url
        if (cancel) throw SignInCancelled()
        val state = url.toHttpUrl().queryParameter("state").orEmpty()
        return "https://zapakid.ro/app/apple#${reply(state)}"
    }
}
