package ro.zapakid.calls.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The call push: a high-priority data message that carries IDs only, never names or content. */
class CallPushTest {
    @Test
    fun aCallPushCarriesTheCallAndConversation() {
        assertEquals(
            CallPush.Incoming(callId = "call-1", conversationId = "conv-1"),
            CallPush.parse(mapOf("type" to "call", "callId" to "call-1", "conversationId" to "conv-1"))
        )
    }

    @Test
    fun aCancelPushStopsTheRinging() {
        assertEquals(CallPush.Cancelled("call-1"), CallPush.parse(mapOf("type" to "call_cancel", "callId" to "call-1")))
    }

    @Test
    fun otherOrBrokenPushesAreNotCalls() {
        assertNull(CallPush.parse(mapOf("type" to "chat")))
        assertNull(CallPush.parse(mapOf("type" to "call")))
        assertNull(CallPush.parse(mapOf("type" to "call", "callId" to "x".repeat(200), "conversationId" to "c")))
        assertNull(CallPush.parse(mapOf("type" to "call", "callId" to "bad id/..", "conversationId" to "c")))
    }
}
