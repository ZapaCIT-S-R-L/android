package ro.zapakid.calls.crypto

import java.nio.ByteBuffer

/**
 * What gets sealed: version, epoch, the 32-byte key and the call ID. Binding call and epoch inside the sealed
 * bytes stops the server from replaying a key in another call or relabelling an old epoch as a new one.
 */
object SealedKeyPayload {
    private const val VERSION: Byte = 1
    private const val HEADER = 1 + Int.SIZE_BYTES

    fun encode(callId: String, key: CallKey): ByteArray {
        val id = callId.encodeToByteArray()
        return ByteBuffer
            .allocate(HEADER + CallKey.SIZE + id.size)
            .put(VERSION)
            .putInt(key.epoch)
            .put(key.bytes())
            .put(id)
            .array()
    }

    /** Throws [CallKeySealerException] unless the payload is for [callId] and [epoch]. */
    fun decode(payload: ByteArray, callId: String, epoch: Int): CallKey {
        if (payload.size < HEADER + CallKey.SIZE || payload[0] != VERSION) rejected("unknown key payload")
        val buffer = ByteBuffer.wrap(payload)
        buffer.get()
        if (buffer.getInt() != epoch) rejected("epoch mismatch")
        val key = ByteArray(CallKey.SIZE).also { buffer.get(it) }
        val id = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (!id.contentEquals(callId.encodeToByteArray())) rejected("call mismatch")
        return CallKey.of(epoch, key).also { key.fill(0) }
    }

    private fun rejected(message: String): Nothing = throw CallKeySealerException(message)
}
