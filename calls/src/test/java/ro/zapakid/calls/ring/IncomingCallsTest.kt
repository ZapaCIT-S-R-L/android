package ro.zapakid.calls.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** From push to ringing: the system call UI shows the conversation's local name, the controller rings. */
class IncomingCallsTest {
    private val system = FakeCallSystem()
    private val rung = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private val incoming =
        IncomingCalls(
            system = system,
            titles = { conversationId -> if (conversationId == "conv-1") ConversationTitle("Ana", 3, false) else null },
            ring = { callId, conversationId, title ->
                rung += "$callId $conversationId ${title.name}"
                true
            },
            cancel = { cancelled += it }
        )

    @Test
    fun aKnownConversationRingsWithItsName() {
        incoming.onPush(mapOf("type" to "call", "callId" to "call-1", "conversationId" to "conv-1"))

        assertEquals(listOf("call-1 conv-1 Ana"), rung)
        assertEquals(listOf("incoming call-1 Ana"), system.log)
    }

    @Test
    fun anUnknownConversationIsIgnored() {
        incoming.onPush(mapOf("type" to "call", "callId" to "call-1", "conversationId" to "conv-9"))

        assertTrue(rung.isEmpty())
        assertTrue(system.log.isEmpty())
    }

    @Test
    fun aCancelEndsTheRinging() {
        incoming.onPush(mapOf("type" to "call", "callId" to "call-1", "conversationId" to "conv-1"))
        incoming.onPush(mapOf("type" to "call_cancel", "callId" to "call-1"))

        assertEquals(listOf("call-1"), cancelled)
        assertEquals("ended call-1", system.log.last())
    }

    @Test
    fun aBusyPhoneDoesNotShowASecondCall() {
        val busy = IncomingCalls(system, { ConversationTitle("Ana", 0, false) }, { _, _, _ -> false }, {})

        busy.onPush(mapOf("type" to "call", "callId" to "call-2", "conversationId" to "conv-1"))

        assertTrue(system.log.isEmpty())
    }

    private class FakeCallSystem : CallSystem {
        val log = mutableListOf<String>()

        override fun reportIncoming(callId: String, title: ConversationTitle) {
            log += "incoming $callId ${title.name}"
        }

        override fun reportOutgoing(callId: String, title: ConversationTitle) {
            log += "outgoing $callId ${title.name}"
        }

        override fun reportEnded(callId: String) {
            log += "ended $callId"
        }
    }
}
