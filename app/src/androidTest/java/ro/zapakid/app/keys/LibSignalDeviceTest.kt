package ro.zapakid.app.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair

/** libsignal's Android native library is in the APK and loads on the device. */
@RunWith(AndroidJUnit4::class)
class LibSignalDeviceTest {
    @Test
    fun signsAndVerifiesOnTheDevice() {
        val pair = IdentityKeyPair.generate()
        val signature = pair.privateKey.calculateSignature("hello".encodeToByteArray())
        assertTrue(pair.publicKey.publicKey.verifySignature("hello".encodeToByteArray(), signature))
        assertFalse(pair.publicKey.publicKey.verifySignature("other".encodeToByteArray(), signature))
    }
}
