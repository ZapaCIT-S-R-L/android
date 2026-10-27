package ro.zapakid.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ro.zapakid.chat.crypto.AttachmentCipher
import ro.zapakid.chat.crypto.MessageSealerException

class AttachmentCipherTest {
    private val photo = ByteArray(1000) { (it * 7).toByte() }

    @Test
    fun roundTrip() {
        val sealed = AttachmentCipher.encrypt(photo)
        assertArrayEquals(photo, AttachmentCipher.decrypt(sealed.bytes, sealed.key, sealed.sha256))
    }

    @Test
    fun tamperingOrAWrongKeyIsRejected() {
        val sealed = AttachmentCipher.encrypt(photo)
        val tampered = sealed.bytes.copyOf().also { it[20] = (it[20] + 1).toByte() }

        assertThrows(MessageSealerException::class.java) { AttachmentCipher.decrypt(tampered, sealed.key, sealed.sha256) }
        assertThrows(MessageSealerException::class.java) {
            AttachmentCipher.decrypt(tampered, sealed.key, AttachmentCipher.sha256(tampered))
        }
        assertThrows(MessageSealerException::class.java) { AttachmentCipher.decrypt(sealed.bytes, ByteArray(32), sealed.sha256) }
    }
}
