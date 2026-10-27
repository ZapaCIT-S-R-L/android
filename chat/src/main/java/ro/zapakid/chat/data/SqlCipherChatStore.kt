package ro.zapakid.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SQLiteDatabase
import ro.zapakid.chat.AttachmentRef
import ro.zapakid.chat.Conversation
import ro.zapakid.chat.Message
import ro.zapakid.chat.MessageKind
import ro.zapakid.chat.MessageStatus

/**
 * The chat database, encrypted with SQLCipher (AES-256). [passphrase] is 32 random bytes kept wrapped by the
 * Android Keystore (see [DatabaseKey]); it never leaves the phone and never goes into a backup.
 */
@Suppress("TooManyFunctions") // the ChatStore interface plus small cursor helpers
class SqlCipherChatStore(context: Context, passphrase: ByteArray, name: String = "chat.db") : ChatStore {
    private val db: SQLiteDatabase
    private val changes = MutableStateFlow(0L)
    private val json = Json { ignoreUnknownKeys = true }

    // Selection arguments go as String[]: the Object[] overload of rawQuery didn't bind them (no rows came back).
    init {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        db = SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null, null)
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL, avatar INTEGER NOT NULL,
               grp INTEGER NOT NULL, members TEXT NOT NULL, last_message TEXT, last_at INTEGER NOT NULL,
               unread INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, sender TEXT NOT NULL,
               mine INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT, attachment TEXT, sent_at INTEGER NOT NULL,
               status TEXT NOT NULL, local_file TEXT)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_by_conversation ON messages (conversation_id, sent_at)")
    }

    private fun bump() {
        changes.value += 1
    }

    override fun conversations(): Flow<List<Conversation>> = changes.map {
        db.rawQuery("SELECT * FROM conversations ORDER BY last_at DESC", arrayOf<String>()).use { c ->
            c.all(::conversation)
        }
    }.flowOn(Dispatchers.IO)

    override fun messages(conversationId: String): Flow<List<Message>> = changes.map {
        db.rawQuery(
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY sent_at",
            arrayOf<String>(conversationId)
        ).use { c ->
            c.all(::message)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun upsertConversation(conversation: Conversation) = io {
        val values =
            ContentValues().apply {
                put("id", conversation.id)
                put("title", conversation.title)
                put("avatar", conversation.avatar)
                put("grp", if (conversation.group) 1 else 0)
                put("members", conversation.members.joinToString("\n"))
                put("last_message", conversation.lastMessage)
                put("last_at", conversation.lastAt)
                put("unread", conversation.unread)
            }
        db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    override suspend fun add(message: Message): Boolean = io {
        val values =
            ContentValues().apply {
                put("id", message.id)
                put("conversation_id", message.conversationId)
                put("sender", message.sender)
                put("mine", if (message.mine) 1 else 0)
                put("kind", message.kind.name)
                put("text", message.text)
                put("attachment", message.attachment?.let { json.encodeToString(AttachmentRef.serializer(), it) })
                put("sent_at", message.sentAt)
                put("status", message.status.name)
                put("local_file", message.localFile)
            }
        val added = db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (added) {
            db.execSQL(
                "UPDATE conversations SET last_message = ?, last_at = ?, unread = unread + ? WHERE id = ?",
                arrayOf<Any?>(preview(message), message.sentAt, if (message.mine) 0 else 1, message.conversationId)
            )
            bump()
        }
        added
    }

    override suspend fun setStatus(messageId: String, status: MessageStatus) = update("status", status.name, messageId)

    override suspend fun setLocalFile(messageId: String, path: String) = update("local_file", path, messageId)

    private suspend fun update(column: String, value: String, id: String) = io {
        db.execSQL("UPDATE messages SET $column = ? WHERE id = ?", arrayOf<Any?>(value, id))
        bump()
    }

    override suspend fun markRead(conversationId: String) = io {
        db.execSQL("UPDATE conversations SET unread = 0 WHERE id = ?", arrayOf<Any?>(conversationId))
        bump()
    }

    override suspend fun conversation(id: String): Conversation? = io {
        db.rawQuery("SELECT * FROM conversations WHERE id = ?", arrayOf<String>(id)).use { c ->
            c.all(::conversation).firstOrNull()
        }
    }

    fun close() = db.close()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun <T> Cursor.all(row: (Cursor) -> T): List<T> = buildList { while (moveToNext()) add(row(this@all)) }

    private fun Cursor.str(name: String): String? = getColumnIndexOrThrow(name).let {
        if (isNull(it)) null else getString(it)
    }

    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))

    private fun conversation(c: Cursor) = Conversation(
        id = c.str("id")!!,
        title = c.str("title")!!,
        avatar = c.long("avatar").toInt(),
        group = c.long("grp") == 1L,
        members = c.str("members")!!.split("\n").filter { it.isNotEmpty() },
        lastMessage = c.str("last_message"),
        lastAt = c.long("last_at"),
        unread = c.long("unread").toInt()
    )

    private fun message(c: Cursor) = Message(
        id = c.str("id")!!,
        conversationId = c.str("conversation_id")!!,
        sender = c.str("sender")!!,
        mine = c.long("mine") == 1L,
        kind = MessageKind.valueOf(c.str("kind")!!),
        text = c.str("text"),
        attachment = c.str("attachment")?.let { json.decodeFromString(AttachmentRef.serializer(), it) },
        sentAt = c.long("sent_at"),
        status = MessageStatus.valueOf(c.str("status")!!),
        localFile = c.str("local_file")
    )
}
