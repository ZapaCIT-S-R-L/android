package ro.zapakid.app.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair

/** libsignal is linked and works on the JVM (desktop natives of libsignal-client). */
class LibSignalTest {
    @Test
    fun anIdentityKeyPairSurvivesSerialization() {
        val pair = IdentityKeyPair.generate()
        val copy = IdentityKeyPair(pair.serialize())
        assertArrayEquals(pair.publicKey.serialize(), copy.publicKey.serialize())
        assertArrayEquals(pair.privateKey.serialize(), copy.privateKey.serialize())
    }

    @Test
    fun aSignatureVerifiesOnlyForItsMessage() {
        val pair = IdentityKeyPair.generate()
        val signature = pair.privateKey.calculateSignature("hello".encodeToByteArray())
        assertTrue(pair.publicKey.publicKey.verifySignature("hello".encodeToByteArray(), signature))
        assertFalse(pair.publicKey.publicKey.verifySignature("hellO".encodeToByteArray(), signature))
    }
}
