package ro.zapakid.app.keys

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.zapakid.app.auth.BlobCipher

/**
 * Small key-value store for libsignal's records: one file per record under [dir]/<kind>/, the file name a SHA-256 of
 * the record name (no addresses on disk), the content encrypted with [cipher] (the Android Keystore key in the app).
 * The app puts [dir] in noBackupFilesDir; nothing here is ever backed up. A file that doesn't decrypt is ignored.
 */
class EncryptedRecordStore(private val dir: File, private val cipher: BlobCipher) {
    @Serializable
    private class Entry(val name: String, val value: ByteArray)

    private val json = Json

    @Synchronized
    fun get(kind: String, name: String): ByteArray? = read(file(kind, name))?.takeIf { it.name == name }?.value

    @Synchronized
    fun put(kind: String, name: String, value: ByteArray) {
        val target = file(kind, name)
        target.parentFile?.mkdirs()
        val sealed = cipher.encrypt(json.encodeToString(Entry.serializer(), Entry(name, value)).encodeToByteArray())
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not store a $kind record")
        }
    }

    @Synchronized
    fun remove(kind: String, name: String) {
        file(kind, name).delete()
    }

    /** Every readable record of [kind], by name. */
    @Synchronized
    fun list(kind: String): Map<String, ByteArray> =
        (File(dir, kind).listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: emptyArray())
            .mapNotNull(::read)
            .associate { it.name to it.value }

    private fun file(kind: String, name: String): File {
        require(kind.matches(KIND)) { "bad record kind" }
        val digest = MessageDigest.getInstance("SHA-256").digest(name.encodeToByteArray())
        return File(File(dir, kind), digest.joinToString("") { "%02x".format(it) })
    }

    @Suppress("SwallowedException") // unreadable = not there; the exception carries no record data
    private fun read(file: File): Entry? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(Entry.serializer(), cipher.decrypt(file.readBytes()).decodeToString())
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        val KIND = Regex("^[a-z-]+$")
    }
}
