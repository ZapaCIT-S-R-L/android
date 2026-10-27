package ro.zapakid.calls.ring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The full-screen incoming call: the caller's name from the phone, big Answer/Decline, actions reach the app. */
class IncomingCallActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val seen = mutableListOf<String>()

    @After
    fun tearDown() {
        CallRinger.handler = null
    }

    @Test
    fun showsTheCallerAndDeclineReachesTheApp() {
        CallRinger.handler =
            object : CallRingerHandler {
                override fun accept(callId: String) {
                    seen += "accept $callId"
                }

                override fun decline(callId: String) {
                    seen += "decline $callId"
                }

                override fun hangup(callId: String) {
                    seen += "hangup $callId"
                }
            }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<IncomingCallActivity>(
            IncomingCallActivity.intent(context, "call-1", ConversationTitle("Mum", 4, false))
        ).use {
            compose.onNodeWithText("Mum").assertIsDisplayed()
            compose.onNodeWithText("is calling you!").assertIsDisplayed()
            compose.onNodeWithContentDescription("Decline").performClick()
            compose.waitUntil(5_000) { seen.isNotEmpty() }
        }
        assertEquals(listOf("decline call-1"), seen)
    }
}
