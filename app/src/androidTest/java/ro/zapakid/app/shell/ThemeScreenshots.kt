package ro.zapakid.app.shell

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import ro.zapakid.app.family.Family
import ro.zapakid.app.family.FamilyParent
import ro.zapakid.app.family.FamilyScreen
import ro.zapakid.app.family.FamilyUiState
import ro.zapakid.app.family.Kid
import ro.zapakid.app.ui.theme.ZapaKidTheme
import ro.zapakid.calls.CallPerson
import ro.zapakid.calls.CallState
import ro.zapakid.calls.CallUiState
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.ui.CallActions
import ro.zapakid.calls.ui.CallScreen
import ro.zapakid.calls.ui.UserRole
import ro.zapakid.chat.Conversation
import ro.zapakid.chat.Message
import ro.zapakid.chat.MessageKind
import ro.zapakid.chat.MessageStatus
import ro.zapakid.chat.ui.ChatActions
import ro.zapakid.chat.ui.ChatScreen
import ro.zapakid.chat.ui.ConversationListScreen

/**
 * Renders the four main screens in both looks and saves <role>-<screen>.png for review: Gradle pulls them into
 * app/build/outputs/connected_android_test_additional_output. Always passes.
 */
class ThemeScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private val chats =
        listOf(
            Conversation("c1", "Mum", 0, false, listOf("me", "mum"), "Can't wait for dinner!", 10, 1),
            Conversation("c2", "Dad", 1, false, listOf("me", "dad"), "See you at the park!", 9, 0),
            Conversation("c3", "Grandma Ana", 2, false, listOf("me", "ana"), "Love you, sweetie!", 8, 0),
            Conversation("c4", "Alex & Maria", 3, true, listOf("me", "a", "m"), "Ready to build?", 7, 2)
        )
    private val messages =
        listOf(
            Message("m1", "c1", "mum", false, MessageKind.Text, "Hi! How was school?", null, 1, MessageStatus.Received),
            Message("m2", "c1", "me", true, MessageKind.Text, "Great! We built a robot", null, 2, MessageStatus.Sent),
            Message("m3", "c1", "mum", false, MessageKind.Text, "Wow! Show me tonight", null, 3, MessageStatus.Received)
        )
    private val call =
        CallUiState(
            phase = CallState.InCall,
            callId = "c",
            title = "Mum",
            people = listOf(CallPerson(CallDevice("mum", "1"), "Mum", 0, speaking = true)),
            encrypted = true,
            connectedAtMillis = System.currentTimeMillis() - 83_000
        )
    private val family =
        FamilyUiState(
            loading = false,
            family =
                Family(
                    "f1",
                    "Pop",
                    parents = listOf(FamilyParent("p1", "Ana"), FamilyParent("p2", "Mihai")),
                    kids = listOf(Kid("k1", "Mia", "robot-3", 2), Kid("k2", "Ion", "robot-1", -1))
                )
        )

    @Test
    fun parentScreens() = shoot(UserRole.Parent)

    @Test
    fun kidScreens() = shoot(UserRole.Kid)

    private fun shoot(role: UserRole) {
        var screen by mutableStateOf("chats")
        compose.setContent {
            ZapaKidTheme(role = role, darkTheme = false) {
                when (screen) {
                    "chats" -> ConversationListScreen(chats) {}

                    "chat" -> ChatScreen(chats[0], messages, recording = false, actions = NoChat)

                    "call" -> CallScreen(call, NoCall)

                    else ->
                        if (role == UserRole.Kid) {
                            ro.zapakid.app.family.FamilyMembersScreen(family.family)
                        } else {
                            FamilyScreen(family, {}, { _, _, _ -> }, {}, {}, {})
                        }
                }
            }
        }
        for (name in listOf("chats", "chat", "call", "family")) {
            compose.runOnIdle { screen = name }
            compose.waitForIdle()
            save(compose.onRoot().captureToImage().asAndroidBitmap(), "${role.name.lowercase()}-$name.png")
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        // AGP pulls additionalTestOutputDir into build/outputs/connected_android_test_additional_output.
        val pulled = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = pulled ?: File(context.getExternalFilesDir(null), "screens")
        dir.mkdirs()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private object NoCall : CallActions {
        override fun accept() = Unit

        override fun decline() = Unit

        override fun hangup() = Unit

        override fun toggleMute() = Unit

        override fun toggleSpeaker() = Unit

        override fun dismiss() = Unit
    }

    private object NoChat : ChatActions {
        override fun send(text: String) = Unit

        override fun pickPhoto() = Unit

        override fun startVoice() = Unit

        override fun stopVoice(send: Boolean) = Unit

        override fun call() = Unit

        override fun retry(message: Message) = Unit

        override fun play(message: Message) = Unit

        override fun back() = Unit
    }
}
