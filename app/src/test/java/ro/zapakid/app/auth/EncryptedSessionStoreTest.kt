package ro.zapakid.app.auth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.zapakid.app.testing.TestBlobCipher

/** The session is stored only encrypted; the real cipher is Android Keystore (see KeystoreBlobCipherTest). */
class EncryptedSessionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val session =
        Session("zks_secret-session-token", "2026-11-26T10:00:00Z", Parent("p_1", "ana@example.com", "Ana Pop"))

    @Test
    fun savedSessionLoadsBack() {
        val store = EncryptedSessionStore(File(folder.root, "session.bin"), TestBlobCipher())

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun theFileNeverContainsTheTokenOrProfileInPlainText() {
        val file = File(folder.root, "session.bin")
        EncryptedSessionStore(file, TestBlobCipher()).save(session)

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret-session-token"))
        assertFalse(raw.contains("zks_"))
        assertFalse(raw.contains("ana@example.com"))
        assertFalse(raw.contains("Ana Pop"))
    }

    @Test
    fun clearDeletesTheFile() {
        val file = File(folder.root, "session.bin")
        val store = EncryptedSessionStore(file, TestBlobCipher())
        store.save(session)

        store.clear()

        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun noFileMeansNoSession() {
        assertNull(EncryptedSessionStore(File(folder.root, "none.bin"), TestBlobCipher()).load())
    }

    @Test
    fun aTamperedFileIsDiscardedNotTrusted() {
        val file = File(folder.root, "session.bin")
        val store = EncryptedSessionStore(file, TestBlobCipher())
        store.save(session)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertNull(store.load())
        assertFalse("the corrupt file is removed", file.exists())
    }

    @Test
    fun aFileFromAnotherKeyIsDiscarded() {
        val file = File(folder.root, "session.bin")
        EncryptedSessionStore(file, TestBlobCipher()).save(session)

        assertNull(EncryptedSessionStore(file, TestBlobCipher()).load())
        assertTrue(!file.exists())
    }
}
