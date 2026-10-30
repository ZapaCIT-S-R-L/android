package ro.zapakid.chat.crypto

import ro.zapakid.chat.ChatDevice

class MessageSealerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The libsignal ciphertext kinds the chat service relays (there is no plaintext kind). */
enum class CiphertextType(val wire: String) {
    PreKey("prekey_message"),
    Message("message"),
    SenderKey("sender_key_message");

    companion object {
        fun of(wire: String): CiphertextType? = entries.firstOrNull { it.wire == wire }
    }
}

/** One device's copy: the libsignal message type and its bytes. */
class Sealed(val type: CiphertextType, val content: ByteArray)

/**
 * End-to-end sealing per recipient device (a libsignal session each); the chat service relays only these opaque
 * blobs. The app wires in the libsignal implementation; tests use a fake.
 */
interface MessageSealer {
    suspend fun seal(plaintext: ByteArray, toDevice: ChatDevice): Sealed

    /** Throws [MessageSealerException] when the blob isn't for this device from [fromDevice]. */
    suspend fun open(type: CiphertextType, content: ByteArray, fromDevice: ChatDevice): ByteArray
}
