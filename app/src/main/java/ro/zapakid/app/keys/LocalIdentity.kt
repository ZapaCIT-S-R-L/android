package ro.zapakid.app.keys

import java.nio.ByteBuffer
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper

/** This phone's libsignal identity key pair and registration ID, made by libsignal, kept in [records]. */
class LocalIdentity(private val records: EncryptedRecordStore) {
    class Identity(
        val keyPair: IdentityKeyPair,
        /** 14-bit, 1..16380 (libsignal KeyHelper). */
        val registrationId: Int
    )

    @Synchronized
    fun getOrCreate(): Identity {
        val pair = records.get(KIND, KEY_PAIR)
        val registration = records.get(KIND, REGISTRATION_ID)
        if (pair != null && registration != null) {
            return Identity(IdentityKeyPair(pair), ByteBuffer.wrap(registration).int)
        }
        val created = Identity(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))
        records.put(KIND, KEY_PAIR, created.keyPair.serialize())
        records.put(KIND, REGISTRATION_ID, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(created.registrationId).array())
        return created
    }

    private companion object {
        const val KIND = "local-identity"
        const val KEY_PAIR = "identity-key-pair"
        const val REGISTRATION_ID = "registration-id"
    }
}
