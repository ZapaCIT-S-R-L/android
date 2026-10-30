package ro.zapakid.app.keys

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ro.zapakid.app.auth.Attestation
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.auth.RequestAttestation
import ro.zapakid.app.auth.readOrThrow
import ro.zapakid.app.auth.tokenFor

/**
 * The key directory (openapi.yaml, "Draft: Devices and keys"):
 *   POST /v1/devices                     DeviceKeys -> 201 {deviceId}
 *   GET  /v1/accounts/{id}/identity      200 {identityKey, changes} or 404 (no identity yet)
 */
interface DeviceApi {
    /** @return the libsignal device ID (1..127) the server gave this phone. */
    suspend fun register(token: String, keys: DeviceKeys): Int

    /** The account's registered public identity key, or null when it has none yet. */
    suspend fun accountIdentityKey(token: String, accountId: String): ByteArray?
}

class HttpDeviceApi(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    /** Play Integrity on registration, like sign-in (openapi.yaml: required on POST /v1/devices). */
    private val attestation: RequestAttestation? = null
) : DeviceApi {
    @Serializable
    private class Registered(val deviceId: Int)

    private val json = DeviceKeys.json
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun register(token: String, keys: DeviceKeys): Int {
        val bytes = json.encodeToString(DeviceKeys.serializer(), keys).encodeToByteArray()
        val request = request("devices", token).post(bytes.toRequestBody(jsonType))
        attestation.tokenFor(bytes)?.let { request.header(Attestation.HEADER, it) }
        val text = execute(request.build())
        val deviceId = json.decodeFromString(Registered.serializer(), text).deviceId
        if (deviceId !in DEVICE_IDS) throw AuthApiException(HTTP_BAD_GATEWAY, "invalid_response", "Device ID $deviceId")
        return deviceId
    }

    @Serializable
    private class AccountIdentity(val identityKey: String)

    override suspend fun accountIdentityKey(token: String, accountId: String): ByteArray? = try {
        val text = execute(request("accounts/$accountId/identity", token).get().build())
        Base64.getDecoder().decode(json.decodeFromString(AccountIdentity.serializer(), text).identityKey)
    } catch (e: AuthApiException) {
        if (e.status != HTTP_NOT_FOUND) throw e
        null
    }

    private fun request(path: String, token: String) = Request
        .Builder()
        .url(baseUrl.newBuilder().addPathSegments(path).build())
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")

    private suspend fun execute(request: Request): String =
        withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.readOrThrow(json) } }

    private companion object {
        val DEVICE_IDS = 1..127
        const val HTTP_NOT_FOUND = 404
        const val HTTP_BAD_GATEWAY = 502
    }
}
