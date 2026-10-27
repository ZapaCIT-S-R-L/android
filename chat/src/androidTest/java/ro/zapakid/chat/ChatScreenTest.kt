package ro.zapakid.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ro.zapakid.chat.ui.ChatActions
import ro.zapakid.chat.ui.ChatScreen
import ro.zapakid.chat.ui.ChatTags
import ro.zapakid.chat.ui.ConversationListScreen

/** The chat screens with fixed data. English UI. */
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val ana = Conversation("c1", "Ana", 2, false, listOf("me", "ana"), "Hi!", 10, 2)
    private val log = mutableListOf<String>()
    private val actions =
        object : ChatActions {
            override fun send(text: String) {
                log += "send $text"
            }

            override fun pickPhoto() {
                log += "photo"
            }

            override fun startVoice() {
                log += "voice"
            }

            override fun stopVoice(send: Boolean) {
                log += "stop $send"
            }

            override fun call() {
                log += "call"
            }

            override fun retry(message: Message) {
                log += "retry ${message.id}"
            }

            override fun play(message: Message) {
                log += "play ${message.id}"
            }

            override fun back() {
                log += "back"
            }
        }

    @Test
    fun theListShowsChatsWithUnreadCounts() {
        var opened: Conversation? = null
        compose.setContent { MaterialTheme { ConversationListScreen(listOf(ana)) { opened = it } } }

        compose.onNodeWithContentDescription("2 new messages").assertIsDisplayed()
        compose.onNodeWithText("Ana").performClick()
        assertEquals(ana, opened)
    }

    @Test
    fun typingAndSendingAndCallingFromAChat() {
        val messages =
            listOf(
                Message("m1", "c1", "ana", false, MessageKind.Text, "Hi!", null, 10, MessageStatus.Received),
                Message("m2", "c1", "me", true, MessageKind.Text, "Oops", null, 11, MessageStatus.Failed)
            )
        compose.setContent { MaterialTheme { ChatScreen(ana, messages, recording = false, actions = actions) } }

        compose.onNodeWithText("Hi!").assertIsDisplayed()
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("Hello Ana")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.onNodeWithContentDescription("Call Ana").performClick()
        compose.onNodeWithText("Not sent. Tap to try again").performClick()

        assertEquals(listOf("send Hello Ana", "call", "retry m2"), log)
    }

    @Test
    fun anEmptyInputOffersAVoiceMessage() {
        compose.setContent { MaterialTheme { ChatScreen(ana, emptyList(), recording = false, actions = actions) } }

        compose.onNodeWithContentDescription("Record a voice message").performClick()
        compose.onNodeWithContentDescription("Send a photo").performClick()

        assertEquals(listOf("voice", "photo"), log)
    }
}
