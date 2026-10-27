package ro.zapakid.chat.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Images and voice notes: AES-256-GCM with a fresh random key per file; the server stores only ciphertext.
 * Layout: 12-byte IV, then ciphertext + tag. The key and the SHA-256 of the ciphertext go inside the sealed message.
 */
object AttachmentCipher {
    private const val KEY = 32
    private const val IV = 12
    private const val TAG_BITS = 128

    class Sealed(val bytes: ByteArray, val key: ByteArray, val sha256: ByteArray)

    fun encrypt(plain: ByteArray, random: SecureRandom = SecureRandom()): Sealed {
        val key = ByteArray(KEY).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val bytes = iv + cipher.doFinal(plain)
        return Sealed(bytes, key, sha256(bytes))
    }

    /** Checks the hash first, so a swapped blob is rejected before decryption. */
    @Suppress("ThrowsCount")
    fun decrypt(bytes: ByteArray, key: ByteArray, sha256: ByteArray): ByteArray {
        if (!MessageDigest.isEqual(sha256(bytes), sha256)) throw MessageSealerException("attachment hash mismatch")
        if (bytes.size < IV) throw MessageSealerException("attachment too short")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, bytes, 0, IV))
            cipher.doFinal(bytes, IV, bytes.size - IV)
        } catch (e: java.security.GeneralSecurityException) {
            throw MessageSealerException("attachment failed authentication", e)
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
