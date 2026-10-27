package ro.zapakid.app.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import ro.zapakid.app.testing.Phone
import ro.zapakid.app.testing.address
import ro.zapakid.app.testing.asBundle

/**
 * Two phones with these stores talk through libsignal: Alice builds a session from Bob's published keys (the
 * contract shape), and both sides encrypt and decrypt. Then the key-change rule: a changed peer identity is refused
 * for sending until the parent acknowledges it, also after a restart.
 */
class SessionEndToEndTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val aliceAddress = address("alice-account")
    private val bobAddress = address("bob-account")

    private fun startSession(alice: Phone, bob: Phone): DeviceKeys {
        val bobKeys = DeviceKeyGenerator(bob.identity, bob.store).generate(oneTimeCount = 3)
        SessionBuilder(alice.store, aliceAddress, bobAddress).process(PreKeyBundles.from(bobKeys.identityKey, bobKeys.asBundle()))
        return bobKeys
    }

    @Test
    fun aliceAndBobTalkThroughTheseStores() {
        val alice = Phone(folder.newFolder("alice"))
        val bob = Phone(folder.newFolder("bob"))
        val bobKeys = startSession(alice, bob)

        val first = SessionCipher(alice.store, aliceAddress, bobAddress).encrypt("Hi Bob".encodeToByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        val bobCipher = SessionCipher(bob.reopen().store, bobAddress, aliceAddress)
        assertEquals("Hi Bob", bobCipher.decrypt(PreKeySignalMessage(first.serialize())).decodeToString())

        val reply = SessionCipher(bob.reopen().store, bobAddress, aliceAddress).encrypt("Hi Alice".encodeToByteArray())
        assertEquals("Hi Alice", SessionCipher(alice.store, aliceAddress, bobAddress).decrypt(SignalMessage(reply.serialize())).decodeToString())

        val bobStore = bob.reopen().store
        assertFalse("the one-time EC prekey is used up", bobStore.containsPreKey(bobKeys.preKeys.first().keyId))
        assertFalse("the one-time Kyber prekey is used up", bobStore.containsKyberPreKey(bobKeys.pqPreKeys.first().keyId))
        assertTrue("the last-resort Kyber prekey stays", bobStore.containsKyberPreKey(bobKeys.pqLastResortPreKey.keyId))
        try {
            bobStore.loadPreKey(bobKeys.preKeys.first().keyId)
            fail("used prekey still loadable")
        } catch (expected: InvalidKeyIdException) {
        }
    }

    @Test
    fun theLastResortKyberKeyRefusesAReusedBaseKey() {
        val bob = Phone(folder.newFolder("bob"))
        val keys = DeviceKeyGenerator(bob.identity, bob.store).generate(oneTimeCount = 1)
        val baseKey = ECKeyPair.generate().publicKey

        bob.store.markKyberPreKeyUsed(keys.pqLastResortPreKey.keyId, keys.signedPreKey.keyId, baseKey)
        try {
            bob.reopen().store.markKyberPreKeyUsed(keys.pqLastResortPreKey.keyId, keys.signedPreKey.keyId, baseKey)
            fail("reused base key accepted")
        } catch (expected: ReusedBaseKeyException) {
        }
        // Another base key is fine.
        bob.store.markKyberPreKeyUsed(keys.pqLastResortPreKey.keyId, keys.signedPreKey.keyId, ECKeyPair.generate().publicKey)
    }

    @Test
    fun firstContactIsTrusted() {
        val alice = Phone(folder.newFolder("alice"))
        val bob = Phone(folder.newFolder("bob"))
        val bobKey = bob.identity.keyPair.publicKey

        assertTrue(alice.store.isTrustedIdentity(bobAddress, bobKey, IdentityKeyStore.Direction.SENDING))
        assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, alice.store.saveIdentity(bobAddress, bobKey))
        assertTrue(alice.store.isTrustedIdentity(bobAddress, bobKey, IdentityKeyStore.Direction.SENDING))
        assertFalse(alice.store.hasPendingIdentityChange(bobAddress))
    }

    @Test
    fun aChangedPeerKeyIsRefusedForSendingUntilAcknowledgedAlsoAfterARestart() {
        val alice = Phone(folder.newFolder("alice"))
        startSession(alice, Phone(folder.newFolder("bob")))

        // Bob reinstalls without his identity: a new key for the same account.
        val newBob = Phone(folder.newFolder("bob-new"))
        val newKeys = DeviceKeyGenerator(newBob.identity, newBob.store).generate(oneTimeCount = 1)
        val newBundle = PreKeyBundles.from(newKeys.identityKey, newKeys.asBundle())
        try {
            SessionBuilder(alice.store, aliceAddress, bobAddress).process(newBundle)
            fail("a changed key was trusted silently")
        } catch (expected: UntrustedIdentityException) {
        }

        assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING, alice.store.saveIdentity(bobAddress, newBundle.identityKey))
        assertTrue(alice.store.hasPendingIdentityChange(bobAddress))
        assertFalse(alice.store.isTrustedIdentity(bobAddress, newBundle.identityKey, IdentityKeyStore.Direction.SENDING))
        assertTrue("receiving still works, so the app can show the warning", alice.store.isTrustedIdentity(bobAddress, newBundle.identityKey, IdentityKeyStore.Direction.RECEIVING))

        val restarted = alice.reopen()
        assertTrue("the pending change survives a restart", restarted.store.hasPendingIdentityChange(bobAddress))
        assertFalse(restarted.store.isTrustedIdentity(bobAddress, newBundle.identityKey, IdentityKeyStore.Direction.SENDING))

        restarted.store.acknowledgeIdentityChange(bobAddress)
        assertFalse(restarted.reopen().store.hasPendingIdentityChange(bobAddress))
        SessionBuilder(restarted.store, aliceAddress, bobAddress).process(newBundle)
        val message = SessionCipher(restarted.store, aliceAddress, bobAddress).encrypt("Hi again".encodeToByteArray())
        assertEquals("Hi again", SessionCipher(newBob.store, bobAddress, aliceAddress).decrypt(PreKeySignalMessage(message.serialize())).decodeToString())
    }
}
