package ro.zapakid.chat.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import ro.zapakid.chat.Conversation
import ro.zapakid.chat.Message
import ro.zapakid.chat.MessageStatus

/** Conversations and decrypted messages on this phone. On a device: [SqlCipherChatStore] (encrypted database). */
interface ChatStore {
    fun conversations(): Flow<List<Conversation>>

    fun messages(conversationId: String): Flow<List<Message>>

    suspend fun upsertConversation(conversation: Conversation)

    /** Adds a message (ignored when its ID is already stored: duplicates from the server are harmless). */
    suspend fun add(message: Message): Boolean

    suspend fun setStatus(messageId: String, status: MessageStatus)

    suspend fun setLocalFile(messageId: String, path: String)

    suspend fun markRead(conversationId: String)

    suspend fun conversation(id: String): Conversation?
}

/** In memory: tests and previews. */
class InMemoryChatStore : ChatStore {
    private val conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    private val messages = MutableStateFlow<List<Message>>(emptyList())

    override fun conversations(): Flow<List<Conversation>> = conversations.map { all ->
        all.values.sortedByDescending { it.lastAt }
    }

    override fun messages(conversationId: String): Flow<List<Message>> =
        messages.map { all -> all.filter { it.conversationId == conversationId }.sortedBy { it.sentAt } }

    override suspend fun upsertConversation(conversation: Conversation) = conversations.update {
        it +
            (conversation.id to conversation)
    }

    override suspend fun add(message: Message): Boolean {
        if (messages.value.any { it.id == message.id }) return false
        messages.update { it + message }
        conversations.update { all ->
            val c = all[message.conversationId] ?: return@update all
            all +
                (
                    c.id to
                        c.copy(
                            lastMessage = preview(message),
                            lastAt = message.sentAt,
                            unread =
                                c.unread + if (message.mine) 0 else 1
                        )
                    )
        }
        return true
    }

    override suspend fun setStatus(messageId: String, status: MessageStatus) =
        messages.update { all -> all.map { if (it.id == messageId) it.copy(status = status) else it } }

    override suspend fun setLocalFile(messageId: String, path: String) =
        messages.update { all -> all.map { if (it.id == messageId) it.copy(localFile = path) else it } }

    override suspend fun markRead(conversationId: String) = conversations.update { all ->
        all[conversationId]?.let { all + (conversationId to it.copy(unread = 0)) } ?: all
    }

    override suspend fun conversation(id: String): Conversation? = conversations.value[id]
}

/** The line under a conversation's name: the text, or an icon word for media. */
fun preview(message: Message): String = when (message.kind) {
    ro.zapakid.chat.MessageKind.Text -> message.text.orEmpty()
    ro.zapakid.chat.MessageKind.Image -> "🖼️"
    ro.zapakid.chat.MessageKind.Voice -> "🎤"
}
