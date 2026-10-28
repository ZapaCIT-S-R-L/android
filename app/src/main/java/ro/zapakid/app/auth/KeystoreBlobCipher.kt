package ro.zapakid.app.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore (StrongBox when the phone has one).
 * The key never leaves the secure hardware; it is not in any backup, so a restored phone starts signed out.
 * Platform crypto only: no home-made primitives. Chat encryption is libsignal's job, not this class's.
 */
class KeystoreBlobCipher(private val alias: String = DEFAULT_ALIAS) : BlobCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV size" }
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val existing = existingKey() ?: throw javax.crypto.AEADBadTagException("No key")
        if (ciphertext.size <= IV_BYTES) throw javax.crypto.AEADBadTagException("Too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES))
        return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.size - IV_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    @Synchronized
    private fun key(): SecretKey = existingKey() ?: generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

    private fun generate(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            generator.init(spec)
            generator.generateKey()
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException (API 28+) is a ProviderException: fall back to the TEE-backed Keystore.
            if (!strongBox) throw e
            generate(strongBox = false)
        }
    }

    private companion object {
        const val DEFAULT_ALIAS = "zapakid.session.v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
