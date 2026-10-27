package ro.zapakid.app.keys

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.IdentityKeyStore
import ro.zapakid.app.testing.FakeBlockStore
import ro.zapakid.app.testing.Phone
import ro.zapakid.app.testing.address

/**
 * Security review M1: trust on first use must not reset after a reinstall. The peers this account has trusted (and
 * pending key changes) go to Block Store with the account's identity and come back with it.
 */
class TrustBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val blockStore = FakeBlockStore()
    private val bob = address("bob-account")

    private fun reinstall(phone: Phone): Phone {
        phone.dir.deleteRecursively() // app data gone; Block Store kept
        return Phone(phone.dir, backup = blockStore, account = phone.account)
    }

    @Test
    fun aTrustedPeerGoesToBlockStoreWithTheIdentity() = runTest {
        val alice = Phone(folder.root.resolve("alice"), backup = blockStore)
        val bobKey = IdentityKeyPair.generate().publicKey
        alice.store.saveIdentity(bob, bobKey)

        val peers = alice.identityBackup.loadPeers()
        assertEquals(listOf("bob-account"), peers.map { it.name })
        assertFalse(peers.single().pending)
    }

    @Test
    fun afterAReinstallAChangedKeyIsStillAKeyChangeNotAFirstContact() = runTest {
        val alice = Phone(folder.root.resolve("alice"), backup = blockStore)
        alice.store.saveIdentity(bob, IdentityKeyPair.generate().publicKey)

        val reinstalled = reinstall(alice)
        val attacker = IdentityKeyPair.generate().publicKey

        assertFalse(
            "a different key for Bob is not trusted on first use again",
            reinstalled.store.isTrustedIdentity(bob, attacker, IdentityKeyStore.Direction.SENDING)
        )
        assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING, reinstalled.store.saveIdentity(bob, attacker))
        assertTrue(reinstalled.store.hasPendingIdentityChange(bob))
    }

    @Test
    fun aPendingChangeAndItsAcknowledgementSurviveAReinstall() = runTest {
        val alice = Phone(folder.root.resolve("alice"), backup = blockStore)
        alice.store.saveIdentity(bob, IdentityKeyPair.generate().publicKey)
        val newKey = IdentityKeyPair.generate().publicKey
        alice.store.saveIdentity(bob, newKey)

        assertTrue("pending survives", reinstall(alice).store.hasPendingIdentityChange(bob))

        val again = reinstall(alice)
        again.store.acknowledgeIdentityChange(bob)
        val afterAck = reinstall(again)
        assertFalse(afterAck.store.hasPendingIdentityChange(bob))
        assertTrue(afterAck.store.isTrustedIdentity(bob, newKey, IdentityKeyStore.Direction.SENDING))
    }

    @Test
    fun anotherAccountOnThePhoneDoesNotInheritThePeers() = runTest {
        val alice = Phone(folder.root.resolve("alice"), backup = blockStore)
        alice.store.saveIdentity(bob, IdentityKeyPair.generate().publicKey)

        val other = Phone(folder.root.resolve("other"), backup = blockStore, account = "account-b")

        assertTrue(other.identityBackup.loadPeers().isEmpty())
        assertTrue(
            other.store.isTrustedIdentity(bob, IdentityKeyPair.generate().publicKey, IdentityKeyStore.Direction.SENDING)
        )
    }

    @Test
    fun theBlockStoreItemStaysWithinGooglesSizeLimit() = runTest {
        val alice = Phone(folder.root.resolve("alice"), backup = blockStore)
        repeat(30) {
            alice.store.saveIdentity(
                address("7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f%04d".format(it)),
                IdentityKeyPair.generate().publicKey
            )
        }
        val item = blockStore.items.getValue(BlockStoreIdentityBackup.keyFor(alice.account)).bytes
        assertTrue("30 peers fit: ${item.size} bytes", item.size <= BlockStoreIdentityBackup.MAX_BYTES)
        assertEquals(30, alice.identityBackup.loadPeers().size)

        repeat(200) {
            alice.store.saveIdentity(
                address("0b8d1e2a-9c3f-4d5e-8f60-11223344%04d".format(it)),
                IdentityKeyPair.generate().publicKey
            )
        }
        val full = blockStore.items.getValue(BlockStoreIdentityBackup.keyFor(alice.account)).bytes
        assertTrue("never over the limit: ${full.size} bytes", full.size <= BlockStoreIdentityBackup.MAX_BYTES)
        assertTrue("the identity is always kept", alice.identityBackup.load() != null)
    }
}
