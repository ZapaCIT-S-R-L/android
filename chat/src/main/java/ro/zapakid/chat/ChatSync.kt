package ro.zapakid.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.zapakid.chat.net.ChatApi
import ro.zapakid.chat.net.ChatSignal
import ro.zapakid.chat.net.ChatSocket

/**
 * Keeps one live stream per conversation open while the app is in front (the chat service runs one per
 * conversation): catches up on every connect, stores then acknowledges envelopes as they arrive, refreshes devices
 * on membership events, and reconnects with backoff (1 s doubling to 30 s).
 */
class ChatSync(private val repository: ChatRepository, private val socket: ChatSocket, private val api: ChatApi) {
    private val mutableOnline = MutableStateFlow(false)
    val online: StateFlow<Boolean> = mutableOnline

    suspend fun run() = coroutineScope {
        val conversations = runCatching { repository.refreshConversations() }.getOrDefault(emptyList())
        mutableOnline.value = conversations.isNotEmpty()
        conversations.forEach { id -> launch { stream(id) } }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a broken socket just reconnects
    private suspend fun stream(conversationId: String) {
        var backoff = FIRST_BACKOFF
        while (true) {
            try {
                socket.connect(conversationId).collect { signal ->
                    when (signal) {
                        ChatSignal.Connected -> {
                            backoff = FIRST_BACKOFF
                            repository.sync(conversationId)
                        }

                        is ChatSignal.Incoming -> {
                            repository.receive(signal.envelope)
                            api.ack(conversationId, listOf(signal.envelope.id))
                        }

                        is ChatSignal.Event -> repository.refresh(conversationId)

                        is ChatSignal.Disconnected -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableOnline.value = false
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    private companion object {
        const val FIRST_BACKOFF = 1_000L
        const val MAX_BACKOFF = 30_000L
    }
}
