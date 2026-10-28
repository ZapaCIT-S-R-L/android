package ro.zapakid.app.shell

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ro.zapakid.app.R
import ro.zapakid.calls.CallController
import ro.zapakid.calls.ui.RobotAvatar
import ro.zapakid.chat.ChatRepository
import ro.zapakid.chat.Conversation
import ro.zapakid.chat.Message
import ro.zapakid.chat.MessageKind
import ro.zapakid.chat.ui.ChatActions
import ro.zapakid.chat.ui.ChatScreen
import ro.zapakid.chat.ui.ConversationListScreen
import ro.zapakid.chat.ui.VoiceRecorder

/** Shown in Chats and Calls until this phone's chat keys are set up; calm, with Try again after a failure. */
@Composable
fun SettingUpScreen(state: SetupState, onTryAgain: () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("setting_up")) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            RobotAvatar(index = 5, size = 120.dp)
            Text(
                stringResource(R.string.setting_up),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            if (state is SetupState.Waiting) {
                Text(
                    stringResource(R.string.setup_retry_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                OutlinedButton(onClick = onTryAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.setup_try_again))
                }
            }
        }
    }
}

/** Chats tab: the list, and one conversation with a call button that starts a call through :calls. */
@Composable
fun ChatsRoute(chats: ChatRepository, calls: CallController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val conversations by chats.conversations().collectAsState(emptyList())
    val open = conversations.firstOrNull { it.id == openId }
    if (open == null) {
        ConversationListScreen(conversations) { openId = it.id }
        return
    }
    val messages by remember(open.id) { chats.messages(open.id) }.collectAsState(emptyList())
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                scope.launch {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                    chats.sendMedia(
                        open.id,
                        MessageKind.Image,
                        bytes,
                        context.contentResolver.getType(uri) ?: "image/jpeg"
                    )
                }
            }
        }
    val images = remember(open.id) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(open.id, messages.size) { chats.markRead(open.id) }
    val showImage = { id: String, bytes: ByteArray ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { images[id] = it.asImageBitmap() }
        Unit
    }
    val actions =
        remember(open.id) {
            actions(scope, open, chats, calls, recorder, { recording = it }, { openId = null }, showImage) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    ChatScreen(open, messages, recording, actions) { images[it.id] }
}

@Suppress("LongParameterList")
private fun actions(
    scope: kotlinx.coroutines.CoroutineScope,
    open: Conversation,
    chats: ChatRepository,
    calls: CallController,
    recorder: VoiceRecorder,
    setRecording: (Boolean) -> Unit,
    back: () -> Unit,
    showImage: (String, ByteArray) -> Unit,
    pick: () -> Unit
): ChatActions {
    return object : ChatActions {
        override fun send(text: String) {
            scope.launch { chats.sendText(open.id, text) }
        }

        override fun pickPhoto() = pick()

        override fun startVoice() {
            recorder.start()
            setRecording(true)
        }

        override fun stopVoice(send: Boolean) {
            setRecording(false)
            val (bytes, millis) = recorder.stop(send) ?: return
            scope.launch { chats.sendMedia(open.id, MessageKind.Voice, bytes, "audio/mp4", millis) }
        }

        override fun call() {
            calls.startCall(
                open.id,
                open.title,
                others = (open.members.size - 1).coerceAtLeast(1),
                avatar = open.avatar
            )
        }

        override fun retry(message: Message) {
            scope.launch { chats.retry(message) }
        }

        override fun play(message: Message) {
            val ref = message.attachment ?: return
            scope.launch {
                val bytes = chats.openAttachment(ref)
                if (message.kind == MessageKind.Voice) recorder.play(bytes) else showImage(message.id, bytes)
            }
        }

        override fun back() = back()
    }
}
