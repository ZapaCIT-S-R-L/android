package ro.zapakid.app.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real Android Keystore key protecting the session on the device. */
@RunWith(AndroidJUnit4::class)
class KeystoreSessionCipherTest {
    private val alias = "zapakid.test.session"

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun encryptsAndDecryptsWithAKeystoreKey() {
        val cipher = KeystoreSessionCipher(alias)
        val plain = "secret-session-token".toByteArray()

        val sealed = cipher.encrypt(plain)

        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret-session-token"))
        assertArrayEquals(plain, cipher.decrypt(sealed))
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test
    fun aSessionStoredOnTheDeviceIsNotReadableWithoutTheKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "test-session.bin")
        val store = EncryptedSessionStore(file, KeystoreSessionCipher(alias))
        store.save(Session("zks_tok", "2099-01-01T00:00:00Z", Parent("p_1", null, "Ana Pop")))

        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        assertNull(EncryptedSessionStore(file, KeystoreSessionCipher(alias)).load())
        file.delete()
    }
}
