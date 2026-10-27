package ro.zapakid.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ro.zapakid.chat.net.ChatSignal
import ro.zapakid.chat.net.ChatSocket

/**
 * Keeps the live socket open while the app is in front: catches up with [ChatRepository.sync] on every connect,
 * then stores and acknowledges messages as they arrive. Reconnects with backoff (1 s doubling to 30 s).
 */
class ChatSync(
    private val repository: ChatRepository,
    private val socket: ChatSocket,
    private val ack: suspend (List<String>) -> Unit
) {
    private val mutableOnline = MutableStateFlow(false)
    val online: StateFlow<Boolean> = mutableOnline

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a broken socket just reconnects
    suspend fun run() {
        var backoff = FIRST_BACKOFF
        while (true) {
            try {
                socket.connect().collect { signal ->
                    when (signal) {
                        ChatSignal.Connected -> {
                            mutableOnline.value = true
                            backoff = FIRST_BACKOFF
                            repository.refreshConversations()
                            repository.sync()
                        }

                        is ChatSignal.Incoming -> {
                            repository.receive(signal.message)
                            ack(listOf(signal.message.id))
                        }

                        is ChatSignal.Disconnected -> mutableOnline.value = false
                    }
                }
            } catch (e: CancellationException) {
                mutableOnline.value = false
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
