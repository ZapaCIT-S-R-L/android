package ro.zapakid.app.keys

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * POST /v1/devices body (openapi.yaml schema DeviceKeys). Public keys and the identity's signatures only, base64.
 * pushToken is added by the device registration later.
 */
@Serializable
data class DeviceKeys(
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKey,
    val pqLastResortPreKey: SignedPreKey,
    val preKeys: List<PreKey>,
    val pqPreKeys: List<SignedPreKey>
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** An EC signed prekey or a Kyber prekey, with the identity key's signature over the serialized public key. */
@Serializable
data class SignedPreKey(val keyId: Int, val publicKey: String, val signature: String)

@Serializable
data class PreKey(val keyId: Int, val publicKey: String)

/** One device's bundle from GET /v1/keys/{accountId}/{deviceId} (openapi.yaml schema PreKeyBundle). */
@Serializable
data class DeviceBundle(
    val deviceId: Int,
    val registrationId: Int,
    val signedPreKey: SignedPreKey,
    val pqPreKey: SignedPreKey,
    val preKey: PreKey? = null
)
