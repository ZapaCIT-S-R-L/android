package ro.zapakid.calls.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

/** AES-GCM frame encryption: the frame layout every engine (fake and WebRTC) uses. */
class FrameCipherTest {
    private val random = SecureRandom()
    private val opusFrame = byteArrayOf(0x78, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

    private fun ringWith(vararg epochs: Int): KeyRing = KeyRing().apply { epochs.forEach { install(CallKey.random(it, random)) } }

    @Test
    fun roundTripRestoresTheFrame() {
        val ring = ringWith(1)
        val sender = FrameCipher.Sender(ssrc = 42, ring = ring)

        val encrypted = sender.encrypt(opusFrame)

        assertArrayEquals(opusFrame, FrameCipher.decrypt(encrypted, ring))
    }

    @Test
    fun onlyTheOpusTocByteStaysInTheClear() {
        val ring = ringWith(1)
        val encrypted = FrameCipher.Sender(ssrc = 42, ring = ring).encrypt(opusFrame)

        assertEquals(opusFrame[0], encrypted[0])
        val body = encrypted.copyOfRange(1, opusFrame.size)
        assertFalse(body.contentEquals(opusFrame.copyOfRange(1, opusFrame.size)))
        // prefix + ciphertext + 16-byte tag + 12-byte IV + IV length + key index
        assertEquals(opusFrame.size + 16 + 12 + 2, encrypted.size)
        assertEquals(12, encrypted[encrypted.size - 2].toInt())
        assertEquals(1, encrypted[encrypted.size - 1].toInt())
    }

    @Test
    fun theSameFrameNeverEncryptsTheSameWayTwice() {
        val sender = FrameCipher.Sender(ssrc = 42, ring = ringWith(1))

        assertNotEquals(sender.encrypt(opusFrame).toList(), sender.encrypt(opusFrame).toList())
    }

    @Test
    fun aTamperedFrameIsRejected() {
        val ring = ringWith(1)
        val encrypted = FrameCipher.Sender(ssrc = 42, ring = ring).encrypt(opusFrame)

        for (i in listOf(0, 3, encrypted.size - 20)) {
            val tampered = encrypted.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(FrameDecryptException::class.java) { FrameCipher.decrypt(tampered, ring) }
        }
    }

    @Test
    fun aFrameFromAnotherCallKeyCannotBeRead() {
        val encrypted = FrameCipher.Sender(ssrc = 42, ring = ringWith(1)).encrypt(opusFrame)

        assertThrows(FrameDecryptException::class.java) { FrameCipher.decrypt(encrypted, ringWith(1)) }
    }

    @Test
    fun aReceiverWithoutTheEpochKeyCannotRead() {
        val sender = ringWith(1, 2)
        val receiver = KeyRing().apply { install(sender.get(1)!!) }
        val encrypted = FrameCipher.Sender(ssrc = 42, ring = sender).encrypt(opusFrame)

        assertThrows(FrameDecryptException::class.java) { FrameCipher.decrypt(encrypted, receiver) }
    }

    @Test
    fun framesSentWithThePreviousKeyStillDecryptDuringRotation() {
        val ring = ringWith(1)
        val sender = FrameCipher.Sender(ssrc = 42, ring = ring)
        val old = sender.encrypt(opusFrame)

        ring.install(CallKey.random(2, random))
        val fresh = sender.encrypt(opusFrame)

        assertEquals(2, fresh.last().toInt())
        assertArrayEquals(opusFrame, FrameCipher.decrypt(old, ring))
        assertArrayEquals(opusFrame, FrameCipher.decrypt(fresh, ring))
    }

    @Test
    fun aSenderWithoutAKeyRefusesToSendClearAudio() {
        assertThrows(IllegalStateException::class.java) { FrameCipher.Sender(ssrc = 42, ring = KeyRing()).encrypt(opusFrame) }
    }

    @Test
    fun keysAreThirtyTwoRandomBytesAndNeverPrinted() {
        val key = CallKey.random(7, random)

        assertEquals(32, key.bytes().size)
        assertFalse(key.toString().contains(key.bytes().joinToString()))
        assertNotEquals(key.bytes().toList(), CallKey.random(7, random).bytes().toList())
    }

    @Test
    fun theRingKeepsSixteenEpochsAndWipesTheOverwrittenKey() {
        val ring = KeyRing()
        val first = CallKey.random(1, random)
        ring.install(first)
        for (epoch in 2..17) ring.install(CallKey.random(epoch, random))

        assertEquals(null, ring.get(1))
        assertEquals(17, ring.current?.epoch)
        assertArrayEquals(ByteArray(32), first.bytes())
    }
}
