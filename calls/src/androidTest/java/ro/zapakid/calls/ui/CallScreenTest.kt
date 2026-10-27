package ro.zapakid.calls.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ro.zapakid.calls.CallController
import ro.zapakid.calls.CallDirection
import ro.zapakid.calls.CallLogEntry
import ro.zapakid.calls.EndReason
import ro.zapakid.calls.InMemoryCallLog
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
import ro.zapakid.calls.testing.MIA
import ro.zapakid.calls.testing.TestSealer

/** The friendly call screens, driven through the real controller with a fake engine and service. English UI. */
class CallScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val api = FakeCallsApi()
    private val events = FakeEvents()
    private val engine = FakeCallEngine()
    private val log = InMemoryCallLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = CallController(api, events, engine, TestSealer(ME), ME, log, scope)

    private val actions =
        object : CallActions {
            override fun accept() = controller.accept()

            override fun decline() = controller.decline()

            override fun hangup() = controller.hangup()

            override fun toggleMute() = controller.toggleMute()

            override fun toggleSpeaker() = controller.toggleSpeaker()

            override fun dismiss() = controller.dismiss()
        }

    @After
    fun tearDown() = scope.cancel()

    private fun show(fontScale: Float = 1f) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme {
                val state by controller.state.collectAsState()
                CallScreen(state, actions)
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    @Test
    fun anIncomingCallShowsBigAnswerAndDeclineButtons() {
        show()
        onMain { controller.incoming("call-1", "conv-1", "Ana") }

        compose.onNodeWithText("Ana").assertIsDisplayed()
        compose.onNodeWithTag(CallTags.STATUS).assertTextEquals("is calling you!")
        compose.onNodeWithContentDescription("Answer").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Decline").assertIsDisplayed()

        compose.onNodeWithTag(CallTags.ACCEPT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(CallTags.STATUS).assertTextEquals("Connecting…")
        compose.waitUntil(5_000) { api.calls.contains("join call-1") }
    }

    @Test
    fun answeredAndKeyedTheCallShowsInCallWithMuteAndSpeaker() {
        show()
        onMain { controller.incoming("call-1", "conv-1", "Ana") }
        compose.onNodeWithTag(CallTags.ACCEPT).performClick()
        compose.waitUntil(5_000) { engine.started }
        onMain {
            events.roster(ANA, ME)
            engine.emit(EngineEvent.Connected)
        }
        val ana = CallKeyManager("call-1", ANA, TestSealer(ANA), KeyRing())
        val envelope =
            kotlinx.coroutines.runBlocking {
                ana.onRoster(listOf(ANA))
                ana.onRoster(listOf(ANA, ME)).single()
            }
        onMain { events.push(CallSignal.Key(envelope)) }

        compose.waitUntil(5_000) { controller.state.value.phase == ro.zapakid.calls.CallState.InCall }
        compose.onNodeWithText("Only you and your family can hear this call").assertIsDisplayed()
        compose
            .onNodeWithTag(CallTags.MUTE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
            .performClick()
        compose.onNodeWithTag(
            CallTags.MUTE
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        assertTrue(engine.isMuted)

        compose.onNodeWithTag(CallTags.END).performClick()
        compose.onNodeWithTag(CallTags.STATUS).assertTextEquals("Call ended")
        compose.onNodeWithTag(CallTags.CLOSE).assertIsDisplayed()
    }

    @Test
    fun anOutgoingCallSaysCallingAndNobodyAnswered() {
        show()
        onMain { controller.startCall("conv-1", "Ana") }
        compose.onNodeWithTag(CallTags.STATUS).assertTextEquals("Calling…")
        compose.onNodeWithContentDescription("End call").assertIsDisplayed()

        onMain { events.push(CallSignal.RingTimeout) }

        compose.onNodeWithTag(CallTags.STATUS).assertTextEquals("Nobody answered. Try again later!")
    }

    @Test
    fun aGroupCallShowsABubbleForEveryoneAndWhoIsTalking() {
        show()
        onMain { controller.startCall("conv-g", "Family", others = 3) }
        onMain {
            events.roster(ME, ANA, ION, MIA)
            engine.emit(EngineEvent.Speaking(setOf(ION)))
        }

        compose.waitUntil(5_000) { controller.state.value.people.size == 3 }
        compose.onAllNodesWithTag(CallTags.PERSON).assertCountEquals(3)
        compose.onNodeWithContentDescription("acc-ion, talking").assertExists()
    }

    @Test
    fun theButtonsStayReachableAtTwiceTheFontSize() {
        show(fontScale = 2f)
        onMain { controller.incoming("call-1", "conv-1", "Ana") }

        compose.onNodeWithTag(CallTags.ACCEPT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CallTags.DECLINE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theCallLogShowsMissedCallsAndCallsBack() {
        val entry = CallLogEntry("call-9", "conv-1", "Ana", CallDirection.Incoming, EndReason.Missed, 0L, 0L, false)
        var calledBack: CallLogEntry? = null
        compose.setContent { MaterialTheme { CallLogScreen(listOf(entry), { 0 }, { calledBack = it }) } }

        compose.onNodeWithText("Missed", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Call Ana").performClick()

        assertEquals(entry, calledBack)
    }

    @Test
    fun anEmptyCallLogInvitesToCall() {
        compose.setContent { MaterialTheme { CallLogScreen(emptyList(), { 0 }, {}) } }

        compose.onNodeWithTag(CallLogTags.EMPTY).assertTextEquals("No calls yet. Call someone from your family!")
    }
}
