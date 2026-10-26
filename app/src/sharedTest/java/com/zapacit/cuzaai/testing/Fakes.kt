package com.zapacit.cuzaai.testing

import com.zapacit.cuzaai.auth.AgeRange
import com.zapacit.cuzaai.auth.AgeSignalsSource
import com.zapacit.cuzaai.auth.AuthApi
import com.zapacit.cuzaai.auth.AuthApiException
import com.zapacit.cuzaai.auth.Parent
import com.zapacit.cuzaai.auth.Provider
import com.zapacit.cuzaai.auth.Session
import com.zapacit.cuzaai.auth.SessionStore
import com.zapacit.cuzaai.auth.signin.IdToken
import com.zapacit.cuzaai.auth.signin.IdTokenProvider
import com.zapacit.cuzaai.auth.signin.SignInCancelled

val ANA = Parent(id = "7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11", email = "ana@example.com", displayName = "Ana Pop")

const val FAR_FUTURE = "2099-01-01T00:00:00Z"

/** A nonce in the contract's format (43 base64url characters). */
const val NONCE = "n0nce-n0nce-n0nce-n0nce-n0nce-n0nce-n0nce-x"

/** Stub backend: answers like the API contract, records what the app sent. */
class FakeAuthApi(
    var nonceError: Exception? = null,
    var signInError: AuthApiException? = null,
    var meError: Exception? = null,
    var logoutError: Exception? = null,
) : AuthApi {
    data class SignInCall(
        val provider: Provider,
        val token: String,
        val nonce: String,
        val displayName: String?,
        val ageRange: AgeRange,
    )

    var noncesIssued = 0
    val signIns = mutableListOf<SignInCall>()
    val logouts = mutableListOf<String>()

    override suspend fun nonce(): String {
        nonceError?.let { throw it }
        noncesIssued++
        return NONCE
    }

    override suspend fun signIn(
        provider: Provider,
        token: String,
        nonce: String,
        displayName: String?,
        ageRange: AgeRange,
    ): Session {
        signIns += SignInCall(provider, token, nonce, displayName, ageRange)
        signInError?.let { throw it }
        return Session(token = "zks_session-token-1", expiresAt = FAR_FUTURE, parent = ANA)
    }

    override suspend fun me(token: String): Parent {
        meError?.let { throw it }
        return ANA
    }

    override suspend fun logout(token: String) {
        logouts += token
        logoutError?.let { throw it }
    }
}

class InMemorySessionStore(
    var session: Session? = null,
) : SessionStore {
    override fun load(): Session? = session

    override fun save(session: Session) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

class FakeAgeSignals(
    var range: AgeRange = AgeRange.unavailable("API_NOT_AVAILABLE"),
) : AgeSignalsSource {
    var calls = 0

    override suspend fun check(): AgeRange {
        calls++
        return range
    }
}

/** A sign-in sheet that returns a fixed token, or fails like a cancelled sheet. Records the provider nonce. */
class FakeIdTokenProvider(
    var token: IdToken? = IdToken(token = "provider-id-token"),
) : IdTokenProvider {
    val providerNonces = mutableListOf<String>()

    override suspend fun signIn(providerNonce: String): IdToken {
        providerNonces += providerNonce
        return token ?: throw SignInCancelled()
    }
}
