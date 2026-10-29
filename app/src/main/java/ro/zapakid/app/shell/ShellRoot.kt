@file:Suppress("MatchingDeclarationName")

package ro.zapakid.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import ro.zapakid.calls.CallController
import ro.zapakid.calls.CallLog
import ro.zapakid.calls.CallPeople
import ro.zapakid.calls.api.CallEventsClient
import ro.zapakid.calls.api.HttpCallsApi
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.engine.CallEngine
import ro.zapakid.calls.ui.CallActions
import ro.zapakid.calls.ui.CallLogScreen
import ro.zapakid.chat.ChatDevice
import ro.zapakid.chat.ChatRepository
import ro.zapakid.chat.ChatSync
import ro.zapakid.chat.data.ChatStore
import ro.zapakid.chat.net.HttpChatApi

/** Everything the shell needs from the app graph. */
@Suppress("LongParameterList") // a plain holder of the app graph's parts
class ShellDeps(
    val http: OkHttpClient,
    val chatUrl: HttpUrl,
    val callsUrl: HttpUrl,
    val tokens: ChatTokenClient,
    val sealers: SealerSource,
    val store: () -> ChatStore,
    val engine: () -> CallEngine,
    val callLog: CallLog,
    /** Outlives this composition: a sign-out shuts the session down there, not on the shell's own scope. */
    val shutdown: SessionShutdown
)

private class Services(val chats: ChatRepository, val sync: ChatSync, val calls: CallController)

/** The signed-in app (parent or paired kid): builds chat and calls once this phone's identity and keys are known. */
@Composable
fun ShellRoot(deps: ShellDeps, kid: Boolean, family: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val services by produceState<Services?>(null, deps) {
        val sealers = deps.sealers.sealers() ?: return@produceState
        val me = runCatching { deps.tokens.identity() }.getOrNull() ?: return@produceState
        val chatApi = HttpChatApi(deps.chatUrl, deps.http, deps.tokens)
        val deviceId = me.device.toIntOrNull() ?: return@produceState
        val chats = ChatRepository(chatApi, deps.store(), sealers.chat, ChatDevice(me.account, deviceId))
        val calls =
            CallController(
                HttpCallsApi(deps.callsUrl, deps.http, deps.tokens),
                CallEventsClient(deps.callsUrl, deps.http, deps.tokens, CallDevice(me.account, me.device))::connect,
                deps.engine(),
                sealers.calls,
                CallDevice(me.account, me.device),
                deps.callLog,
                scope,
                CallPeople.Default
            )
        value = Services(chats, ChatSync(chats, chatApi, chatApi), calls)
    }
    val s = services
    // The jobs that use the chat store, so a sign-out can cancel and await them before the store closes.
    val workers = remember { mutableStateListOf<Job>() }
    LaunchedEffect(s) {
        workers.clear()
        s?.let { workers += scope.launch { it.sync.run() } }
    }
    // Registering the session rather than shutting it down here: leaving the composition is also a rotation, and a
    // rotation must not hang up. Sign-out goes through the app graph, whose scope outlives this one.
    DisposableEffect(s) {
        val session = s?.let { SessionShutdown.Session(it.calls::signOut) { workers.toList() } }
        session?.let(deps.shutdown::attach)
        onDispose { session?.let(deps.shutdown::detach) }
    }
    val call = s?.calls?.state?.collectAsState()?.value ?: ro.zapakid.calls.CallUiState()
    val log by deps.callLog.entries.collectAsState()
    AppShell(
        kid = kid,
        call = call,
        callActions = s?.calls?.let(::actionsOf) ?: NoCall,
        chats = { if (s == null) SettingUpScreen() else ChatsRoute(s.chats, s.calls) },
        calls = {
            if (s == null) {
                SettingUpScreen()
            } else {
                CallLogScreen(log, avatarOf = { 0 }, onCallBack = { e ->
                    e.conversationId?.let { s.calls.startCall(it, e.title, if (e.group) 2 else 1) }
                })
            }
        },
        family = family
    )
}

private fun actionsOf(c: CallController) = object : CallActions {
    override fun accept() = c.accept()

    override fun decline() = c.decline()

    override fun hangup() = c.hangup()

    override fun toggleMute() = c.toggleMute()

    override fun toggleSpeaker() = c.toggleSpeaker()

    override fun dismiss() = c.dismiss()
}

private object NoCall : CallActions {
    override fun accept() = Unit

    override fun decline() = Unit

    override fun hangup() = Unit

    override fun toggleMute() = Unit

    override fun toggleSpeaker() = Unit

    override fun dismiss() = Unit
}
