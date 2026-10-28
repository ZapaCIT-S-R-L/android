package ro.zapakid.app.testing

import java.io.File
import org.signal.libsignal.protocol.SignalProtocolAddress
import ro.zapakid.app.keys.DeviceBundle
import ro.zapakid.app.keys.DeviceKeys
import ro.zapakid.app.keys.EncryptedRecordStore
import ro.zapakid.app.keys.LocalIdentity
import ro.zapakid.app.keys.ZapaKidProtocolStore

/** One phone's key material in [dir], as the app opens it at start. Same [cipher] = same phone after a restart. */
class Phone(val dir: File, val cipher: TestBlobCipher = TestBlobCipher()) {
    val records = EncryptedRecordStore(dir, cipher)
    val identity = LocalIdentity(records).getOrCreate()
    val store = ZapaKidProtocolStore(records, identity)

    fun reopen() = Phone(dir, cipher)
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
