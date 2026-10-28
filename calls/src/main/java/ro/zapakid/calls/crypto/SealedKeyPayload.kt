package ro.zapakid.calls.crypto

import java.nio.ByteBuffer

/**
 * What gets sealed, byte for byte the same as iOS (`CallKeyMaterial`): `"ZKCK" | version 1 | epoch u32 BE | key |
 * callId UTF-8`. Binding call and epoch inside the sealed
 * bytes stops the server from replaying a key in another call or relabelling an old epoch as a new one.
 */
object SealedKeyPayload {
    private val MAGIC = "ZKCK".encodeToByteArray()
    private const val VERSION: Byte = 1
    private val HEADER = MAGIC.size + 1 + Int.SIZE_BYTES

    fun encode(callId: String, key: CallKey): ByteArray {
        val id = callId.encodeToByteArray()
        return ByteBuffer
            .allocate(HEADER + CallKey.SIZE + id.size)
            .put(MAGIC)
            .put(VERSION)
            .putInt(key.epoch)
            .put(key.bytes())
            .put(id)
            .array()
    }

    /** Throws [CallKeySealerException] unless the payload is for [callId] and [epoch]. */
    fun decode(payload: ByteArray, callId: String, epoch: Int): CallKey {
        val known = payload.size > HEADER + CallKey.SIZE &&
            payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) &&
            payload[MAGIC.size] == VERSION
        if (!known) rejected("unknown key payload")
        val buffer = ByteBuffer.wrap(payload, MAGIC.size + 1, payload.size - MAGIC.size - 1)
        if (buffer.getInt() != epoch) rejected("epoch mismatch")
        val key = ByteArray(CallKey.SIZE).also { buffer.get(it) }
        val id = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (!id.contentEquals(callId.encodeToByteArray())) rejected("call mismatch")
        return CallKey.of(epoch, key).also { key.fill(0) }
    }

    private fun rejected(message: String): Nothing = throw CallKeySealerException(message)
}
