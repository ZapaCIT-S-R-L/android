package ro.zapakid.calls

/** Why a call ended; the screens show a friendly line for each. */
enum class EndReason { HungUp, Declined, NoAnswer, Missed, EveryoneLeft, ConnectionLost, Failed }

/** The phases the call screens show. */
sealed interface CallState {
    data object Idle : CallState

    /** Outgoing, the others' phones are ringing. */
    data object Calling : CallState

    /** Incoming, this phone rings. */
    data object Ringing : CallState

    data object Connecting : CallState

    data object InCall : CallState

    data object Reconnecting : CallState

    data class Ended(val reason: EndReason) : CallState
}

sealed interface CallEvent {
    data object Dial : CallEvent

    data object IncomingRing : CallEvent

    data object Accept : CallEvent

    data object Decline : CallEvent

    data object RemoteJoined : CallEvent

    data object RemoteDeclined : CallEvent

    data object MediaConnected : CallEvent

    data object MediaLost : CallEvent

    data object ReconnectTimeout : CallEvent

    /** 45 s without an answer (the calls service sends it). */
    data object RingTimeout : CallEvent

    data object Hangup : CallEvent

    data object OthersLeft : CallEvent

    /** The service ended the call (the caller gave up, or the call reached its maximum length). */
    data object RemoteEnded : CallEvent

    data object Failed : CallEvent

    /** Back to idle after the ended screen. */
    data object Reset : CallEvent
}

/** Pure transition function; events that don't fit the current phase leave it unchanged. */
object CallStateMachine {
    fun reduce(state: CallState, event: CallEvent): CallState = when (state) {
        CallState.Idle -> fromIdle(event)
        CallState.Calling -> fromCalling(event)
        CallState.Ringing -> fromRinging(event)
        CallState.Connecting, CallState.InCall, CallState.Reconnecting -> fromLive(state, event)
        is CallState.Ended -> if (event == CallEvent.Reset) CallState.Idle else state
    } ?: state

    private fun fromIdle(event: CallEvent): CallState? = when (event) {
        CallEvent.Dial -> CallState.Calling
        CallEvent.IncomingRing -> CallState.Ringing
        else -> null
    }

    private fun fromCalling(event: CallEvent): CallState? = when (event) {
        CallEvent.RemoteJoined -> CallState.Connecting
        CallEvent.RemoteDeclined -> CallState.Ended(EndReason.Declined)
        CallEvent.RingTimeout -> CallState.Ended(EndReason.NoAnswer)
        else -> ending(event)
    }

    private fun fromRinging(event: CallEvent): CallState? = when (event) {
        CallEvent.Accept -> CallState.Connecting
        CallEvent.Decline -> CallState.Ended(EndReason.Declined)
        CallEvent.RingTimeout, CallEvent.RemoteEnded -> CallState.Ended(EndReason.Missed)
        else -> ending(event)
    }

    private fun fromLive(state: CallState, event: CallEvent): CallState? = when (event) {
        CallEvent.MediaConnected -> CallState.InCall

        CallEvent.MediaLost -> if (state == CallState.Connecting) null else CallState.Reconnecting

        CallEvent.ReconnectTimeout -> if (state ==
            CallState.Reconnecting
        ) {
            CallState.Ended(EndReason.ConnectionLost)
        } else {
            null
        }

        else -> ending(event)
    }

    private fun ending(event: CallEvent): CallState? = when (event) {
        CallEvent.Hangup -> CallState.Ended(EndReason.HungUp)
        CallEvent.OthersLeft, CallEvent.RemoteEnded -> CallState.Ended(EndReason.EveryoneLeft)
        CallEvent.Failed -> CallState.Ended(EndReason.Failed)
        else -> null
    }
}
