package ro.zapakid.app.keys

import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import ro.zapakid.app.auth.Session
import ro.zapakid.app.testing.ANA
import ro.zapakid.app.testing.FAR_FUTURE
import ro.zapakid.app.testing.FakeBlockStore
import ro.zapakid.app.testing.NoReshare
import ro.zapakid.app.testing.TestBlobCipher
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.CallKeySealerException
import ro.zapakid.chat.ChatDevice
import ro.zapakid.chat.crypto.CiphertextType
import ro.zapakid.chat.crypto.MessageSealerException

/** Two phones in memory with real libsignal stores: PQXDH from a prekey bundle, then the double ratchet. */
class LibsignalSealersTest {
    private class Phone(val account: String, val device: Int) {
        val store = InMemorySignalProtocolStore(IdentityKeyPair.generate(), 1000 + device)
        val address = SignalProtocolAddress(account, device)

        /** What the key directory would hand out for this phone. */
        fun bundle(): PreKeyBundle {
            val identity = store.identityKeyPair
            val signed = ECKeyPair.generate()
            val signedRecord =
                SignedPreKeyRecord(1, 0, signed, identity.privateKey.calculateSignature(signed.publicKey.serialize()))
            store.storeSignedPreKey(1, signedRecord)
            val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberRecord =
                KyberPreKeyRecord(2, 0, kyber, identity.privateKey.calculateSignature(kyber.publicKey.serialize()))
            store.storeKyberPreKey(2, kyberRecord)
            val one = ECKeyPair.generate()
            store.storePreKey(3, PreKeyRecord(3, one))
            return PreKeyBundle(
                store.localRegistrationId,
                device,
                3,
                one.publicKey,
                1,
                signed.publicKey,
                signedRecord.signature,
                identity.publicKey,
                2,
                kyber.publicKey,
                kyberRecord.signature
            )
        }
    }

    private val ana = Phone("ana", 1)
    private val ion = Phone("ion", 2)
    private val phones = listOf(ana, ion)
    private val directory =
        PreKeyBundleSource { account -> phones.filter { it.account == account }.map { it.bundle() } }

    private fun sessions(phone: Phone) = LibsignalSessions(phone.store, phone.address, directory)

    @Test
    fun aMessageOpensOnlyAtTheRecipientAndTheReplyComesBack() = runTest {
        val anaSealer = LibsignalMessageSealer(sessions(ana))
        val ionSealer = LibsignalMessageSealer(sessions(ion))

        val first = anaSealer.seal("hi".encodeToByteArray(), ChatDevice("ion", 2))
        assertEquals("session start", CiphertextType.PreKey, first.type)
        assertArrayEquals("hi".encodeToByteArray(), ionSealer.open(first.type, first.content, ChatDevice("ana", 1)))

        val reply = ionSealer.seal("hello".encodeToByteArray(), ChatDevice("ana", 1))
        assertEquals(CiphertextType.Message, reply.type)
        assertArrayEquals("hello".encodeToByteArray(), anaSealer.open(reply.type, reply.content, ChatDevice("ion", 2)))
    }

    @Test
    fun aTamperedOrMislabelledEnvelopeDoesNotOpen() = runTest {
        val sealed = LibsignalMessageSealer(sessions(ana)).seal("hi".encodeToByteArray(), ChatDevice("ion", 2))
        val ionSealer = LibsignalMessageSealer(sessions(ion))
        val tampered = sealed.content.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }

