package ro.zapakid.app.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import ro.zapakid.calls.CallState
import ro.zapakid.calls.CallUiState
import ro.zapakid.calls.ui.CallActions
import ro.zapakid.calls.ui.CallTags

/** The bottom navigation and the call overlay. English UI. */
class AppShellTest {
    @get:Rule
    val compose = createComposeRule()

    private val noCall =
        object : CallActions {
            override fun accept() = Unit

            override fun decline() = Unit

            override fun hangup() = Unit

            override fun toggleMute() = Unit

            override fun toggleSpeaker() = Unit

            override fun dismiss() = Unit
        }

    private fun show(kid: Boolean, call: CallUiState = CallUiState()) = compose.setContent {
        MaterialTheme {
            AppShell(kid, call, noCall, chats = {
                Text("CHATS")
            }, calls = { Text("CALLS") }, family = { Text("FAMILY") })
        }
    }

    @Test
    fun parentsGetChatsCallsAndFamily() {
        show(kid = false)

        compose.onNodeWithText("CHATS").assertIsDisplayed()
        compose.onNodeWithTag("tab_calls").performClick()
        compose.onNodeWithText("CALLS").assertIsDisplayed()
        compose.onNodeWithTag("tab_family").performClick()
        compose.onNodeWithText("FAMILY").assertIsDisplayed()
    }

    @Test
    fun kidsGetChatsAndCallsOnly() {
        show(kid = true)

        compose.onNodeWithTag("tab_family").assertDoesNotExist()
        compose.onNodeWithTag("tab_calls").assertIsDisplayed()
    }

    @Test
    fun aRingingCallCoversTheApp() {
        show(kid = true, call = CallUiState(phase = CallState.Ringing, callId = "c", title = "Mum"))

        compose.onNodeWithTag(CallTags.ACCEPT).assertIsDisplayed()
        compose.onNodeWithText("CHATS").assertDoesNotExist()
    }
}
