package ro.zapakid.calls.ring

import android.os.Handler
import android.os.Looper

/** What Telecom and the ringing notification ask of the app's call controller. */
interface CallRingerHandler {
    fun accept(callId: String)

    fun decline(callId: String)

    fun hangup(callId: String)

    /** The call rang but couldn't be shown (notifications or the full-screen intent are off): log it as missed. */
    fun missed(callId: String) = Unit
}

/**
 * Hands Answer, Decline, Hang up and Missed to the app's call controller ([handler]). A push can wake a fresh process
 * before the app wires the controller, so actions wait until it is set. An answer that no controller takes within
 * [WAIT_MILLIS] ends the call with an error instead of telling Telecom it is live, and Telecom hears "active" only
 * once the controller has the answer.
 */
object CallRinger {
    enum class Action { ACCEPT, DECLINE, HANGUP, MISSED }

    const val WAIT_MILLIS = 10_000L

    private val pending = LinkedHashMap<String, Action>()

    @Volatile
    var handler: CallRingerHandler? = null
        set(value) {
            field = value
            if (value != null) flush(value)
        }

    // Seams: the real ones touch Android's main looper and Telecom; tests replace them.
    internal var scheduler: (Long, () -> Unit) -> Unit = { millis, block ->
        Handler(Looper.getMainLooper()).postDelayed(block, millis)
    }
    internal var onAccepted: (String) -> Unit = { ZapaConnectionService.active(it) }
    internal var onExpired: (String) -> Unit = { ZapaConnectionService.fail(it) }

    /** Stops the ringing UI (notification, full-screen activity); set by the app with its context. */
    var stopRinging: (String) -> Unit = {}

    fun send(callId: String, action: Action) {
        if (action != Action.HANGUP) stopRinging(callId)
        val now = synchronized(this) {
            handler ?: run {
                pending[callId] = action
                if (action == Action.ACCEPT) scheduler(WAIT_MILLIS) { expire(callId) }
                null
            }
        }
        now?.let { deliver(it, callId, action) }
    }

    private fun expire(callId: String) {
        val dropped = synchronized(this) { pending[callId] == Action.ACCEPT && pending.remove(callId) != null }
        if (dropped) onExpired(callId)
    }

    private fun flush(to: CallRingerHandler) {
        val waiting = synchronized(this) { pending.toList().also { pending.clear() } }
        waiting.forEach { (callId, action) -> deliver(to, callId, action) }
    }

    private fun deliver(to: CallRingerHandler, callId: String, action: Action) = when (action) {
        Action.ACCEPT -> {
            to.accept(callId)
            onAccepted(callId)
        }

        Action.DECLINE -> to.decline(callId)
        Action.HANGUP -> to.hangup(callId)
        Action.MISSED -> to.missed(callId)
    }

    /** Tests only: back to a fresh process. */
    internal fun reset() {
        synchronized(this) { pending.clear() }
        handler = null
        scheduler = { millis, block -> Handler(Looper.getMainLooper()).postDelayed(block, millis) }
        onAccepted = { ZapaConnectionService.active(it) }
        onExpired = { ZapaConnectionService.fail(it) }
        stopRinging = {}
    }
}

/** How an incoming call reaches the user: Telecom when allowed, our notification otherwise, missed when neither can. */
internal object IncomingRouting {
    fun report(
        canRing: Boolean,
        telecomAllowed: Boolean,
        register: () -> Unit,
        addToTelecom: () -> Unit,
        notify: () -> Unit,
        missed: () -> Unit
    ) {
        when {
            !canRing -> missed()
            !telecomAllowed -> notify()
            else ->
                try {
                    register()
                    addToTelecom()
                } catch (_: SecurityException) {
                    notify() // the phone account isn't registered or usable in this process
                }
        }
    }
}
