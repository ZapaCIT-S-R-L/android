package ro.zapakid.app.keys

import java.io.IOException
import java.nio.ByteBuffer
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.auth.Session

/** Registers this phone with the key directory; see [DeviceRegistration]. */
fun interface DeviceRegistrar {
    suspend fun register(): DeviceRegistration.Result
}

/**
 * Registers this phone: resolve the account's identity (Block Store, or wait for a re-share), make this device's
 * keys once, upload them (POST /v1/devices) and remember the device ID. The public payload is kept until the
 * upload succeeds, so a retry sends the same keys instead of making new ones.
 */
class DeviceRegistration(
    private val records: EncryptedRecordStore,
    /** The Block Store copy for an account ID ([BlockStoreIdentityBackup]); one item per account. */
    private val backup: (accountId: String) -> IdentityBackup,
    private val api: DeviceApi,
    private val session: () -> Session?,
    private val reshare: IdentityReshare,
    private val oneTimeKeys: Int = DeviceKeyGenerator.MAX_ONE_TIME
) : DeviceRegistrar {
    sealed interface Result {
        data class Registered(val deviceId: Int) : Result

        data object WaitingForReshare : Result

        data class Failed(val error: Error) : Result
    }

    enum class Error { SIGNED_OUT, NETWORK, SERVER, IDENTITY_MISMATCH }

    override suspend fun register(): Result {
        val registered = records.get(KIND, DEVICE_ID)?.let { Result.Registered(ByteBuffer.wrap(it).int) }
        val current = session()
        return when {
            registered != null -> registered
            current == null -> Result.Failed(Error.SIGNED_OUT)
            else -> registerWith(current)
        }
    }

    @Suppress("SwallowedException") // errors become results
    private suspend fun registerWith(current: Session): Result =
        try {
            val registered = api.accountIdentityKey(current.token, current.parent.id)
            when (val state = LocalIdentity(records, backup(current.parent.id)).resolveVerified(registered, reshare)) {
                LocalIdentity.State.WaitingForReshare -> Result.WaitingForReshare
                is LocalIdentity.State.Ready -> upload(current.token, state.identity)
            }
        } catch (e: AuthApiException) {
            Result.Failed(
                when {
                    e.isUnauthorized -> Error.SIGNED_OUT
                    e.code == IDENTITY_MISMATCH -> Error.IDENTITY_MISMATCH
                    else -> Error.SERVER
                }
            )
        } catch (e: IOException) {
            Result.Failed(Error.NETWORK)
        }

    private suspend fun upload(token: String, identity: LocalIdentity.Identity): Result {
        val keys =
            records.get(KIND, PENDING_KEYS)?.let {
                DeviceKeys.json.decodeFromString(DeviceKeys.serializer(), it.decodeToString())
            }
                ?: DeviceKeyGenerator(identity, ZapaKidProtocolStore(records, identity)).generate(oneTimeKeys).also {
                    records.put(
                        KIND,
                        PENDING_KEYS,
                        DeviceKeys.json.encodeToString(DeviceKeys.serializer(), it).encodeToByteArray()
                    )
                }
        val deviceId = api.register(token, keys)
        records.put(KIND, DEVICE_ID, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(deviceId).array())
        records.remove(KIND, PENDING_KEYS)
        return Result.Registered(deviceId)
    }

    private companion object {
        const val KIND = "device"
        const val DEVICE_ID = "device-id"
        const val PENDING_KEYS = "pending-device-keys"

        // Not in openapi.yaml yet: the API's answer when the uploaded identity isn't the account's.
        const val IDENTITY_MISMATCH = "identity_mismatch"
    }
}
