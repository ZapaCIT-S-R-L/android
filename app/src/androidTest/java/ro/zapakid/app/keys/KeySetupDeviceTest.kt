package ro.zapakid.app.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import ro.zapakid.app.auth.KeystoreBlobCipher
import ro.zapakid.app.testing.address
import ro.zapakid.app.testing.asBundle
import java.io.File
import java.security.KeyStore

/** The same session on a device: libsignal's Android natives, the real Keystore key, files in noBackupFilesDir. */
@RunWith(AndroidJUnit4::class)
class KeySetupDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dirs = listOf("test-keys-alice", "test-keys-bob").map { File(context.noBackupFilesDir, it) }
    private val aliases = listOf("zapakid.test.keys.alice", "zapakid.test.keys.bob")

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        aliases.forEach(keystore::deleteEntry)
    }

    private fun open(i: Int): Pair<LocalIdentity.Identity, ZapaKidProtocolStore> {
        val records = EncryptedRecordStore(dirs[i], KeystoreBlobCipher(aliases[i]))
        val identity = LocalIdentity(records).getOrCreate()
        return identity to ZapaKidProtocolStore(records, identity)
    }

    @Test
    fun aSessionWorksOnTheDeviceWithKeystoreProtectedStores() {
        val (_, alice) = open(0)
        val (bobIdentity, bob) = open(1)
        val bobKeys = DeviceKeyGenerator(bobIdentity, bob).generate(oneTimeCount = 2)

        SessionBuilder(alice, address("alice"), address("bob")).process(PreKeyBundles.from(bobKeys.identityKey, bobKeys.asBundle()))
        val message = SessionCipher(alice, address("alice"), address("bob")).encrypt("Hi".encodeToByteArray())

        val (_, bobAgain) = open(1)
        assertEquals("Hi", SessionCipher(bobAgain, address("bob"), address("alice")).decrypt(PreKeySignalMessage(message.serialize())).decodeToString())
        assertTrue(dirs.all { it.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath) })
    }
}
