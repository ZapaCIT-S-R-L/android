@file:Suppress("MatchingDeclarationName")

package ro.zapakid.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ro.zapakid.calls.CallController
import ro.zapakid.calls.CallLog
import ro.zapakid.calls.CallUiState
import ro.zapakid.calls.ui.CallActions
import ro.zapakid.calls.ui.CallLogScreen

/** Everything the shell needs from the app graph: the retained session, and the call log the screen shows. */
class ShellDeps(val session: SignedInSession<Services>, val callLog: CallLog)

/**
 * The signed-in app (parent or paired kid). The session belongs to the app graph, not to this composition, so a
 * configuration change rebuilds the screen and reconnects to the same call, the same sync and the same chat store
 * instead of orphaning them. [SignedInSession.start] is idempotent, and leaving the composition tears nothing down:
 * leaving is also what a rotation does, and a rotation must not hang up. Sign-out goes through the app graph.
 */
@Composable
fun ShellRoot(deps: ShellDeps, kid: Boolean, family: @Composable () -> Unit) {
    LaunchedEffect(deps.session) { deps.session.start() }
    val services by deps.session.services.collectAsState()
    // A plain local, not the delegated property: the lambdas below need the smart cast to a non-null Services.
    val s = services
    val call = s?.calls?.state?.collectAsState()?.value ?: CallUiState()
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
