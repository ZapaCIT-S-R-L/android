package ro.zapakid.calls.ring

/**
 * A call push (high-priority FCM data message). It carries IDs only: the name shown on the ringing screen comes
 * from the conversation stored on the phone, never from the push.
 */
sealed interface CallPush {
    data class Incoming(val callId: String, val conversationId: String) : CallPush

    data class Cancelled(val callId: String) : CallPush

    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun parse(data: Map<String, String>): CallPush? {
            val callId = data["callId"]?.takeIf(ID::matches) ?: return null
            return when (data["type"]) {
                "call" -> data["conversationId"]?.takeIf(ID::matches)?.let { Incoming(callId, it) }
                "call_cancel" -> Cancelled(callId)
                else -> null
            }
        }
    }
}

/** Who a conversation is, from the phone's own data: a name and a robot avatar. */
data class ConversationTitle(val name: String, val avatar: Int, val group: Boolean)

/** The phone's call system: Telecom (ConnectionService) on Android; a fake in tests. */
interface CallSystem {
    fun reportIncoming(callId: String, title: ConversationTitle)

    fun reportOutgoing(callId: String, title: ConversationTitle)

    fun reportEnded(callId: String)
}

/**
 * Where push tokens come from. FCM is wired in once Stefan has created the Firebase project (android #25); until
 * then the app runs with [NoPush] and calls ring only while the app is open.
 */
interface PushTokens {
    suspend fun token(): String?

    object NoPush : PushTokens {
        override suspend fun token(): String? = null
    }
}

/** Turns call pushes into a ringing phone. [ring] returns false when the phone is already in a call. */
class IncomingCalls(
    private val system: CallSystem,
    private val titles: (conversationId: String) -> ConversationTitle?,
    private val ring: (callId: String, conversationId: String, title: ConversationTitle) -> Boolean,
    private val cancel: (callId: String) -> Unit
) {
    fun onPush(data: Map<String, String>) {
        when (val push = CallPush.parse(data)) {
            is CallPush.Incoming -> {
                val title = titles(push.conversationId) ?: return
                if (ring(push.callId, push.conversationId, title)) system.reportIncoming(push.callId, title)
            }

            is CallPush.Cancelled -> {
                cancel(push.callId)
                system.reportEnded(push.callId)
            }

            null -> Unit
        }
    }
}
