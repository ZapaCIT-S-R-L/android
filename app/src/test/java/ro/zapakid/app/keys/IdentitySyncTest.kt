package ro.zapakid.app.keys

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.zapakid.app.testing.FakeBlockStore
import ro.zapakid.app.testing.NoReshare
import ro.zapakid.app.testing.RecordingReshare
import ro.zapakid.app.testing.TestBlobCipher

/**
 * One libsignal identity per account (Stefan, 2026-10-26). The identity key pair is the only item in Android Block
 * Store (end-to-end encrypted cloud backup when Google offers it); prekeys, sessions and the registration ID stay on
 * this phone. A phone that can't get the account's identity waits for a re-share and never makes a new one.
 */
class IdentitySyncTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val blockStore = FakeBlockStore()

    private fun identityOn(dir: String, cipher: TestBlobCipher = TestBlobCipher()) =
        LocalIdentity(EncryptedRecordStore(folder.root.resolve(dir), cipher), BlockStoreIdentityBackup(blockStore))

    private fun LocalIdentity.State.ready() = (this as LocalIdentity.State.Ready).identity

    @Test
    fun aNewAccountGetsItsIdentityOnceAndItIsTheOnlyBlockStoreItem() = runTest {
        val cipher = TestBlobCipher()
        val first = identityOn("phone", cipher).resolve(accountHasIdentity = false, reshare = NoReshare).ready()
        val again = identityOn("phone", cipher).resolve(accountHasIdentity = true, reshare = NoReshare).ready()

        assertArrayEquals(first.keyPair.serialize(), again.keyPair.serialize())
        assertEquals(first.registrationId, again.registrationId)
        assertEquals(setOf(BlockStoreIdentityBackup.KEY), blockStore.items.keys)
        assertArrayEquals(first.keyPair.serialize(), blockStore.items.getValue(BlockStoreIdentityBackup.KEY).bytes)
        assertEquals("stored once", 1, blockStore.stores)
    }

    @Test
    fun itGoesToTheCloudOnlyWhenGoogleEncryptsItEndToEnd() = runTest {
        identityOn("a").resolve(false, NoReshare)
        assertTrue(blockStore.items.getValue(BlockStoreIdentityBackup.KEY).backupToCloud)

        val noE2e = FakeBlockStore(endToEndEncryption = false)
        LocalIdentity(
            EncryptedRecordStore(folder.root.resolve("b"), TestBlobCipher()),
            BlockStoreIdentityBackup(noE2e)
        ).resolve(false, NoReshare)
        assertFalse(
            "without end-to-end encryption it stays on this phone",
            noE2e.items.getValue(BlockStoreIdentityBackup.KEY).backupToCloud
        )
    }

    @Test
    fun theIdentitySurvivesAReinstallFromBlockStore() = runTest {
        val before = identityOn("phone").resolve(false, NoReshare).ready()
        folder.root.resolve("phone").deleteRecursively() // app data gone, Block Store kept

        val reshare = RecordingReshare()
        val after = identityOn("phone").resolve(accountHasIdentity = true, reshare = reshare).ready()

        assertArrayEquals(before.keyPair.serialize(), after.keyPair.serialize())
        assertEquals("no re-share needed", 0, reshare.requests)
        assertTrue(after.registrationId in 1..16380)
    }

    @Test
    fun theParentsOtherPhoneUsesTheSameIdentityWithItsOwnRegistrationId() = runTest {
        val first = identityOn("phone-1").resolve(false, NoReshare).ready()
        val second = identityOn("phone-2").resolve(true, NoReshare).ready()

        assertArrayEquals(first.keyPair.serialize(), second.keyPair.serialize())
        assertTrue(second.registrationId in 1..16380)
    }

    @Test
    fun anAccountWithAnIdentityThatThisPhoneLacksWaitsForAReshareAndMakesNoNewKey() = runTest {
        val reshare = RecordingReshare()
        val records = EncryptedRecordStore(folder.root.resolve("phone"), TestBlobCipher())

        val state = LocalIdentity(
            records,
            BlockStoreIdentityBackup(blockStore)
        ).resolve(accountHasIdentity = true, reshare = reshare)

        assertEquals(LocalIdentity.State.WaitingForReshare, state)
        assertEquals("asked once", 1, reshare.requests)
        assertTrue("nothing put in Block Store", blockStore.items.isEmpty())
        assertTrue("no identity on the phone", records.list("local-identity").isEmpty())
    }

    @Test
    fun aPhoneWhoseBlockStoreCopyIsGoneRestoresIt() = runTest {
        val cipher = TestBlobCipher()
        val identity = identityOn("phone", cipher).resolve(false, NoReshare).ready()
        blockStore.items.clear()

        identityOn("phone", cipher).resolve(true, NoReshare)

        assertArrayEquals(identity.keyPair.serialize(), blockStore.items.getValue(BlockStoreIdentityBackup.KEY).bytes)
    }

    @Test
    fun prekeysAndSessionsStayOnThePhone() = runTest {
        val records = EncryptedRecordStore(folder.root.resolve("phone"), TestBlobCipher())
        val identity = LocalIdentity(records, BlockStoreIdentityBackup(blockStore)).resolve(false, NoReshare).ready()
        DeviceKeyGenerator(identity, ZapaKidProtocolStore(records, identity)).generate(oneTimeCount = 2)

        assertEquals(setOf(BlockStoreIdentityBackup.KEY), blockStore.items.keys)
        assertFalse(records.list("prekey").isEmpty())
        assertFalse(records.list("kyber-last-resort").isEmpty())
    }
}
