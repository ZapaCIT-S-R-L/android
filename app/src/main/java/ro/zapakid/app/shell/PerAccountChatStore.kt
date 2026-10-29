package ro.zapakid.app.shell

import java.security.MessageDigest
import ro.zapakid.chat.data.ChatStore

/**
 * One chat store per account. The database file and its key are named after the account, so a second account on the
 * same phone cannot open the first one's conversations, and a switch closes the store it leaves behind instead of
 * keeping two databases open. [onSwitch] runs when the account actually changed, for the state that lives outside
 * the store — the in-memory call log. Closing and reopening the same account (a configuration change) is not a
 * switch, so that state survives it.
 */
class PerAccountChatStore(
    private val openStore: (name: String) -> ChatStore,
    private val closeStore: (ChatStore) -> Unit,
    private val onSwitch: () -> Unit = {}
) {
    private var account: String? = null
    private var store: ChatStore? = null

    // Remembered across a close: signing out and in as someone else is still a change of account.
    private var lastAccount: String? = null

    /** The store of [id], opened the first time this phone uses that account. */
    @Synchronized
    fun forAccount(id: String): ChatStore {
        if (id == account) return checkNotNull(store)
        store?.let(closeStore)
        store = null
        account = null
        if (lastAccount != null && lastAccount != id) onSwitch()
        // Opened before the account is recorded: a store that fails to open leaves nothing behind.
        val opened = openStore(nameOf(id))
        account = id
        lastAccount = id
        store = opened
        return opened
    }

    /** Signs the store out: nothing stays open, and the next account starts from a closed store. */
    @Synchronized
    fun close() {
        val previous = store ?: return
        store = null
        account = null
        closeStore(previous)
    }

    companion object {
        private val FILE_SAFE = Regex("[A-Za-z0-9_-]{1,64}")
        private const val BYTE_MASK = 0xff
        private const val HEX_RADIX = 16
        private const val HEX_WIDTH = 2

        /** A file name for one account: its id when that is already file-safe, a digest of it otherwise. */
        fun nameOf(id: String): String = id.takeIf { FILE_SAFE.matches(it) } ?: digest(id)

        private fun digest(id: String): String {
            val sha = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            return sha.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_WIDTH, '0') }
        }
    }
}
