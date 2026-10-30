package ro.zapakid.chat.data

import java.io.File
import java.security.SecureRandom

/** Wraps bytes with a non-exportable Android Keystore key (the app's session cipher); a fake in tests. */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(wrapped: ByteArray): ByteArray
}

/** The SQLCipher passphrase: 32 random bytes, stored only wrapped, in noBackupFilesDir. */
object DatabaseKey {
    private const val KEY_BYTES = 32

    fun load(file: File, wrapper: KeyWrapper, random: SecureRandom = SecureRandom()): ByteArray {
        if (file.exists()) return wrapper.unwrap(file.readBytes())
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(wrapper.wrap(key))
        check(tmp.renameTo(file)) { "could not store the database key" }
        return key
    }
}