        assertThrows(MessageSealerException::class.java) {
            kotlinx.coroutines.runBlocking { ionSealer.open(sealed.type, tampered, ChatDevice("ana", 1)) }
        }
        assertThrows(MessageSealerException::class.java) {
            kotlinx.coroutines.runBlocking {
                ionSealer.open(CiphertextType.SenderKey, sealed.content, ChatDevice("ana", 1))
            }
        }
    }

    @Test
    fun callKeysRoundTripWithTheIosTypeByteAndRefuseTheWrongSender() = runTest {
        val anaCalls = LibsignalCallKeySealer(sessions(ana))
        val ionCalls = LibsignalCallKeySealer(sessions(ion))
        val material = ByteArray(45) { it.toByte() }

        val blob = anaCalls.seal(material, CallDevice("ion", "2"))
        assertEquals("first byte 1: a prekey message, as on iOS", 1, blob[0].toInt())
        assertArrayEquals(material, ionCalls.open(blob, CallDevice("ana", "1")))

        val second = anaCalls.seal(material, CallDevice("ion", "2"))
        assertThrows(CallKeySealerException::class.java) {
            kotlinx.coroutines.runBlocking { ionCalls.open(second, CallDevice("mallory", "1")) }
        }
    }

    @Test
    fun aDeviceWithoutABundleCannotBeSealedFor() = runTest {
        assertThrows(MessageSealerException::class.java) {
            kotlinx.coroutines.runBlocking {
                LibsignalMessageSealer(sessions(ana)).seal(byteArrayOf(1), ChatDevice("nobody", 1))
            }
        }
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val blockStore = FakeBlockStore()

    /** Ion's phone; replaced to give the account a new identity key. */
    private var ionNow = Phone("ion", 2)

    /** Ana's phone as the app opens it: per-account records, Block Store, registered as device 1. */
    private fun anaSource(accounts: AccountKeys): LibsignalSealerSource {
        val session = Session("zks_tok0000000000000000000000000000000000000000", FAR_FUTURE, ANA)
        return LibsignalSealerSource({ DeviceRegistration.Result.Registered(1) }, accounts, { session }) { account ->
            listOf(ionNow).filter { it.account == account }.map { it.bundle() }
        }
    }

    /** First install: the account's identity is made (as device registration does) and backed up. */
    private suspend fun firstInstall(): LibsignalSealerSource {
        val accounts = AccountKeys(folder.root.resolve("keys"), TestBlobCipher(), blockStore)
        LocalIdentity(accounts.records(ANA.id), accounts.backup(ANA.id)).resolve(false, NoReshare)
        return anaSource(accounts)
    }

    /** App data and its Keystore key gone, Block Store kept. */
    private fun reinstall(): LibsignalSealerSource {
        folder.root.resolve("keys").deleteRecursively()
        return anaSource(AccountKeys(folder.root.resolve("keys"), TestBlobCipher(), blockStore))
    }

    private suspend fun LibsignalSealerSource.chat() = checkNotNull(sealers()) { "sealers not ready" }.chat

    private suspend fun trustIon() {
        val sealed = firstInstall().chat().seal("hi".encodeToByteArray(), ChatDevice("ion", 2))
        assertArrayEquals(
            "hi".encodeToByteArray(),
            LibsignalMessageSealer(LibsignalSessions(ionNow.store, ionNow.address, directory))
                .open(sealed.type, sealed.content, ChatDevice(ANA.id, 1))
        )
        val ionKey = Base64.getEncoder().encodeToString(ionNow.store.identityKeyPair.publicKey.serialize())
        assertEquals(
            "the app saves the trusted peer with the identity",
            listOf(TrustedPeer("ion", ionKey, pending = false)),
            BlockStoreIdentityBackup(blockStore, ANA.id).loadPeers()
        )
    }

    @Test
    fun afterAReinstallTheTrustedPeersSameKeyIsStillAccepted() = runTest {
        trustIon()

        val sealed = reinstall().chat().seal("again".encodeToByteArray(), ChatDevice("ion", 2))

        assertEquals("a new session after the reinstall", CiphertextType.PreKey, sealed.type)
        assertArrayEquals(
            "again".encodeToByteArray(),
            LibsignalMessageSealer(LibsignalSessions(ionNow.store, ionNow.address, directory))
                .open(sealed.type, sealed.content, ChatDevice(ANA.id, 1))
        )
    }

    @Test
    fun afterAReinstallAChangedKeyForTheTrustedPeerIsRefusedNotAFirstContact() = runTest {
        trustIon()
        ionNow = Phone("ion", 2) // a new identity key for the same account

        val restored = reinstall().chat()

        val refused =
            assertThrows(MessageSealerException::class.java) {
                kotlinx.coroutines.runBlocking { restored.seal("hi".encodeToByteArray(), ChatDevice("ion", 2)) }
            }
        assertTrue("an untrusted identity, not a first contact", refused.cause is UntrustedIdentityException)
    }
}
