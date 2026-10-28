package ro.zapakid.app.keys

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import java.util.Base64
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The identity key pair in Android Block Store, the one deliberately synced item. It goes to Google's cloud backup
 * only when Block Store says that backup is end-to-end encrypted (protected by the phone's screen lock); otherwise it
 * stays in Block Store on this phone (it survives a reinstall, not a new phone). Signing in to the app alone never
 * brings it back: Google's restore needs the old phone's screen lock.
 */
class BlockStoreIdentityBackup(private val blockStore: BlockStoreApi, private val accountId: String) : IdentityBackup {
    /** One item per ZapaKid account; the blob names its account too, and a blob for another account is ignored. */
    @Serializable
    private class Blob(val version: Int, val account: String, val keyPair: String)

    override suspend fun load(): ByteArray? {
        val bytes = blockStore.retrieve(keyFor(accountId)) ?: return null
        val blob = runCatching { Json.decodeFromString(Blob.serializer(), bytes.decodeToString()) }.getOrNull()
        return blob?.takeIf {
            it.version == VERSION && it.account == accountId
        }?.let { Base64.getDecoder().decode(it.keyPair) }
    }

    override suspend fun save(keyPair: ByteArray) {
        val blob = Blob(VERSION, accountId, Base64.getEncoder().encodeToString(keyPair))
        blockStore.store(
            keyFor(accountId),
            Json.encodeToString(Blob.serializer(), blob).encodeToByteArray(),
            backupToCloud = blockStore.isEndToEndEncryptionAvailable()
        )
    }

    override suspend fun cloudBackupAvailable(): Boolean = blockStore.isEndToEndEncryptionAvailable()

    companion object {
        private const val VERSION = 1

        fun keyFor(accountId: String) = "zapakid.identity.v1.$accountId"
    }
}

/** The parts of Block Store the app uses, so tests can stand in for Google Play services. */
interface BlockStoreApi {
    suspend fun isEndToEndEncryptionAvailable(): Boolean

    suspend fun store(key: String, bytes: ByteArray, backupToCloud: Boolean)

    suspend fun retrieve(key: String): ByteArray?
}

class PlayBlockStoreApi(context: Context) : BlockStoreApi {
    private val client = Blockstore.getClient(context.applicationContext)

    override suspend fun isEndToEndEncryptionAvailable(): Boolean = client.isEndToEndEncryptionAvailable.await()

    override suspend fun store(key: String, bytes: ByteArray, backupToCloud: Boolean) {
        client
            .storeBytes(
                StoreBytesData
                    .Builder()
                    .setKey(key)
                    .setBytes(bytes)
                    .setShouldBackupToCloud(backupToCloud)
                    .build()
            ).await()
    }

    override suspend fun retrieve(key: String): ByteArray? = client
        .retrieveBytes(RetrieveBytesRequest.Builder().setKeys(listOf(key)).build())
        .await()
        .blockstoreDataMap[key]
        ?.bytes
}
