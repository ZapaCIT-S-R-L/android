package ro.zapakid.app.keys

import java.nio.ByteBuffer
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * The account's libsignal identity on this phone. One identity per account (decided 2026-10-26): the key pair is
 * shared by the parent's phones through [backup] (Android Block Store), and is the only thing that leaves the phone.
 * The registration ID is per device and stays in [records], like every other key.
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
         * The account has an identity but this phone can't get it (no Block Store copy). Making a new one would look
         * like a key change to every peer, so the phone waits for another family phone to re-share it.
         */
        data object WaitingForReshare : State
    }

    /**
     * @param accountHasIdentity from the API's key directory (GET /v1/accounts/{id}/identity) once devices register.
     */
    suspend fun resolve(accountHasIdentity: Boolean, reshare: IdentityReshare): State {
        val local = records.get(KIND, KEY_PAIR)
        val backedUp = backup.load()
        val keyPair =
            when {
                local != null -> {
                    if (backedUp == null) backup.save(local)
                    local
                }

                backedUp != null ->
                    backedUp.also {
                        records.put(KIND, KEY_PAIR, it)
                        // A restored identity brings back whom it trusted: no fresh trust on first use.
                        ZapaKidProtocolStore.restoreTrust(records, backup.loadPeers())
                    }

                accountHasIdentity -> {
                    reshare.request()
                    return State.WaitingForReshare
                }

                else ->
                    IdentityKeyPair.generate().serialize().also {
                        records.put(KIND, KEY_PAIR, it)
                        backup.save(it)
                    }
            }
        return State.Ready(Identity(IdentityKeyPair(keyPair), registrationId()))
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
    }
}

/** Where the account's identity key pair is shared between the parent's phones. */
interface IdentityBackup {
    suspend fun load(): ByteArray?

    suspend fun save(keyPair: ByteArray)

    /** The peers this account trusts, backed up with the identity. */
    suspend fun loadPeers(): List<TrustedPeer>

    suspend fun savePeers(peers: List<TrustedPeer>)
}

/** Asks another family phone to re-share the identity. TODO(reshare): the flow itself (a later PR); a stub for now. */
fun interface IdentityReshare {
    suspend fun request()
}
