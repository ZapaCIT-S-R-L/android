package ro.zapakid.calls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.calls.api.CallSignal
import ro.zapakid.calls.crypto.CallKeyManager
import ro.zapakid.calls.crypto.KeyRing
import ro.zapakid.calls.engine.EngineEvent
import ro.zapakid.calls.ring.Ringback
import ro.zapakid.calls.testing.ANA
import ro.zapakid.calls.testing.FakeCallEngine
import ro.zapakid.calls.testing.FakeCallsApi
import ro.zapakid.calls.testing.FakeEvents
import ro.zapakid.calls.testing.FakeRingback
import ro.zapakid.calls.testing.ION
import ro.zapakid.calls.testing.ME
import ro.zapakid.calls.testing.TestSealer

/** The call as the screens see it, with a fake calls service and a fake media engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    private val api = FakeCallsApi()
    private val events = FakeEvents()
    private val engine = FakeCallEngine()
    private val log = InMemoryCallLog()

    private fun TestScope.controller(ringback: Ringback = Ringback.Silent) =
        CallController(api, events, engine, TestSealer(ME), ME, log, backgroundScope, clock = {
            testScheduler.currentTime
        }, ringback = ringback)

    /** Ana's phone as the key leader: seals her key to me. */
    private suspend fun anaSendsKey(vararg roster: ro.zapakid.calls.crypto.CallDevice) {
        val ana = CallKeyManager("call-1", ANA, TestSealer(ANA), KeyRing())
        ana.onRoster(roster.toList(), serverEpoch = events.epoch).filter {
            it.toDevice == ME
        }.forEach { events.push(CallSignal.Key(it)) }
    }

    @Test
    fun anOutgoingCallRingsConnectsAndIsEncrypted() = runTest {
        val calls = controller()

        assertTrue(calls.startCall("conv-1", "Ana"))
        runCurrent()
        assertEquals(CallState.Calling, calls.state.value.phase)
        assertEquals(listOf("create conv-1"), api.calls)
        assertEquals("call-1", events.connectedTo)
        assertTrue(engine.started)

        events.roster(ME)
        runCurrent()
        assertEquals("alone, I make the first key with the server's epoch", listOf(1), engine.rotations)

        events.roster(ME, ANA)
        engine.emit(EngineEvent.Connected)
        runCurrent()
        assertEquals("Ana sorts first, so she leads now", CallState.Connecting, calls.state.value.phase)
        anaSendsKey(ANA, ME)
        runCurrent()

        assertEquals(CallState.InCall, calls.state.value.phase)
        assertTrue(calls.state.value.encrypted)
        assertEquals(listOf(1, 2), engine.rotations)
        assertEquals(listOf(ANA), calls.state.value.people.map { it.device })
        assertEquals(listOf(ANA), engine.participants.map { it.device })
    }

    @Test
    fun anIncomingCallRingsAndConnectsAfterAnswerAndTheKey() = runTest {
        val calls = controller()

        assertTrue(calls.incoming("call-1", "conv-1", "Ana"))
        runCurrent()
        assertEquals(CallState.Ringing, calls.state.value.phase)
        assertFalse("no media before answering", engine.started)

        calls.accept()
        runCurrent()
        assertEquals(CallState.Connecting, calls.state.value.phase)
        assertEquals(listOf("join call-1"), api.calls)

        events.roster(ANA, ME)
        engine.emit(EngineEvent.Connected)
        runCurrent()
        assertEquals("no key yet: not in call", CallState.Connecting, calls.state.value.phase)

        anaSendsKey(ANA, ME)
        runCurrent()
        assertEquals(CallState.InCall, calls.state.value.phase)
        assertEquals(listOf(1), engine.rotations)
    }

    @Test
    fun nobodyAnsweringEndsAfter45Seconds() = runTest {
        val calls = controller()
        calls.startCall("conv-1", "Ana")
        runCurrent()

        advanceTimeBy(44_000)
        assertEquals(CallState.Calling, calls.state.value.phase)
        advanceTimeBy(1_001)

        assertEquals(CallState.Ended(EndReason.NoAnswer), calls.state.value.phase)
        runCurrent()
        assertTrue(engine.stopped)
        assertTrue(events.closed)
        assertEquals("leave call-1", api.calls.last())
        assertEquals(EndReason.NoAnswer, log.entries.value.single().reason)
    }

    @Test
    fun anUnansweredIncomingCallIsLoggedAsMissed() = runTest {
        val calls = controller()
        calls.incoming("call-1", "conv-1", "Ana")
        runCurrent()

        events.push(CallSignal.Ended("hung_up"))
        runCurrent()

        assertEquals(CallState.Ended(EndReason.Missed), calls.state.value.phase)
        assertTrue(log.entries.value.single().missed)
        assertFalse("never joined, so no leave", api.calls.any { it.startsWith("leave") })
    }

    @Test
    fun decliningTellsTheServiceAndEnds() = runTest {
        val calls = controller()
        calls.incoming("call-1", "conv-1", "Ana")
        runCurrent()

        calls.decline()
        runCurrent()

        assertEquals(CallState.Ended(EndReason.Declined), calls.state.value.phase)
        assertEquals(listOf("decline call-1"), api.calls)
    }

    @Test
    fun theOtherSideDecliningEndsAnOutgoingCall() = runTest {
        val calls = controller()
        calls.startCall("conv-1", "Ana")
        runCurrent()

        events.push(CallSignal.Declined(ANA.account))
        runCurrent()

        assertEquals(CallState.Ended(EndReason.Declined), calls.state.value.phase)
    }

    @Test
    fun aGroupCallEndsOnlyWhenEveryoneDeclined() = runTest {
        val calls = controller()
        calls.startCall("conv-g", "Family", others = 2)
        runCurrent()

        events.push(CallSignal.Declined(ANA.account))
        runCurrent()
        assertEquals(CallState.Calling, calls.state.value.phase)
        events.push(CallSignal.Declined(ION.account))
        runCurrent()
        assertEquals(CallState.Ended(EndReason.Declined), calls.state.value.phase)
    }

    @Test
    fun whenEveryoneElseLeavesTheCallEndsAndKeysAreWiped() = runTest {
        val calls = controller()
        calls.startCall("conv-1", "Ana")
        runCurrent()
        events.roster(ME)
        events.roster(ME, ANA)
        engine.emit(EngineEvent.Connected)
        runCurrent()
        anaSendsKey(ME, ANA)
        runCurrent()
        val ring = engine.ring!!
        assertTrue(ring.current != null)

        events.roster(ME)
        runCurrent()

        assertEquals(CallState.Ended(EndReason.EveryoneLeft), calls.state.value.phase)
        assertNull(ring.current)
        assertFalse(calls.state.value.encrypted)
    }

    @Test
    fun aLostConnectionReconnectsOrGivesUpAfter20Seconds() = runTest {
        val calls = controller()
        calls.startCall("conv-1", "Ana")
        runCurrent()
        events.roster(ME)
        events.roster(ME, ANA)
        engine.emit(EngineEvent.Connected)
        runCurrent()
        anaSendsKey(ME, ANA)
        runCurrent()

        engine.emit(EngineEvent.Lost)
        runCurrent()
        assertEquals(CallState.Reconnecting, calls.state.value.phase)
        engine.emit(EngineEvent.Connected)
        runCurrent()
        assertEquals(CallState.InCall, calls.state.value.phase)

        engine.emit(EngineEvent.Lost)
        runCurrent()
        advanceTimeBy(20_001)
        assertEquals(CallState.Ended(EndReason.ConnectionLost), calls.state.value.phase)
    }

    @Test
    fun muteSpeakerAndTheSpeakingRing() = runTest {
        val calls = controller()
        calls.startCall("conv-g", "Family", others = 2)
        runCurrent()
        events.roster(ME, ANA, ION)
        runCurrent()

        calls.toggleMute()
        calls.toggleSpeaker()
        engine.emit(EngineEvent.Speaking(setOf(ION, ME)))
        runCurrent()

        assertTrue(engine.isMuted)
        assertTrue(engine.speakerOn)
        assertTrue(calls.state.value.muted)
        assertEquals(listOf(false, true), calls.state.value.people.map { it.speaking })
        assertFalse("muted: my own ring stays off", calls.state.value.selfSpeaking)
    }

    @Test
    fun aSecondCallWhileBusyIsRefused() = runTest {
        val calls = controller()
        calls.startCall("conv-1", "Ana")

        assertFalse(calls.incoming("call-2", "conv-2", "Ion"))
        assertFalse(calls.startCall("conv-3", "Mia"))
    }

    @Test
    fun aFailedStartEndsAsFailedAndCanBeDismissed() = runTest {
        api.failCreate = IllegalStateException("network")
        val calls = controller()
        calls.startCall("conv-1", "Ana")
        runCurrent()

        assertEquals(CallState.Ended(EndReason.Failed), calls.state.value.phase)
        calls.dismiss()
        assertEquals(CallUiState(), calls.state.value)
        assertTrue(calls.startCall("conv-1", "Ana"))
    }

    @Test
    fun hangingUpLogsTheDuration() = runTest {
        val calls = controller()
        calls.startCall("conv-1", "Ana")
        runCurrent()
        events.roster(ME)
        events.roster(ME, ANA)
        engine.emit(EngineEvent.Connected)
        runCurrent()
        anaSendsKey(ME, ANA)
        runCurrent()

        advanceTimeBy(65_000)
        calls.hangup()
        runCurrent()

        val entry = log.entries.value.single()
        assertEquals(EndReason.HungUp, entry.reason)
        assertEquals(65L, entry.durationSeconds)
        assertEquals(CallDirection.Outgoing, entry.direction)
    }

    @Test
    fun theCallerHearsARingbackUntilTheOtherSideAnswers() = runTest {
        val ringback = FakeRingback()
        val calls = controller(ringback)

        calls.startCall("conv-1", "Ana")
        runCurrent()
        assertEquals("the tone starts with the ringing call", listOf("start"), ringback.events)

        events.roster(ME, ANA)
        runCurrent()

        assertEquals("and stops once the callee joins", listOf("start", "stop"), ringback.events)
        assertFalse(ringback.playing)
        assertEquals(CallState.Connecting, calls.state.value.phase)
    }

    @Test
    fun theRingbackStopsWhenNobodyAnswers() = runTest {
        val ringback = FakeRingback()
        val calls = controller(ringback)
        calls.startCall("conv-1", "Ana")
        runCurrent()

        advanceTimeBy(45_001)

        assertEquals(CallState.Ended(EndReason.NoAnswer), calls.state.value.phase)
        assertEquals(listOf("start", "stop"), ringback.events)
    }

    @Test
    fun theRingbackStopsWhenTheCalleeDeclines() = runTest {
        val ringback = FakeRingback()
        val calls = controller(ringback)
        calls.startCall("conv-1", "Ana")
        runCurrent()

        events.push(CallSignal.Declined(ANA.account))
        runCurrent()

        assertEquals(CallState.Ended(EndReason.Declined), calls.state.value.phase)
        assertEquals(listOf("start", "stop"), ringback.events)
    }

    @Test
    fun theRingbackStopsOnHangupAndOnAFailedStart() = runTest {
        val hung = FakeRingback()
        val calls = controller(hung)
        calls.startCall("conv-1", "Ana")
        runCurrent()

        calls.hangup()
        runCurrent()

        assertEquals(CallState.Ended(EndReason.HungUp), calls.state.value.phase)
        assertEquals(listOf("start", "stop"), hung.events)

        api.failCreate = IllegalStateException("network")
        val failed = FakeRingback()
        controller(failed).startCall("conv-1", "Ana")
        runCurrent()

        assertEquals("a call that never gets off the ground is silent too", listOf("start", "stop"), failed.events)
    }

    @Test
    fun theCalleeHearsNoRingback() = runTest {
        val ringback = FakeRingback()
        val calls = controller(ringback)

        calls.incoming("call-1", "conv-1", "Ana")
        runCurrent()

        assertEquals("the ringback belongs to the caller alone", emptyList<String>(), ringback.events)
        assertEquals(CallState.Ringing, calls.state.value.phase)
    }

    @Test
    fun signingOutMidCallReleasesEverythingEvenWhenTheShellScopeIsAlreadyGone() = runTest {
        val shellScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val calls =
            CallController(
                api,
                events,
                engine,
                TestSealer(ME),
                ME,
                log,
                shellScope,
                clock = { testScheduler.currentTime }
            )
        calls.startCall("conv-1", "Ana")
        runCurrent()
        events.roster(ME, ANA)
        engine.emit(EngineEvent.Connected)
        runCurrent()
        anaSendsKey(ANA, ME)
        runCurrent()
        assertEquals(CallState.InCall, calls.state.value.phase)

        // The shell is being disposed, so its scope is cancelling: a teardown launched into it would never start,
        // which is how a signed-out call used to keep the microphone and the socket alive. Only the app's own
        // cleanup scope is still running.
        shellScope.cancel()
        val cleanup = CoroutineScope(StandardTestDispatcher(testScheduler))

        calls.signOut(cleanup).join()
        runCurrent()

        assertEquals(CallState.Ended(EndReason.HungUp), calls.state.value.phase)
        assertEquals(EndReason.HungUp, log.entries.value.single().reason)
        assertTrue("the peer connection and the microphone are gone", engine.stopped)
        assertTrue("the engine's own scope is given up as well", engine.released)
        assertTrue("the event socket is closed", events.closed)
        assertEquals("leave call-1", api.calls.last())
        assertFalse("the call keys are wiped", calls.state.value.encrypted)
        cleanup.cancel()
    }

    @Test
    fun signingOutAnIdleShellStillGivesUpTheEngine() = runTest {
        val calls = controller()

        calls.signOut(this).join()
        runCurrent()

        assertTrue("no call ran, but the engine's scope must not linger", engine.released)
        assertFalse(engine.stopped)
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun signingOutWhileRingingStopsTheRingbackToo() = runTest {
        val ringback = FakeRingback()
        val calls = controller(ringback)
        calls.startCall("conv-1", "Ana")
        runCurrent()
        assertEquals(listOf("start"), ringback.events)

        calls.signOut(this).join()
        runCurrent()

        assertEquals(listOf("start", "stop"), ringback.events)
        assertEquals(CallState.Ended(EndReason.HungUp), calls.state.value.phase)
    }
}
