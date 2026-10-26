package ro.zapakid.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PKCE S256 for the Apple web flow (RFC 7636). */
class PkceTest {
    @Test
    fun challengeMatchesTheRfc7636Example() {
        // RFC 7636, Appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun verifiersAreRandomAndInTheContractFormat() {
        val a = Pkce.verifier()
        val b = Pkce.verifier()
        assertNotEquals(a, b)
        assertTrue(a, Regex("^[A-Za-z0-9._~-]{43,128}$").matches(a))
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(Pkce.challenge(a)))
    }
}
