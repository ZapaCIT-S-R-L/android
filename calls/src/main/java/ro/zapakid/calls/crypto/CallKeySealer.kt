package ro.zapakid.calls.crypto

/** One phone in a call: the account (token `sub`) and its device (token `dev`). */
data class CallDevice(val account: String, val device: String)

class CallKeySealerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seals a call key for one recipient device, so the calls service relays only opaque bytes. The app wires in
 * the libsignal implementation (a Signal session per device); tests use a fake. [open] throws
 * [CallKeySealerException] when the blob wasn't sealed for this device by [fromDevice].
 */
interface CallKeySealer {
    suspend fun seal(payload: ByteArray, toDevice: CallDevice): ByteArray

    suspend fun open(blob: ByteArray, fromDevice: CallDevice): ByteArray
}

/** A sealed call key on its way through the calls service (`{epoch, toDevice, blob}` plus the sender). */
data class KeyEnvelope(val epoch: Int, val fromDevice: CallDevice, val toDevice: CallDevice, val blob: ByteArray) {
    override fun equals(other: Any?): Boolean = other is KeyEnvelope &&
        epoch == other.epoch &&
        fromDevice == other.fromDevice &&
        toDevice == other.toDevice &&
        blob.contentEquals(other.blob)

    override fun hashCode(): Int =
        ((epoch * 31 + fromDevice.hashCode()) * 31 + toDevice.hashCode()) * 31 + blob.contentHashCode()

    override fun toString(): String = "KeyEnvelope(epoch=$epoch, from=$fromDevice, to=$toDevice, ${blob.size} bytes)"
}
