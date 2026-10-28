@file:Suppress("MatchingDeclarationName")

package ro.zapakid.calls.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.zapakid.calls.CallDirection
import ro.zapakid.calls.CallLogEntry
import ro.zapakid.calls.R

object CallLogTags {
    const val LIST = "call_log"
    const val EMPTY = "call_log_empty"
    const val ROW = "call_log_row"
}

/** Recent calls, newest first; missed calls in red; one tap calls back. */
@Composable
fun CallLogScreen(
    entries: List<CallLogEntry>,
    avatarOf: (CallLogEntry) -> Int,
    onCallBack: (CallLogEntry) -> Unit,
    now: () -> Long = System::currentTimeMillis
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.call_log_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 16.dp).semantics { heading() }
            )
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.call_log_empty),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp).testTag(CallLogTags.EMPTY)
                )
            } else {
                LazyColumn(Modifier.testTag(CallLogTags.LIST)) {
                    items(entries, key = { it.callId + it.startedAtMillis }) { entry ->
                        LogRow(entry, avatarOf(entry), now, onCallBack)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: CallLogEntry, avatar: Int, now: () -> Long, onCallBack: (CallLogEntry) -> Unit) {
    val kind =
        when {
            entry.missed -> stringResource(R.string.call_log_missed)
            entry.direction == CallDirection.Incoming -> stringResource(R.string.call_log_incoming)
            else -> stringResource(R.string.call_log_outgoing)
        }
    val icon =
        when {
            entry.missed -> R.drawable.ic_call_missed
            entry.direction == CallDirection.Incoming -> R.drawable.ic_call_received
            else -> R.drawable.ic_call_made
        }
    val color = if (entry.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val ago = DateUtils.getRelativeTimeSpanString(entry.startedAtMillis, now(), DateUtils.MINUTE_IN_MILLIS).toString()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(CallLogTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RobotAvatar(index = avatar, size = 44.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (entry.missed) color else MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text("$kind · $ago", style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
        IconButton(onClick = { onCallBack(entry) }, modifier = Modifier.size(56.dp)) {
            Icon(
                painterResource(R.drawable.ic_call),
                contentDescription = stringResource(R.string.call_log_call_again, entry.title),
                tint = AcceptGreen
            )
        }
    }
}
