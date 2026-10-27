package ro.zapakid.app.keys

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import java.util.Base64
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The identity key pair in Android Block Store, the one deliberately synced item. It goes to Google's cloud backup
 * only when Block Store says that backup is end-to-end encrypted (protected by the phone's screen lock); otherwise it
 * stays in Block Store on this phone (it survives a reinstall, not a new phone). Signing in to the app alone never
 * brings it back: Google's restore needs the old phone's screen lock.
 */
class BlockStoreIdentityBackup(private val blockStore: BlockStoreApi, private val accountId: String) : IdentityBackup {
    /**
     * One item per ZapaKid account; the blob names its account too, and a blob for another account is ignored. It also
     * holds the peers this account trusts (security review M1), so trust on first use doesn't reset after a reinstall.
     */
    @Serializable
    private data class Blob(
        val version: Int,
        val account: String,
        val keyPair: String,
        val peers: List<TrustedPeer> = emptyList(),
        /** Not every peer fit into Block Store's 4 KiB; the missing ones are first contacts after a restore. */
        val truncated: Boolean = false
    )

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun blob(): Blob? {
        val bytes = blockStore.retrieve(keyFor(accountId)) ?: return null
        return runCatching { json.decodeFromString(Blob.serializer(), bytes.decodeToString()) }
            .getOrNull()
            ?.takeIf { it.version == VERSION && it.account == accountId }
    }

    override suspend fun load(): ByteArray? = blob()?.let { Base64.getDecoder().decode(it.keyPair) }

    override suspend fun save(keyPair: ByteArray) {
        val peers = blob()?.peers.orEmpty()
        write(Blob(VERSION, accountId, Base64.getEncoder().encodeToString(keyPair)), peers)
    }

    override suspend fun loadPeers(): List<TrustedPeer> = blob()?.peers.orEmpty()

    /** Replaces the backed-up peers; a no-op until the identity itself is backed up. */
    override suspend fun savePeers(peers: List<TrustedPeer>) {
        val current = blob() ?: return
        write(current, peers)
    }

    private suspend fun write(base: Blob, peers: List<TrustedPeer>) {
        var kept = peers
        var bytes = encode(base.copy(peers = kept, truncated = false))
        while (bytes.size > MAX_BYTES && kept.isNotEmpty()) {
            kept = kept.dropLast(maxOf(1, kept.size / TRIM_STEP))
            bytes = encode(base.copy(peers = kept, truncated = true))
        }
        blockStore.store(keyFor(accountId), bytes, backupToCloud = blockStore.isEndToEndEncryptionAvailable())
    }

    private fun encode(blob: Blob) = json.encodeToString(Blob.serializer(), blob).encodeToByteArray()

    companion object {
        private const val VERSION = 1
        private const val TRIM_STEP = 10

        /** BlockstoreClient.MAX_SIZE. */
        const val MAX_BYTES = 4096

        fun keyFor(accountId: String) = "zapakid.identity.v1.$accountId"
    }
}

/** A peer account's identity key as this account trusts it ([pending] = changed, not yet acknowledged). */
@Serializable
data class TrustedPeer(
    @SerialName("n") val name: String,
    @SerialName("k") val key: String,
    @SerialName("p") val pending: Boolean
)

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
