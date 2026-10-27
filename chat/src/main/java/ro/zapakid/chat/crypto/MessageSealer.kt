package ro.zapakid.chat.crypto

import ro.zapakid.chat.ChatDevice

class MessageSealerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * End-to-end sealing per recipient device: the chat service relays only these opaque blobs. The libsignal
 * implementation is wired in by the app on top of the key-setup PRs; tests use a fake.
 */
interface MessageSealer {
    suspend fun seal(plaintext: ByteArray, toDevice: ChatDevice): ByteArray

    /** Throws [MessageSealerException] when the blob isn't for this device from [fromDevice]. */
    suspend fun open(blob: ByteArray, fromDevice: ChatDevice): ByteArray
}
