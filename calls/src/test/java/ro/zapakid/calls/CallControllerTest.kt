package ro.zapakid.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import ro.zapakid.calls.testing.ANA
import ro.zapakid.calls.testing.FakeCallEngine
import ro.zapakid.calls.testing.FakeCallsApi
import ro.zapakid.calls.testing.FakeEvents
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

    private fun TestScope.controller() =
        CallController(api, events, engine, TestSealer(ME), ME, log, backgroundScope, clock = {
            testScheduler.currentTime
        })

    /** Ana's phone as the key leader: seals her key to me. */
    private suspend fun anaSendsKey(vararg roster: ro.zapakid.calls.crypto.CallDevice) {
        val ana = CallKeyManager("call-1", ANA, TestSealer(ANA), KeyRing())
        ana.onRoster(listOf(ANA))
        ana.onRoster(roster.toList()).filter { it.toDevice == ME }.forEach { events.push(CallSignal.Key(it)) }
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
        assertEquals("alone, I make the first key", listOf(1), engine.rotations)

        events.roster(ME, ANA)
        engine.emit(EngineEvent.Connected)
        runCurrent()

        assertEquals(CallState.InCall, calls.state.value.phase)
        assertTrue(calls.state.value.encrypted)
        assertTrue("Ana's phone got the new key", events.sentKeys.any { it.toDevice == ANA && it.epoch == 2 })
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
        assertEquals(listOf(2), engine.rotations)
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

        events.push(CallSignal.Ended("caller_left"))
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

        events.push(CallSignal.Declined(ANA))
        runCurrent()

        assertEquals(CallState.Ended(EndReason.Declined), calls.state.value.phase)
    }

    @Test
    fun aGroupCallEndsOnlyWhenEveryoneDeclined() = runTest {
        val calls = controller()
        calls.startCall("conv-g", "Family", others = 2)
        runCurrent()

        events.push(CallSignal.Declined(ANA))
        runCurrent()
        assertEquals(CallState.Calling, calls.state.value.phase)
        events.push(CallSignal.Declined(ION))
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

        advanceTimeBy(65_000)
        calls.hangup()
        runCurrent()

        val entry = log.entries.value.single()
        assertEquals(EndReason.HungUp, entry.reason)
        assertEquals(65L, entry.durationSeconds)
        assertEquals(CallDirection.Outgoing, entry.direction)
    }
}
