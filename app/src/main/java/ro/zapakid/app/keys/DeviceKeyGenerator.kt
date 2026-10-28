package ro.zapakid.app.keys

import java.security.SecureRandom
import java.util.Base64
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Makes this device's prekeys with libsignal: a signed EC prekey, a signed Kyber last-resort prekey (PQXDH) and
 * [generate]'s count of one-time EC and Kyber prekeys. Private records go into [store]; the result is only what the
 * server may see. Key IDs are 24-bit, consecutive from a random start (system random).
 */
class DeviceKeyGenerator(
    private val identity: LocalIdentity.Identity,
    private val store: ZapaKidProtocolStore,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val b64 = Base64.getEncoder()

    fun generate(oneTimeCount: Int = MAX_ONE_TIME): DeviceKeys {
        require(oneTimeCount in 0..MAX_ONE_TIME) { "at most $MAX_ONE_TIME one-time prekeys (contract)" }
        val ecStart = startId(oneTimeCount + 1)
        val kyberStart = startId(oneTimeCount + 1)
        val timestamp = now()

        val signed = ECKeyPair.generate()
        val signedRecord = SignedPreKeyRecord(ecStart, timestamp, signed, sign(signed.publicKey.serialize()))
        store.storeSignedPreKey(signedRecord.id, signedRecord)

        val lastResort = kyber(kyberStart, timestamp)
        store.storeLastResortKyberPreKey(lastResort.id, lastResort)

        val preKeys =
            (1..oneTimeCount).map { i ->
                PreKeyRecord(ecStart + i, ECKeyPair.generate()).also { store.storePreKey(it.id, it) }
            }
        val pqPreKeys =
            (1..oneTimeCount).map { i ->
                kyber(kyberStart + i, timestamp).also { store.storeKyberPreKey(it.id, it) }
            }

        return DeviceKeys(
            registrationId = identity.registrationId,
            identityKey = b64.encodeToString(identity.keyPair.publicKey.serialize()),
            signedPreKey = SignedPreKey(
                signedRecord.id,
                b64.encodeToString(signed.publicKey.serialize()),
                b64.encodeToString(signedRecord.signature)
            ),
            pqLastResortPreKey = published(lastResort),
            preKeys = preKeys.map { PreKey(it.id, b64.encodeToString(it.keyPair.publicKey.serialize())) },
            pqPreKeys = pqPreKeys.map(::published)
        )
    }

    private fun kyber(id: Int, timestamp: Long): KyberPreKeyRecord {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        return KyberPreKeyRecord(id, timestamp, pair, sign(pair.publicKey.serialize()))
    }

    private fun published(record: KyberPreKeyRecord) = SignedPreKey(
        record.id,
        b64.encodeToString(record.keyPair.publicKey.serialize()),
        b64.encodeToString(record.signature)
    )

    private fun sign(publicKey: ByteArray): ByteArray = identity.keyPair.privateKey.calculateSignature(publicKey)

    /** A random first ID such that [count] consecutive IDs stay within 1..MAX_KEY_ID. */
    private fun startId(count: Int): Int = 1 + random.nextInt(MAX_KEY_ID - count)

    companion object {
        const val MAX_ONE_TIME = 100
        const val MAX_KEY_ID = 0xFFFFFF
    }
}
