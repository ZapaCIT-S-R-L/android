@file:Suppress("MatchingDeclarationName")

package ro.zapakid.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import ro.zapakid.app.R
import ro.zapakid.calls.CallState
import ro.zapakid.calls.CallUiState
import ro.zapakid.calls.ui.CallActions
import ro.zapakid.calls.ui.CallScreen

enum class Tab(val label: Int, val icon: Int, val tag: String) {
    Chats(R.string.tab_chats, ro.zapakid.chat.R.drawable.ic_chat_send, "tab_chats"),
    Calls(R.string.tab_calls, ro.zapakid.calls.R.drawable.ic_call, "tab_calls"),
    Family(R.string.tab_family, R.drawable.ic_family, "tab_family")
}

/**
 * The signed-in app: Chats, Calls and (for parents) Family at the bottom. A running call covers everything,
 * so answering or ending a call is always one tap away.
 */
@Composable
fun AppShell(
    kid: Boolean,
    call: CallUiState,
    callActions: CallActions,
    chats: @Composable () -> Unit,
    calls: @Composable () -> Unit,
    family: @Composable () -> Unit
) {
    if (call.phase != CallState.Idle) {
        CallScreen(call, callActions)
        return
    }
    var tab by rememberSaveable { mutableStateOf(Tab.Chats) }
    val tabs = if (kid) listOf(Tab.Chats, Tab.Calls) else Tab.entries
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in tabs) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                        modifier = Modifier.testTag(t.tag)
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Chats -> chats()
                Tab.Calls -> calls()
                Tab.Family -> family()
            }
        }
    }
}
