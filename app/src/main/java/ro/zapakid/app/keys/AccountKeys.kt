package ro.zapakid.app.keys

import java.io.File
import java.security.MessageDigest
import ro.zapakid.app.auth.BlobCipher

/**
 * Key material per ZapaKid account (security review H1): each account's libsignal records live in their own
 * directory under [root] (named by a SHA-256 of the account ID, so the ID isn't on disk), and its identity in its own
 * Block Store item. Another account's identity, prekeys or device ID are never read.
 */
class AccountKeys(private val root: File, private val cipher: BlobCipher, private val blockStore: BlockStoreApi) {
    fun records(accountId: String) = EncryptedRecordStore(File(root, dirName(accountId)), cipher)

    fun backup(accountId: String): IdentityBackup = BlockStoreIdentityBackup(blockStore, accountId)

    /** Deletes every key record of an account on this phone (a revoked kid phone). */
    fun wipe(accountId: String) {
        File(root, dirName(accountId)).deleteRecursively()
    }

    private fun dirName(accountId: String): String {
        require(accountId.isNotBlank()) { "no account" }
        return MessageDigest.getInstance("SHA-256").digest(accountId.encodeToByteArray()).joinToString("") {
            "%02x".format(it)
        }
    }
}
