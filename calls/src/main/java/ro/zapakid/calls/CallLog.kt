package ro.zapakid.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class CallDirection { Outgoing, Incoming }

/** One line in the call log: metadata only, never audio. */
data class CallLogEntry(
    val callId: String,
    val conversationId: String?,
    val title: String,
    val direction: CallDirection,
    val reason: EndReason,
    val startedAtMillis: Long,
    val durationSeconds: Long,
    val group: Boolean
) {
    val missed: Boolean get() = direction == CallDirection.Incoming && reason == EndReason.Missed
}

interface CallLog {
    val entries: StateFlow<List<CallLogEntry>>

    fun add(entry: CallLogEntry)
}

/** Newest first, capped. The app swaps in a persistent store with the chat database. */
class InMemoryCallLog(private val limit: Int = 200) : CallLog {
    private val state = MutableStateFlow<List<CallLogEntry>>(emptyList())
    override val entries: StateFlow<List<CallLogEntry>> = state

    override fun add(entry: CallLogEntry) = state.update { (listOf(entry) + it).take(limit) }

    /** Drops the log: an account switch must not show the next account the previous one's calls. */
    fun clear() {
        state.value = emptyList()
    }
}
