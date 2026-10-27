package ro.zapakid.app.keys

import java.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle

/** Builds libsignal's PreKeyBundle from the API's key directory shape. libsignal checks the signatures. */
object PreKeyBundles {
    private val b64 = Base64.getDecoder()

    fun from(identityKey: String, device: DeviceBundle): PreKeyBundle = PreKeyBundle(
        device.registrationId,
        device.deviceId,
        device.preKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
        device.preKey?.let { ECPublicKey(b64.decode(it.publicKey)) },
        device.signedPreKey.keyId,
        ECPublicKey(b64.decode(device.signedPreKey.publicKey)),
        b64.decode(device.signedPreKey.signature),
        IdentityKey(b64.decode(identityKey)),
        device.pqPreKey.keyId,
        KEMPublicKey(b64.decode(device.pqPreKey.publicKey)),
        b64.decode(device.pqPreKey.signature)
    )
}
