package ro.zapakid.app.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636, S256) for the Apple web flow: the one-time code is useless without this phone's verifier. */
object Pkce {
    private const val VERIFIER_BYTES = 32
    private val random = SecureRandom()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    /** 32 random bytes, base64url: 43 characters. */
    fun verifier(): String = base64Url.encodeToString(ByteArray(VERIFIER_BYTES).also(random::nextBytes))

    /** base64url(SHA-256(verifier)). */
    fun challenge(verifier: String): String =
        base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
}
