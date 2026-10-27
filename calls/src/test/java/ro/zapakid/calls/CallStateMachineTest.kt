package ro.zapakid.calls

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.zapakid.calls.CallEvent.Accept
import ro.zapakid.calls.CallEvent.Decline
import ro.zapakid.calls.CallEvent.Dial
import ro.zapakid.calls.CallEvent.Failed
import ro.zapakid.calls.CallEvent.Hangup
import ro.zapakid.calls.CallEvent.IncomingRing
import ro.zapakid.calls.CallEvent.MediaConnected
import ro.zapakid.calls.CallEvent.MediaLost
import ro.zapakid.calls.CallEvent.OthersLeft
import ro.zapakid.calls.CallEvent.ReconnectTimeout
import ro.zapakid.calls.CallEvent.RemoteDeclined
import ro.zapakid.calls.CallEvent.RemoteEnded
import ro.zapakid.calls.CallEvent.RemoteJoined
import ro.zapakid.calls.CallEvent.RingTimeout
import ro.zapakid.calls.CallState.Calling
import ro.zapakid.calls.CallState.Connecting
import ro.zapakid.calls.CallState.Ended
import ro.zapakid.calls.CallState.Idle
import ro.zapakid.calls.CallState.InCall
import ro.zapakid.calls.CallState.Reconnecting
import ro.zapakid.calls.CallState.Ringing

/** The call phases the screens show: calling, ringing, connecting, in call, reconnecting, ended. */
class CallStateMachineTest {
    private fun run(vararg events: CallEvent, from: CallState = Idle): CallState =
        events.fold(from) { state, event -> CallStateMachine.reduce(state, event) }

    @Test
    fun outgoingCallGoesFromCallingToConnectingToInCall() {
        assertEquals(Calling, run(Dial))
        assertEquals(Connecting, run(Dial, RemoteJoined))
        assertEquals(InCall, run(Dial, RemoteJoined, MediaConnected))
    }

    @Test
    fun incomingCallRingsThenConnectsWhenAccepted() {
        assertEquals(Ringing, run(IncomingRing))
        assertEquals(Connecting, run(IncomingRing, Accept))
        assertEquals(InCall, run(IncomingRing, Accept, MediaConnected))
    }

    @Test
    fun nobodyAnsweringWithinTheRingTimeEndsAsNoAnswerOrMissed() {
        assertEquals(Ended(EndReason.NoAnswer), run(Dial, RingTimeout))
        assertEquals(Ended(EndReason.Missed), run(IncomingRing, RingTimeout))
    }

    @Test
    fun decliningEndsTheCall() {
        assertEquals(Ended(EndReason.Declined), run(IncomingRing, Decline))
        assertEquals(Ended(EndReason.Declined), run(Dial, RemoteDeclined))
    }

    @Test
    fun aLostConnectionReconnectsAndGivesUpAfterTheTimeout() {
        assertEquals(Reconnecting, run(MediaLost, from = InCall))
        assertEquals(InCall, run(MediaLost, MediaConnected, from = InCall))
        assertEquals(Ended(EndReason.ConnectionLost), run(MediaLost, ReconnectTimeout, from = InCall))
    }

    @Test
    fun hangingUpEndsTheCallFromEveryActivePhase() {
        for (state in listOf(Calling, Ringing, Connecting, InCall, Reconnecting)) {
            assertEquals(state.toString(), Ended(EndReason.HungUp), run(Hangup, from = state))
        }
    }

    @Test
    fun theCallEndsWhenEveryoneElseLeftOrTheServerEndsIt() {
        assertEquals(Ended(EndReason.EveryoneLeft), run(OthersLeft, from = InCall))
        assertEquals(Ended(EndReason.EveryoneLeft), run(RemoteEnded, from = InCall))
        assertEquals("the caller hung up first", Ended(EndReason.Missed), run(RemoteEnded, from = Ringing))
        assertEquals(Ended(EndReason.Failed), run(Failed, from = Connecting))
    }

    @Test
    fun eventsThatDoNotFitThePhaseAreIgnored() {
        assertEquals(Idle, run(Accept))
        assertEquals(Idle, run(MediaConnected))
        assertEquals(InCall, run(Dial, from = InCall))
        assertEquals(Calling, run(Accept, from = Calling))
        val ended = Ended(EndReason.HungUp)
        assertEquals(ended, run(MediaConnected, IncomingRing, Hangup, from = ended))
    }

    @Test
    fun aNewCallCanStartAfterTheLastOneEnded() {
        assertEquals(Calling, run(CallEvent.Reset, Dial, from = Ended(EndReason.HungUp)))
    }
}
