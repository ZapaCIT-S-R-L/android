package ro.zapakid.app.keys

import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * libsignal's stores for this phone, on [records] (encrypted, never backed up).
 *
 * Key changes are never trusted silently. libsignal's in-memory store accepts a new peer identity at once; here a
 * changed identity is saved as *pending* and refused for sending until [acknowledgeIdentityChange] (after the parent
 * compares safety numbers). Receiving still works, so the app can show the warning. Pending changes are stored, so
 * they survive a restart.
 */
@Suppress("TooManyFunctions") // libsignal's SignalProtocolStore is this many functions
class ZapaKidProtocolStore(private val records: EncryptedRecordStore, private val identity: LocalIdentity.Identity) :
    SignalProtocolStore {
    @Serializable
    private class PeerIdentity(val key: String, val pending: Boolean)

    private val json = Json
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    // IdentityKeyStore

    override fun getIdentityKeyPair(): IdentityKeyPair = identity.keyPair

    override fun getLocalRegistrationId(): Int = identity.registrationId

    @Synchronized
    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange {
        val existing = peer(address)
        val key = b64.encodeToString(identityKey.serialize())
        return when {
            existing == null -> {
                // First contact: trusted (the QR at kid pairing and safety numbers add verification later).
                putPeer(address, PeerIdentity(key, pending = false))
                IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            }

            existing.key == key -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED

            else -> {
                putPeer(address, PeerIdentity(key, pending = true))
                IdentityKeyStore.IdentityChange.REPLACED_EXISTING
            }
        }
    }

    @Synchronized
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val existing = peer(address)
        return direction == IdentityKeyStore.Direction.RECEIVING ||
            existing == null ||
            (existing.key == b64.encodeToString(identityKey.serialize()) && !existing.pending)
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = peer(address)?.let {
        IdentityKey(unb64.decode(it.key))
    }

    @Synchronized
    fun hasPendingIdentityChange(address: SignalProtocolAddress): Boolean = peer(address)?.pending == true

    /** The parent has seen the warning (and compared safety numbers): trust the new key from now on. */
    @Synchronized
    fun acknowledgeIdentityChange(address: SignalProtocolAddress) {
        peer(address)?.let { putPeer(address, PeerIdentity(it.key, pending = false)) }
    }

    private fun peer(address: SignalProtocolAddress): PeerIdentity? = records.get(PEER_IDENTITY, address.name)?.let {
        json.decodeFromString(PeerIdentity.serializer(), it.decodeToString())
    }

    // One identity per account: peers are keyed by account, not device.
    private fun putPeer(address: SignalProtocolAddress, peer: PeerIdentity) = records.put(
        PEER_IDENTITY,
        address.name,
        json.encodeToString(PeerIdentity.serializer(), peer).encodeToByteArray()
    )

    // PreKeyStore

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        records.get(PRE_KEY, preKeyId.toString())?.let(::PreKeyRecord)
            ?: throw InvalidKeyIdException("No prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
        records.put(PRE_KEY, preKeyId.toString(), record.serialize())

    override fun containsPreKey(preKeyId: Int): Boolean = records.get(PRE_KEY, preKeyId.toString()) != null

    override fun removePreKey(preKeyId: Int) = records.remove(PRE_KEY, preKeyId.toString())

    // SignedPreKeyStore

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        records.get(SIGNED_PRE_KEY, signedPreKeyId.toString())?.let(::SignedPreKeyRecord)
            ?: throw InvalidKeyIdException("No signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        records.list(SIGNED_PRE_KEY).values.map(::SignedPreKeyRecord)

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        records.put(SIGNED_PRE_KEY, signedPreKeyId.toString(), record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        records.get(SIGNED_PRE_KEY, signedPreKeyId.toString()) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = records.remove(SIGNED_PRE_KEY, signedPreKeyId.toString())

    // KyberPreKeyStore: one-time keys are deleted when used; the last-resort key stays, never with a reused base key.

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = (
        records.get(KYBER_ONE_TIME, kyberPreKeyId.toString())
            ?: records.get(KYBER_LAST_RESORT, kyberPreKeyId.toString())
        )
        ?.let(::KyberPreKeyRecord) ?: throw InvalidKeyIdException("No Kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        (records.list(KYBER_ONE_TIME).values + records.list(KYBER_LAST_RESORT).values).map(::KyberPreKeyRecord)

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        records.put(KYBER_ONE_TIME, kyberPreKeyId.toString(), record.serialize())

    fun storeLastResortKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        records.put(KYBER_LAST_RESORT, kyberPreKeyId.toString(), record.serialize())

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        records.get(KYBER_ONE_TIME, kyberPreKeyId.toString()) != null ||
            records.get(KYBER_LAST_RESORT, kyberPreKeyId.toString()) != null

    @Synchronized
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        if (records.get(KYBER_ONE_TIME, kyberPreKeyId.toString()) != null) {
            records.remove(KYBER_ONE_TIME, kyberPreKeyId.toString())
            return
        }
        val use = "$kyberPreKeyId:$signedPreKeyId:${b64.encodeToString(baseKey.serialize())}"
        if (records.get(KYBER_LAST_RESORT_USE, use) != null) throw ReusedBaseKeyException("Reused base key")
        records.put(KYBER_LAST_RESORT_USE, use, byteArrayOf(1))
    }

    // SessionStore

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        records.get(SESSION, sessionName(address))?.let(::SessionRecord) ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> = addresses.map {
        records.get(SESSION, sessionName(it))?.let(::SessionRecord)
            ?: throw NoSessionException("No session for $it")
    }

    override fun getSubDeviceSessions(name: String): List<Int> = records
        .list(SESSION)
        .keys
        .map { it.split(SEPARATOR) }
        .filter { it[0] == name }
        .map { it[1].toInt() }
        .filter { it != PRIMARY_DEVICE }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        records.put(SESSION, sessionName(address), record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        records.get(SESSION, sessionName(address)) != null

    override fun deleteSession(address: SignalProtocolAddress) = records.remove(SESSION, sessionName(address))

    override fun deleteAllSessions(name: String) {
        for (device in getSubDeviceSessions(name) + PRIMARY_DEVICE) records.remove(SESSION, "$name$SEPARATOR$device")
    }

    private fun sessionName(address: SignalProtocolAddress) = "${address.name}$SEPARATOR${address.deviceId}"

    // SenderKeyStore (groups, later)

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        records.put(SENDER_KEY, "${sessionName(sender)}$SEPARATOR$distributionId", record.serialize())

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        records.get(SENDER_KEY, "${sessionName(sender)}$SEPARATOR$distributionId")?.let(::SenderKeyRecord)

    private companion object {
        const val PEER_IDENTITY = "peer-identity"
        const val PRE_KEY = "prekey"
        const val SIGNED_PRE_KEY = "signed-prekey"
        const val KYBER_ONE_TIME = "kyber-prekey"
        const val KYBER_LAST_RESORT = "kyber-last-resort"
        const val KYBER_LAST_RESORT_USE = "kyber-last-resort-use"
        const val SESSION = "session"
        const val SENDER_KEY = "sender-key"
        const val SEPARATOR = "\n"
        const val PRIMARY_DEVICE = 1
    }
}
