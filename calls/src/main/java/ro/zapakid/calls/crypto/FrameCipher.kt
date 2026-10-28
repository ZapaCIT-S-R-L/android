package ro.zapakid.calls.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FrameDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES-256-GCM for one encoded audio frame, in the layout of WebRTC's FrameCryptor (SFrame-style):
 *
 *     [clear prefix][ciphertext + 16-byte tag][12-byte IV][IV length = 12][key index]
 *
 * The clear prefix is the Opus TOC byte (1 byte), so the SFU can route frames; it is authenticated as AAD. The
 * IV is the sender's SSRC (4 bytes) and a 64-bit frame counter, unique per sender for the life of the call.
 * On a phone the native FrameCryptor encrypts with the same per-epoch keys; this class is the JVM reference
 * the fake engine and the tests use.
 */
object FrameCipher {
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    const val IV_SIZE = 12
    private const val TRAILER = IV_SIZE + 2
    const val OPUS_CLEAR_BYTES = 1
    private const val BYTE_MASK = 0xFF

    class Sender(private val ssrc: Int, private val ring: KeyRing, private val clearBytes: Int = OPUS_CLEAR_BYTES) {
        private var counter = 0L

        @Synchronized
        fun encrypt(frame: ByteArray): ByteArray {
            val key = checkNotNull(ring.current) { "no call key yet: audio is never sent in the clear" }
            val prefix = minOf(clearBytes, frame.size)
            val iv = ByteBuffer.allocate(IV_SIZE).putInt(ssrc).putLong(counter++).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.bytes(), "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(frame, 0, prefix)
            val sealed = cipher.doFinal(frame, prefix, frame.size - prefix)
            return ByteBuffer
                .allocate(prefix + sealed.size + TRAILER)
                .put(frame, 0, prefix)
                .put(sealed)
                .put(iv)
                .put(IV_SIZE.toByte())
                .put(KeyRing.indexOf(key.epoch).toByte())
                .array()
        }
    }

    fun decrypt(frame: ByteArray, ring: KeyRing, clearBytes: Int = OPUS_CLEAR_BYTES): ByteArray {
        check(frame.size >= clearBytes + TAG_BYTES + TRAILER) { "frame too short" }
        val keyIndex = frame[frame.size - 1].toInt() and BYTE_MASK
        check(frame[frame.size - 2].toInt() == IV_SIZE) { "unexpected IV length" }
        val key = ring.byIndex(keyIndex) ?: rejected("no key for index $keyIndex")
        val ivStart = frame.size - TRAILER
        val iv = frame.copyOfRange(ivStart, ivStart + IV_SIZE)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.bytes(), "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(frame, 0, clearBytes)
            frame.copyOfRange(0, clearBytes) + cipher.doFinal(frame, clearBytes, ivStart - clearBytes)
        } catch (e: GeneralSecurityException) {
            rejected("frame failed authentication", e)
        }
    }

    private inline fun check(ok: Boolean, message: () -> String) {
        if (!ok) rejected(message())
    }

    private fun rejected(message: String, cause: Throwable? = null): Nothing =
        throw FrameDecryptException(message, cause)
}
