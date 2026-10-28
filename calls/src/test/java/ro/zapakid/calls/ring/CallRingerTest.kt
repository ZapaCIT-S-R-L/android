package ro.zapakid.calls.ring

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Answer and Decline from Telecom or the notification reach the call controller even when a push woke a fresh
 * process before the app wired it (review of #46, item 1); the ringing UI stops at once (item 4).
 */
class CallRingerTest {
    private val log = mutableListOf<String>()
    private val timers = mutableListOf<() -> Unit>()

    private val handler =
        object : CallRingerHandler {
            override fun accept(callId: String) {
                log += "accept $callId"
            }

            override fun decline(callId: String) {
                log += "decline $callId"
            }

            override fun hangup(callId: String) {
                log += "hangup $callId"
            }

            override fun missed(callId: String) {
                log += "missed $callId"
            }
        }

    @Before
    fun setUp() {
        CallRinger.reset()
        CallRinger.scheduler = { _, block -> timers += block }
        CallRinger.onAccepted = { log += "active $it" }
        CallRinger.onExpired = { log += "failed $it" }
        CallRinger.stopRinging = { log += "stop $it" }
    }

    @After
    fun tearDown() = CallRinger.reset()

    @Test
    fun withTheControllerWiredAnAnswerGoesThroughAndTheCallTurnsActive() {
        CallRinger.handler = handler
        CallRinger.send("call-1", CallRinger.Action.ACCEPT)
        assertEquals(listOf("stop call-1", "accept call-1", "active call-1"), log)
    }

    @Test
    fun anAnswerBeforeTheControllerIsWiredWaitsAndIsNotReportedActive() {
        CallRinger.send("call-1", CallRinger.Action.ACCEPT)
        assertEquals("not active yet", listOf("stop call-1"), log)

        CallRinger.handler = handler
        assertEquals(listOf("stop call-1", "accept call-1", "active call-1"), log)
    }

    @Test
    fun anAnswerThatNoControllerTakesEndsTheCallWithAnError() {
        CallRinger.send("call-1", CallRinger.Action.ACCEPT)
        timers.forEach { it() }
        assertEquals(listOf("stop call-1", "failed call-1"), log)

        CallRinger.handler = handler
        assertEquals("dropped, not replayed later", listOf("stop call-1", "failed call-1"), log)
    }

    @Test
    fun anExpiryAfterTheControllerTookTheAnswerDoesNothing() {
        CallRinger.send("call-1", CallRinger.Action.ACCEPT)
        CallRinger.handler = handler
        timers.forEach { it() }
        assertTrue("no failure", log.none { it.startsWith("failed") })
    }

    @Test
    fun aDeclineAndAMissedCallAreKeptUntilTheControllerIsWired() {
        CallRinger.send("call-1", CallRinger.Action.DECLINE)
        CallRinger.send("call-2", CallRinger.Action.MISSED)
        CallRinger.handler = handler
        assertEquals(listOf("stop call-1", "stop call-2", "decline call-1", "missed call-2"), log)
    }
}
