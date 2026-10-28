package ro.zapakid.app.keys

import java.nio.ByteBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * The account's libsignal identity on this phone. One identity per account (decided 2026-10-26): the key pair is
 * shared by the parent's phones through [backup] (Android Block Store), and is the only thing that leaves the phone.
 * The registration ID is per device and stays in [records], like every other key. Both [records] and [backup] belong
 * to one account ([AccountKeys]), so another account's identity is never read.
 */
class LocalIdentity(private val records: EncryptedRecordStore, private val backup: IdentityBackup) {
    class Identity(
        val keyPair: IdentityKeyPair,
        /** 14-bit, 1..16380 (libsignal KeyHelper); per device. */
        val registrationId: Int
    )

    sealed interface State {
        data class Ready(val identity: Identity) : State

        /**
         * The account has an identity but this phone can't get it (no Block Store copy, or only copies that don't
         * match the registered key). Making a new one would look like a key change to every peer, so the phone waits
         * for another family phone to re-share it.
         */
        data object WaitingForReshare : State
    }

    /**
     * @param accountHasIdentity from the API's key directory (GET /v1/accounts/{id}/identity) once devices register.
     * Prefer [resolveVerified] when the registered key itself is at hand.
     */
    suspend fun resolve(accountHasIdentity: Boolean, reshare: IdentityReshare): State =
        resolve(accountHasIdentity, registeredKey = null, reshare = reshare)

    /**
     * @param registeredKey the account's registered public identity key (GET /v1/accounts/{id}/identity), or null
     * when the account has none yet. A local or Block Store copy is used only when its public key matches it.
     */
    suspend fun resolveVerified(registeredKey: ByteArray?, reshare: IdentityReshare): State =
        resolve(registeredKey != null, registeredKey, reshare)

    private suspend fun resolve(accountHasIdentity: Boolean, registeredKey: ByteArray?, reshare: IdentityReshare) =
        lock.withLock {
            fun usable(keyPair: ByteArray?) = keyPair?.takeIf {
                registeredKey == null || IdentityKeyPair(it).publicKey.serialize().contentEquals(registeredKey)
            }

            val local = usable(records.get(KIND, KEY_PAIR))
            // Block Store can fail (no Google Play services, ApiException): a phone with its key carries on.
            val backedUp = if (local != null) runCatching { backup.load() }.getOrNull() else backup.load()
            val keyPair =
                when {
                    local != null -> local.also { keepBackedUp(it, stored = backedUp != null) }

                    usable(backedUp) != null -> backedUp!!.also {
                        records.put(KIND, KEY_PAIR, it)
                        records.remove(KIND, IN_CLOUD) // re-checked on the next run
                        // A restored identity brings back whom it trusted: no fresh trust on first use.
                        ZapaKidProtocolStore.restoreTrust(records, backup.loadPeers())
                    }

                    accountHasIdentity -> {
                        reshare.request()
                        return@withLock State.WaitingForReshare
                    }

                    else ->
                        IdentityKeyPair.generate().serialize().also {
                            records.put(KIND, KEY_PAIR, it)
                            keepBackedUp(it, stored = false)
                        }
                }
            State.Ready(Identity(IdentityKeyPair(keyPair), registrationId()))
        }

    /**
     * Saves the key pair to Block Store when it isn't there yet, and again once cloud backup becomes available (the
     * user set a screen lock after the first save). A failure is retried on the next run.
     */
    private suspend fun keepBackedUp(keyPair: ByteArray, stored: Boolean) {
        if (stored && records.get(KIND, IN_CLOUD) != null) return
        runCatching {
            val cloud = backup.cloudBackupAvailable()
            if (!stored || cloud) backup.save(keyPair)
            if (cloud) records.put(KIND, IN_CLOUD, byteArrayOf(1))
        }
    }

    private fun registrationId(): Int {
        records.get(KIND, REGISTRATION_ID)?.let { return ByteBuffer.wrap(it).int }
        val id = KeyHelper.generateRegistrationId(false)
        records.put(KIND, REGISTRATION_ID, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(id).array())
        return id
    }

    private companion object {
        const val KIND = "local-identity"
        const val KEY_PAIR = "identity-key-pair"
        const val REGISTRATION_ID = "registration-id"
        const val IN_CLOUD = "in-cloud-backup"

        /** One resolve at a time in this process, so two first runs can't make two identities. */
        val lock = Mutex()
    }
}

/** Where the account's identity key pair is shared between the parent's phones. */
interface IdentityBackup {
    suspend fun load(): ByteArray?

    suspend fun save(keyPair: ByteArray)

    /** The peers this account trusts, backed up with the identity. */
    suspend fun loadPeers(): List<TrustedPeer>

    suspend fun savePeers(peers: List<TrustedPeer>)

    /** Whether [save] goes to an end-to-end encrypted cloud backup now (Block Store needs a screen lock for that). */
    suspend fun cloudBackupAvailable(): Boolean = false
}

/** Asks another family phone to re-share the identity. TODO(reshare): the flow itself (a later PR); a stub for now. */
fun interface IdentityReshare {
    suspend fun request()
}
