package ro.zapakid.calls.crypto

/**
 * The last [SIZE] call keys, by key index (epoch modulo [SIZE]), the same ring the WebRTC FrameCryptor keeps.
 * Receivers still read frames sent with the previous key while a rotation reaches every phone. The newest key
 * is the one a sender uses.
 */
class KeyRing {
    private val slots = arrayOfNulls<CallKey>(SIZE)

    @Volatile
    var current: CallKey? = null
        private set

    @Synchronized
    fun install(key: CallKey) {
        val slot = indexOf(key.epoch)
        slots[slot]?.takeIf { it !== key }?.wipe()
        slots[slot] = key
        if ((current?.epoch ?: 0) < key.epoch) current = key
    }

    /** The key for an epoch, or null when the ring never had it or it has rotated out. */
    @Synchronized
    fun get(epoch: Int): CallKey? = slots[indexOf(epoch)]?.takeIf { it.epoch == epoch }

    /** The key for a key index from a frame trailer. */
    @Synchronized
    fun byIndex(index: Int): CallKey? = slots.getOrNull(index)

    @Synchronized
    fun clear() {
        slots.forEachIndexed { i, key ->
            key?.wipe()
            slots[i] = null
        }
        current = null
    }

    companion object {
        const val SIZE = 16

        fun indexOf(epoch: Int): Int = Math.floorMod(epoch, SIZE)
    }
}
