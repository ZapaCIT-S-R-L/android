package ro.zapakid.app.keys

import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
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
 *
 * Everything is keyed by the signed-in account ([AccountKeys]): another parent on the same phone never sees this
 * one's identity, prekeys or device ID. The device ID is also bound to the session that registered it, as on the
 * server (`POST /v1/devices` binds the session; a new session has no device). After a sign-out and sign-in the
 * phone registers again, as a new device with the same account identity and fresh prekeys.
 */
class DeviceRegistration(
    private val accounts: AccountKeys,
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

    /** Registers the signed-in account on this phone; without a session nothing is read and nothing is reported. */
    override suspend fun register(): Result {
        val current = session() ?: return Result.Failed(Error.SIGNED_OUT)
        val records = accounts.records(current.parent.id)
        if (records.get(KIND, SESSION)?.contentEquals(sessionHash(current.token)) == true) {
            records.get(KIND, DEVICE_ID)?.let { return Result.Registered(ByteBuffer.wrap(it).int) }
        } else {
            // Another session registered (or started to): the server doesn't know this one. New device, new keys.
            records.remove(KIND, DEVICE_ID)
            records.remove(KIND, PENDING_KEYS)
            records.remove(KIND, SESSION)
        }
        return registerWith(current, records)
    }

    @Suppress("SwallowedException") // errors become results
    private suspend fun registerWith(current: Session, records: EncryptedRecordStore): Result =
        try {
            val registered = api.accountIdentityKey(current.token, current.parent.id)
            when (
                val state = LocalIdentity(
                    records,
                    accounts.backup(current.parent.id)
                ).resolveVerified(registered, reshare)
            ) {
                LocalIdentity.State.WaitingForReshare -> Result.WaitingForReshare
                is LocalIdentity.State.Ready -> upload(current, state.identity, records)
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

    private suspend fun upload(
        current: Session,
        identity: LocalIdentity.Identity,
        records: EncryptedRecordStore
    ): Result {
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
                    records.put(KIND, SESSION, sessionHash(current.token))
                }
        val deviceId = api.register(current.token, keys)
        records.put(KIND, DEVICE_ID, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(deviceId).array())
        records.remove(KIND, PENDING_KEYS)
        return Result.Registered(deviceId)
    }

    /** Which session the pending keys or device ID belong to; the token itself is never written to the records. */
    private fun sessionHash(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())

    private companion object {
        const val KIND = "device"
        const val DEVICE_ID = "device-id"
        const val PENDING_KEYS = "pending-device-keys"
        const val SESSION = "session"

        // Not in openapi.yaml yet: the API's answer when the uploaded identity isn't the account's.
        const val IDENTITY_MISMATCH = "identity_mismatch"
    }
}
