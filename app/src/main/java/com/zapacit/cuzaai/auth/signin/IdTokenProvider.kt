package com.zapacit.cuzaai.auth.signin

/** An identity token from Google or Apple. */
data class IdToken(val token: String)

/** Shows the provider's sign-in UI and returns its identity token. */
interface IdTokenProvider {
    /**
     * @param providerNonce the hex SHA-256 of the API's nonce; the token must carry it in its nonce claim.
     * @throws SignInCancelled when the parent closed the sheet; [SignInNotConfigured]; [SignInFailed].
     */
    suspend fun signIn(providerNonce: String): IdToken
}

class SignInCancelled : Exception("Sign-in cancelled")

class SignInNotConfigured(what: String) : Exception("$what is not configured in this build")

class SignInFailed(message: String) : Exception(message)
