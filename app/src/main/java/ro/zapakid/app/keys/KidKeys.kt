package ro.zapakid.app.keys

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.family.KidSession

/** Kids' identities live on their one phone: no Block Store copy and no re-share. */
private object NoBackup : IdentityBackup {
    override suspend fun load(): ByteArray? = null

    override suspend fun save(keyPair: ByteArray) = Unit

    override suspend fun loadPeers(): List<TrustedPeer> = emptyList()

    override suspend fun savePeers(peers: List<TrustedPeer>) = Unit
}

/**
 * Key setup on a paired kid phone (api PR #74), with the kid session (`zkk_`): make the kid's libsignal identity on
 * this phone, make the device keys once, POST /v1/devices. A replaced phone gets `409 identity_mismatch` and, while
 * the pairing is under 10 minutes old, replaces the kid's identity (PUT /v1/keys/identity). A 401 means the parent
 * unpaired or removed the kid: [onRevoked] wipes the phone.
 */
class KidDeviceRegistration(
    private val accounts: AccountKeys,
    private val api: DeviceApi,
    private val session: () -> KidSession?,
    private val onRevoked: () -> Unit,
    private val oneTimeKeys: Int = DeviceKeyGenerator.MAX_ONE_TIME
) : DeviceRegistrar {
    @Suppress("ReturnCount", "SwallowedException") // errors become results
    override suspend fun register(): DeviceRegistration.Result {
        val kid = session() ?: return DeviceRegistration.Result.Failed(DeviceRegistration.Error.SIGNED_OUT)
        val records = accounts.records(kid.kid.id)
        records.get(DeviceRegistration.KIND, DeviceRegistration.DEVICE_ID)?.let {
            return DeviceRegistration.Result.Registered(ByteBuffer.wrap(it).int)
        }
        return try {
            upload(kid.token, records)
        } catch (e: AuthApiException) {
            DeviceRegistration.Result.Failed(failure(e))
        } catch (e: IOException) {
            DeviceRegistration.Result.Failed(DeviceRegistration.Error.NETWORK)
        }
    }

    private fun failure(e: AuthApiException): DeviceRegistration.Error = when {
        e.isUnauthorized -> DeviceRegistration.Error.SIGNED_OUT.also { onRevoked() }
        e.code == IDENTITY_MISMATCH || e.code == REAUTHENTICATION_REQUIRED -> DeviceRegistration.Error.IDENTITY_MISMATCH
        else -> DeviceRegistration.Error.SERVER
    }

    private suspend fun upload(token: String, records: EncryptedRecordStore): DeviceRegistration.Result {
        val state = LocalIdentity(records, NoBackup).resolve(accountHasIdentity = false) {}
        val identity = (state as LocalIdentity.State.Ready).identity
        val keys =
            records.get(DeviceRegistration.KIND, DeviceRegistration.PENDING_KEYS)?.let {
                DeviceKeys.json.decodeFromString(DeviceKeys.serializer(), it.decodeToString())
            } ?: DeviceKeyGenerator(identity, ZapaKidProtocolStore(records, identity)).generate(oneTimeKeys).also {
                records.put(
                    DeviceRegistration.KIND,
                    DeviceRegistration.PENDING_KEYS,
                    DeviceKeys.json.encodeToString(DeviceKeys.serializer(), it).encodeToByteArray()
                )
            }
        val deviceId =
            try {
                api.register(token, keys)
            } catch (e: AuthApiException) {
                // A replaced phone: the kid's old identity is gone with the old phone. Allowed right after pairing.
                if (e.code != IDENTITY_MISMATCH) throw e
                api.changeIdentity(token, keys)
            }
        records.put(
            DeviceRegistration.KIND,
            DeviceRegistration.DEVICE_ID,
            ByteBuffer.allocate(Int.SIZE_BYTES).putInt(deviceId).array()
        )
        records.remove(DeviceRegistration.KIND, DeviceRegistration.PENDING_KEYS)
        return DeviceRegistration.Result.Registered(deviceId)
    }

    private companion object {
        const val IDENTITY_MISMATCH = "identity_mismatch"
        const val REAUTHENTICATION_REQUIRED = "reauthentication_required"
    }
}

/**
 * A revoked kid phone (any 401 with the kid session): delete the kid's keys, the kid session and the chat database,
 * and send the phone back to the pairing screen ([revocations] goes up by one each time).
 */
class KidRevocation(
    private val accounts: AccountKeys,
    private val kidId: () -> String?,
    private val clearSession: () -> Unit,
    private val files: List<File>
) {
    private val count = MutableStateFlow(0)
    val revocations: StateFlow<Int> = count

    @Synchronized
    fun revoke() {
        kidId()?.let(accounts::wipe)
        clearSession()
        files.forEach { it.delete() }
        count.value += 1
    }
}
