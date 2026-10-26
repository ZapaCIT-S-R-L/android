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

val ANA = Parent(id = "p_1", name = "Ana Pop", email = "ana@example.com")

/** Stub backend: answers like the API contract, records what the app sent. */
class FakeAuthApi(
    var signInError: AuthApiException? = null,
    var meError: AuthApiException? = null,
    var logoutError: Exception? = null
) : AuthApi {
    data class SignInCall(
        val provider: Provider,
        val idToken: String,
        val nonce: String?,
        val name: String?,
        val ageRange: AgeRange
    )

    val signIns = mutableListOf<SignInCall>()
    val logouts = mutableListOf<String>()

    override suspend fun signIn(
        provider: Provider,
        idToken: String,
        nonce: String?,
        name: String?,
        ageRange: AgeRange
    ): Session {
        signIns += SignInCall(provider, idToken, nonce, name, ageRange)
        signInError?.let { throw it }
        return Session(token = "session-token-1", parent = ANA)
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

class InMemorySessionStore(var session: Session? = null) : SessionStore {
    override fun load(): Session? = session

    override fun save(session: Session) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

class FakeAgeSignals(var range: AgeRange = AgeRange.unknown()) : AgeSignalsSource {
    var calls = 0

    override suspend fun check(): AgeRange {
        calls++
        return range
    }
}

/** A sign-in provider that returns a fixed token, or fails like a cancelled sheet. */
class FakeIdTokenProvider(var token: IdToken? = IdToken(idToken = "google-id-token", nonce = "nonce-1")) :
    IdTokenProvider {
    var calls = 0

    override suspend fun signIn(): IdToken {
        calls++
        return token ?: throw SignInCancelled()
    }
}
