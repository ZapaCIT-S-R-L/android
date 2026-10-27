package ro.zapakid.app.keys

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.tasks.await

/**
 * The identity key pair in Android Block Store, the one deliberately synced item. It goes to Google's cloud backup
 * only when Block Store says that backup is end-to-end encrypted (protected by the phone's screen lock); otherwise it
 * stays in Block Store on this phone (it survives a reinstall, not a new phone). Signing in to the app alone never
 * brings it back: Google's restore needs the old phone's screen lock.
 */
class BlockStoreIdentityBackup(private val blockStore: BlockStoreApi) : IdentityBackup {
    override suspend fun load(): ByteArray? = blockStore.retrieve(KEY)

    override suspend fun save(keyPair: ByteArray) =
        blockStore.store(KEY, keyPair, backupToCloud = blockStore.isEndToEndEncryptionAvailable())

    companion object {
        const val KEY = "zapakid.identity.v1"
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
