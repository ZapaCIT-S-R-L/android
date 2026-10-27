package ro.zapakid.calls.crypto

import java.security.SecureRandom

/**
 * Per-call key rotation, the same rules as iOS. The key leader is the device with the lowest [CallDevice.id]
 * (`<accountId>.<deviceId>`) in the roster. Whenever the set of devices changes (join or leave) the leader
 * makes a fresh random key for the next
 * epoch and seals it to every other device, so a device that left can't read what follows and one that joined
 * can't read what came before. Other devices accept a key only from the current leader, only for a newer epoch,
 * and only when the sealed payload names this call and that epoch.
 */
class CallKeyManager(
    private val callId: String,
    private val self: CallDevice,
    private val sealer: CallKeySealer,
    private val ring: KeyRing,
    private val random: SecureRandom = SecureRandom()
) {
    private var roster: List<CallDevice> = emptyList()
    private var keyedFor: Set<CallDevice> = emptySet()
    private var epoch = -1

    /** Current epoch, -1 before the first key. */
    val currentEpoch: Int get() = epoch

    /** Call on every roster update; returns the envelopes to send (empty unless this device is the leader). */
    suspend fun onRoster(devices: List<CallDevice>, serverEpoch: Int? = null): List<KeyEnvelope> {
        roster = (devices + self).distinct().sortedBy { it.id }
        val members = roster.toSet()
        return if (leader() == self && members != keyedFor) rotate(members, serverEpoch) else emptyList()
    }

    /** The calls service only relays keys for its current epoch; without one, the next epoch after ours. */
    private suspend fun rotate(members: Set<CallDevice>, serverEpoch: Int?): List<KeyEnvelope> {
        val key = CallKey.random(serverEpoch?.takeIf { it > epoch } ?: (epoch + 1), random)
        val payload = SealedKeyPayload.encode(callId, key)
        val envelopes =
            try {
                roster.filter { it != self }.map { KeyEnvelope(key.epoch, self, it, sealer.seal(payload, it)) }
            } finally {
                payload.fill(0)
            }
        epoch = key.epoch
        keyedFor = members
        ring.install(key)
        return envelopes
    }

    /** Opens a key from the leader; returns whether it was installed. */
    suspend fun onEnvelope(envelope: KeyEnvelope): Boolean {
        val fromLeader = envelope.fromDevice == leader() && envelope.fromDevice != self
        val key = if (envelope.toDevice == self && fromLeader && envelope.epoch > epoch) open(envelope) else null
        if (key != null) {
            epoch = key.epoch
            // A later leader change must rotate again: it rekeys for its own view of the roster.
            keyedFor = emptySet()
            ring.install(key)
        }
        return key != null
    }

    private suspend fun open(envelope: KeyEnvelope): CallKey? = try {
        val payload = sealer.open(envelope.blob, envelope.fromDevice)
        try {
            SealedKeyPayload.decode(payload, callId, envelope.epoch)
        } finally {
            payload.fill(0)
        }
    } catch (e: CallKeySealerException) {
        lastRejection = e.message
        null
    }

    private fun leader(): CallDevice? = roster.minByOrNull { it.id }

    /** Why the last envelope was rejected (no key material), for diagnostics. */
    var lastRejection: String? = null
        private set

    /** The call ended: wipe every key. */
    fun close() {
        ring.clear()
        keyedFor = emptySet()
        roster = emptyList()
    }
}
