package ro.zapakid.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.zapakid.calls.ui.RobotAvatar
import ro.zapakid.chat.Conversation
import ro.zapakid.chat.Message
import ro.zapakid.chat.MessageKind
import ro.zapakid.chat.MessageStatus
import ro.zapakid.chat.R

object ChatTags {
    const val LIST = "chat_list"
    const val EMPTY = "chat_empty"
    const val ROW = "chat_row"
    const val SCREEN = "chat_screen"
    const val INPUT = "chat_input"
    const val SEND = "chat_send"
    const val CALL = "chat_call"
    const val PHOTO = "chat_photo"
    const val VOICE = "chat_voice"
    const val BUBBLE = "chat_bubble"
}

/** Chats, newest first, with an unread badge. */
@Composable
fun ConversationListScreen(conversations: List<Conversation>, onOpen: (Conversation) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.chat_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 16.dp).semantics { heading() }
            )
            if (conversations.isEmpty()) {
                Text(
                    stringResource(R.string.chat_empty),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 32.dp).testTag(ChatTags.EMPTY)
                )
            }
            LazyColumn(Modifier.testTag(ChatTags.LIST)) {
                items(conversations, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { onOpen(c) }.testTag(ChatTags.ROW),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RobotAvatar(index = c.avatar, size = 48.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            c.lastMessage?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (c.unread > 0) {
                            val unread = pluralStringResource(R.plurals.chat_unread, c.unread, c.unread)
                            Badge(Modifier.semantics { contentDescription = unread }) { Text("${c.unread}") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** What the chat screen asks for; media comes from the system photo picker and the voice recorder. */
interface ChatActions {
    fun send(text: String)

    fun pickPhoto()

    fun startVoice()

    fun stopVoice(send: Boolean)

    fun call()

    fun retry(message: Message)

    fun play(message: Message)

    fun back()
}

/** One conversation: big bubbles, a call button on top, text, photo and hold-to-talk voice notes below. */
@Composable
fun ChatScreen(
    conversation: Conversation,
    messages: List<Message>,
    recording: Boolean,
    actions: ChatActions,
    image: (Message) -> ImageBitmap? = { null }
) {
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    Surface(Modifier.fillMaxSize().imePadding().testTag(ChatTags.SCREEN)) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(actions::back) { Text(stringResource(R.string.chat_back)) }
                RobotAvatar(index = conversation.avatar, size = 36.dp)
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics {
                        heading()
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(actions::call, Modifier.size(56.dp).testTag(ChatTags.CALL)) {
                    Icon(
                        painterResource(R.drawable.ic_chat_call),
                        contentDescription = stringResource(R.string.chat_call, conversation.title)
                    )
                }
            }
            HorizontalDivider()
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 12.dp),
                state = list,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { Bubble(it, conversation.group, actions, image) }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(actions::pickPhoto, Modifier.size(52.dp).testTag(ChatTags.PHOTO)) {
                    Icon(
                        painterResource(R.drawable.ic_chat_photo),
                        contentDescription = stringResource(R.string.chat_photo)
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_TEXT) },
                    placeholder = { Text(stringResource(R.string.chat_type)) },
                    modifier = Modifier.weight(1f).testTag(ChatTags.INPUT)
                )
                if (text.isBlank()) {
                    val label = stringResource(if (recording) R.string.chat_voice_stop else R.string.chat_voice)
                    IconButton(
                        { if (recording) actions.stopVoice(send = true) else actions.startVoice() },
                        Modifier.size(
                            52.dp
                        ).background(
                            if (recording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            CircleShape
                        ).testTag(ChatTags.VOICE)
                    ) { Icon(painterResource(R.drawable.ic_chat_mic), contentDescription = label) }
                } else {
                    IconButton({
                        actions.send(text)
                        text = ""
                    }, Modifier.size(52.dp).testTag(ChatTags.SEND)) {
                        Icon(
                            painterResource(R.drawable.ic_chat_send),
                            contentDescription = stringResource(R.string.chat_send)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: Message, group: Boolean, actions: ChatActions, image: (Message) -> ImageBitmap?) {
    val mine = message.mine
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(18.dp)
                ).padding(12.dp)
                .testTag(ChatTags.BUBBLE)
        ) {
            if (group &&
                !mine
            ) {
                Text(
                    message.sender,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            when (message.kind) {
                MessageKind.Text -> Text(message.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)

                MessageKind.Image -> {
                    val bitmap = image(message)
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap,
                            contentDescription = stringResource(R.string.chat_photo_received),
                            modifier = Modifier.widthIn(max = 260.dp)
                        )
                    } else {
                        TextButton({ actions.play(message) }) { Text(stringResource(R.string.chat_photo_open)) }
                    }
                }

                MessageKind.Voice -> {
                    val seconds = (message.attachment?.durationMillis ?: 0) / MILLIS
                    TextButton({ actions.play(message) }, Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.chat_voice_note, seconds))
                    }
                }
            }
            when (message.status) {
                MessageStatus.Sending -> Text(
                    stringResource(R.string.chat_sending),
                    style = MaterialTheme.typography.labelSmall
                )

                MessageStatus.Failed ->
                    TextButton({
                        actions.retry(message)
                    }) { Text(stringResource(R.string.chat_failed), color = MaterialTheme.colorScheme.error) }

                else -> Unit
            }
        }
    }
}

private const val MAX_TEXT = 4000
private const val MILLIS = 1000
