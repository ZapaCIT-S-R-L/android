package com.zapacit.cuzaai.auth.signin

import java.security.SecureRandom

/** An identity token from Google or Apple, with the nonce the app put into the request. */
data class IdToken(
    val idToken: String,
    val nonce: String?,
    /** Apple sends the name only on the very first sign-in, outside the token. */
    val name: String? = null
)

/** Shows the provider's sign-in UI and returns its identity token. */
interface IdTokenProvider {
    /** @throws SignInCancelled when the parent closed the sheet; [SignInNotConfigured]; [SignInFailed]. */
    suspend fun signIn(): IdToken
}

class SignInCancelled : Exception("Sign-in cancelled")

class SignInNotConfigured(what: String) : Exception("$what is not configured in this build")

class SignInFailed(message: String) : Exception(message)

/** URL-safe random value for state and nonce (256 bits from SecureRandom). */
internal object Random256 {
    private const val BYTES = 32
    private val random = SecureRandom()

    fun next(): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return Base64Url.encode(bytes)
    }
}

/** Base64url without padding (java.util.Base64: API 26+, and available in JVM unit tests). */
internal object Base64Url {
    fun encode(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
