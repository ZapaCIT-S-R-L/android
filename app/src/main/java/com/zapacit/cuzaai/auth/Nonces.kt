package com.zapacit.cuzaai.auth

import java.security.MessageDigest

/** The sign-in nonce handling from the API contract: the provider sees only the hash of the API's nonce. */
object Nonces {
    /** Lowercase hex SHA-256 (platform MessageDigest), passed to Google/Apple as their nonce. */
    fun providerNonce(apiNonce: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(apiNonce.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
