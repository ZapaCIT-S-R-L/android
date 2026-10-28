package ro.zapakid.app.testing

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import ro.zapakid.app.auth.BlobCipher

/**
 * AES-GCM with an in-memory key, standing in for the Android Keystore key in JVM tests. Test-only.
 * Share one instance between two stores to simulate the same phone (same Keystore key) after a restart.
 */
class TestBlobCipher : BlobCipher {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }
}
