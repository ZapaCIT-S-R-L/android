package ro.zapakid.calls.crypto

import java.security.SecureRandom

/**
 * One call key: 32 random bytes for one epoch of one call. A new epoch starts on every join and leave. The bytes
 * are copied in and out, wiped by [wipe], and never appear in [toString].
 */
class CallKey private constructor(val epoch: Int, private val material: ByteArray) {
    init {
        require(material.size == SIZE) { "a call key has $SIZE bytes" }
        require(epoch > 0) { "epochs start at 1" }
    }

    fun bytes(): ByteArray = material.copyOf()

    /** Overwrites the key in memory; the ring calls it when an epoch drops out, the call calls it at the end. */
    fun wipe() = material.fill(0)

    override fun toString(): String = "CallKey(epoch=$epoch)"

    companion object {
        const val SIZE = 32

        fun random(epoch: Int, random: SecureRandom): CallKey = CallKey(epoch, ByteArray(SIZE).also(random::nextBytes))

        fun of(epoch: Int, bytes: ByteArray): CallKey = CallKey(epoch, bytes.copyOf())
    }
}
