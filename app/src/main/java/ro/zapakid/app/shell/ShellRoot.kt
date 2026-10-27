@file:Suppress("MatchingDeclarationName")

package ro.zapakid.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
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
    val callLog: CallLog
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
        val chats = ChatRepository(chatApi, deps.store(), sealers.chat, ChatDevice(me.account, me.device))
        val calls =
            CallController(
                HttpCallsApi(deps.callsUrl, deps.http, deps.tokens),
                CallEventsClient(deps.callsUrl, deps.http, deps.tokens)::connect,
                deps.engine(),
                sealers.calls,
                CallDevice(me.account, me.device),
                deps.callLog,
                scope,
                CallPeople.Default
            )
        value = Services(chats, ChatSync(chats, chatApi, chatApi::ack), calls)
    }
    val s = services
    LaunchedEffect(s) { s?.sync?.run() }
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
