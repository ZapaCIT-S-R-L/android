package ro.zapakid.app.testing

import java.io.File
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.SignalProtocolAddress
import ro.zapakid.app.keys.BlockStoreApi
import ro.zapakid.app.keys.BlockStoreIdentityBackup
import ro.zapakid.app.keys.DeviceBundle
import ro.zapakid.app.keys.DeviceKeys
import ro.zapakid.app.keys.EncryptedRecordStore
import ro.zapakid.app.keys.IdentityReshare
import ro.zapakid.app.keys.LocalIdentity
import ro.zapakid.app.keys.ZapaKidProtocolStore

/**
 * One phone's key material in [dir], as the app opens it at start, for a new account. Same [cipher] = same phone
 * after a restart; [backup] stands in for the account's Block Store copy of the identity.
 */
class Phone(
    val dir: File,
    val cipher: TestBlobCipher = TestBlobCipher(),
    val backup: FakeBlockStore = FakeBlockStore(),
    val account: String = "account-a"
) {
    val records = EncryptedRecordStore(dir, cipher)
    val identityBackup = BlockStoreIdentityBackup(backup, account)
    val identity =
        (
            runBlocking {
                LocalIdentity(records, identityBackup).resolve(false, NoReshare)
            } as LocalIdentity.State.Ready
            )
            .identity

    /** Trust changes go to Block Store with the identity, as the app wires it. */
    val store = ZapaKidProtocolStore(records, identity) { peers -> runBlocking { identityBackup.savePeers(peers) } }

    fun reopen() = Phone(dir, cipher, backup, account)
}

/** Block Store on the JVM: a map, and whether Google's end-to-end encrypted cloud backup is available. */
class FakeBlockStore(var endToEndEncryption: Boolean = true) : BlockStoreApi {
    class Item(val bytes: ByteArray, val backupToCloud: Boolean)

    val items = mutableMapOf<String, Item>()
    var stores = 0

    /** No Google Play services, or an ApiException: every call throws. */
    var failing = false

    private fun check() {
        if (failing) throw IllegalStateException("Block Store unavailable")
    }

    override suspend fun isEndToEndEncryptionAvailable(): Boolean {
        check()
        return endToEndEncryption
    }

    override suspend fun store(key: String, bytes: ByteArray, backupToCloud: Boolean) {
        check()
        stores++
        items[key] = Item(bytes.copyOf(), backupToCloud)
    }

    override suspend fun retrieve(key: String): ByteArray? {
        check()
        return items[key]?.bytes?.copyOf()
    }
}

object NoReshare : IdentityReshare {
    override suspend fun request() = error("no re-share expected")
}

class RecordingReshare : IdentityReshare {
    var requests = 0

    override suspend fun request() {
        requests++
    }
}

/** What GET /v1/keys/{account}/{device} would return for [keys]: the first one-time EC and Kyber prekeys. */
fun DeviceKeys.asBundle(deviceId: Int = 1) = DeviceBundle(
    deviceId = deviceId,
    registrationId = registrationId,
    signedPreKey = signedPreKey,
    pqPreKey = pqPreKeys.firstOrNull() ?: pqLastResortPreKey,
    preKey = preKeys.firstOrNull()
)

fun address(name: String, device: Int = 1) = SignalProtocolAddress(name, device)
