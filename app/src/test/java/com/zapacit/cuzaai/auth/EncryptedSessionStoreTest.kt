package com.zapacit.cuzaai.auth

import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The session is stored only encrypted; the real cipher is Android Keystore (see KeystoreSessionCipherTest). */
class EncryptedSessionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val session = Session("zks_secret-session-token", "2026-11-26T10:00:00Z", Parent("p_1", "ana@example.com", "Ana Pop"))

    @Test
    fun savedSessionLoadsBack() {
        val store = EncryptedSessionStore(File(folder.root, "session.bin"), SoftwareTestCipher())

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun theFileNeverContainsTheTokenOrProfileInPlainText() {
        val file = File(folder.root, "session.bin")
        EncryptedSessionStore(file, SoftwareTestCipher()).save(session)

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret-session-token"))
        assertFalse(raw.contains("zks_"))
        assertFalse(raw.contains("ana@example.com"))
        assertFalse(raw.contains("Ana Pop"))
    }

    @Test
    fun clearDeletesTheFile() {
        val file = File(folder.root, "session.bin")
        val store = EncryptedSessionStore(file, SoftwareTestCipher())
        store.save(session)

        store.clear()

        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun noFileMeansNoSession() {
        assertNull(EncryptedSessionStore(File(folder.root, "none.bin"), SoftwareTestCipher()).load())
    }

    @Test
    fun aTamperedFileIsDiscardedNotTrusted() {
        val file = File(folder.root, "session.bin")
        val store = EncryptedSessionStore(file, SoftwareTestCipher())
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
        EncryptedSessionStore(file, SoftwareTestCipher()).save(session)

        assertNull(EncryptedSessionStore(file, SoftwareTestCipher()).load())
        assertTrue(!file.exists())
    }
}

/** AES-GCM with an in-memory key, standing in for the Keystore key on the JVM. Test-only. */
private class SoftwareTestCipher : SessionCipher {
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
