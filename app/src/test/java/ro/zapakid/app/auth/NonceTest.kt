package ro.zapakid.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/** The provider gets the lowercase hex SHA-256 of the API's nonce (openapi.yaml, Sign-in flow step 2). */
class NonceTest {
    @Test
    fun providerNonceIsLowercaseHexSha256() {
        // printf 'abc' | shasum -a 256
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Nonces.providerNonce("abc"))
    }
}
