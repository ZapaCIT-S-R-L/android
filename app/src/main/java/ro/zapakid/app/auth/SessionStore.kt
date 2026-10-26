package ro.zapakid.app.auth

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.serialization.json.Json

/** Where the parent session lives between app starts. */
interface SessionStore {
    fun load(): Session?

    fun save(session: Session)

    fun clear()
}

/** Encrypts/decrypts the stored session. On the device: [KeystoreSessionCipher]. */
interface SessionCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * The session as one encrypted file, in noBackupFilesDir on the device (never in a backup).
 * A file that doesn't decrypt (tampered, or the key is gone) is deleted and treated as signed out.
 */
class EncryptedSessionStore(private val file: File, private val cipher: SessionCipher) : SessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun load(): Session? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(Session.serializer(), cipher.decrypt(file.readBytes()).decodeToString())
        } catch (e: GeneralSecurityException) {
            discard(e)
        } catch (e: IllegalArgumentException) {
            // Not valid JSON after decryption (SerializationException is an IllegalArgumentException).
            discard(e)
        } catch (e: IOException) {
            discard(e)
        }
    }

    /** Untrusted or unreadable: delete it and start signed out. The exception carries no session data. */
    private fun discard(@Suppress("UNUSED_PARAMETER") cause: Exception): Session? {
        file.delete()
        return null
    }

    @Synchronized
    override fun save(session: Session) {
        val sealed = cipher.encrypt(json.encodeToString(Session.serializer(), session).encodeToByteArray())
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not store the session")
        }
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }
}
