package ro.zapakid.app.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.zapakid.app.testing.TestBlobCipher
import java.io.File

/** libsignal's records at rest: one encrypted file per record; names and contents never in plain text. */
class EncryptedRecordStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cipher = TestBlobCipher()

    @Test
    fun recordsRoundTripAndSurviveAReopen() {
        val dir = folder.newFolder("keys")
        EncryptedRecordStore(dir, cipher).put("session", "peer-account.1", byteArrayOf(1, 2, 3))

        val reopened = EncryptedRecordStore(dir, cipher)
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.get("session", "peer-account.1"))
        assertNull(reopened.get("session", "other.1"))
        assertNull(reopened.get("prekey", "peer-account.1"))
    }

    @Test
    fun listAndRemoveWorkPerKind() {
        val store = EncryptedRecordStore(folder.newFolder("keys"), cipher)
        store.put("prekey", "1", byteArrayOf(1))
        store.put("prekey", "2", byteArrayOf(2))
        store.put("kyber", "1", byteArrayOf(9))

        assertEquals(setOf("1", "2"), store.list("prekey").keys)
        store.remove("prekey", "1")
        assertEquals(setOf("2"), store.list("prekey").keys)
        assertEquals(setOf("1"), store.list("kyber").keys)
    }

    @Test
    fun nothingIsReadableOnDisk() {
        val dir = folder.newFolder("keys")
        val secret = "private-key-bytes-here".encodeToByteArray()
        EncryptedRecordStore(dir, cipher).put("identity", "peer-account-uuid.1", secret)

        for (file in dir.walkTopDown().filter(File::isFile)) {
            val raw = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(file.path, raw.contains("private-key-bytes-here"))
            assertFalse(file.path, raw.contains("peer-account-uuid"))
            assertFalse("the address is not in the file name", file.name.contains("peer-account-uuid"))
        }
    }

    @Test
    fun aTamperedRecordIsTreatedAsMissing() {
        val dir = folder.newFolder("keys")
        val store = EncryptedRecordStore(dir, cipher)
        store.put("prekey", "7", byteArrayOf(7))
        val file = dir.walkTopDown().first(File::isFile)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertNull(store.get("prekey", "7"))
        assertEquals(emptyMap<String, ByteArray>(), store.list("prekey"))
    }
}
