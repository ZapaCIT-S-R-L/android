package ro.zapakid.calls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.zapakid.calls.CallDirection
import ro.zapakid.calls.CallPerson
import ro.zapakid.calls.CallState
import ro.zapakid.calls.CallUiState
import ro.zapakid.calls.EndReason
import ro.zapakid.calls.R

/** What the call screen can ask the controller to do. */
interface CallActions {
    fun accept()

    fun decline()

    fun hangup()

    fun toggleMute()

    fun toggleSpeaker()

    fun dismiss()
}

object CallTags {
    const val SCREEN = "call_screen"
    const val STATUS = "call_status"
    const val ACCEPT = "call_accept"
    const val DECLINE = "call_decline"
    const val END = "call_end"
    const val MUTE = "call_mute"
    const val SPEAKER = "call_speaker"
    const val CLOSE = "call_close"
    const val GRID = "call_grid"
    const val PERSON = "call_person"
}

/**
 * The whole call on one screen: big robot, a clear status line, big round buttons. The column scrolls, so
 * everything stays reachable at the largest font sizes.
 */
@Composable
fun CallScreen(state: CallUiState, actions: CallActions, clock: () -> Long = System::currentTimeMillis) {
    Surface(modifier = Modifier.fillMaxSize().testTag(CallTags.SCREEN)) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            if (state.group && state.people.size > 1) {
                PeopleGrid(state.people)
            } else {
                val person = state.people.firstOrNull()
                RobotAvatar(
                    index = person?.avatar ?: state.titleAvatar,
                    size = 160.dp,
                    speaking = person?.speaking == true,
                    ringing = state.phase == CallState.Calling || state.phase == CallState.Ringing
                )
            }
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Status(state, clock)
            if (state.encrypted && state.phase == CallState.InCall) {
                Text(
                    stringResource(R.string.call_encrypted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
            Buttons(state, actions)
        }
    }
}

@Composable
private fun Status(state: CallUiState, clock: () -> Long) {
    val text =
        when (val phase = state.phase) {
            CallState.Idle -> ""

            CallState.Calling -> stringResource(R.string.call_calling)

            CallState.Ringing ->
                if (state.group) stringResource(R.string.call_ringing_group) else stringResource(R.string.call_ringing)

            CallState.Connecting -> stringResource(R.string.call_connecting)

            CallState.Reconnecting -> stringResource(R.string.call_reconnecting)

            CallState.InCall ->
                if (state.group) {
                    pluralStringResource(R.plurals.call_people_count, state.people.size + 1, state.people.size + 1)
                } else {
                    stringResource(R.string.call_in_call) + " · " + elapsed(state.connectedAtMillis, clock)
                }

            is CallState.Ended -> stringResource(endText(phase.reason, state.direction))
        }
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.testTag(CallTags.STATUS).semantics {
                // The timer ticks every second: only phase changes are announced.
                if (state.phase != CallState.InCall) liveRegion = LiveRegionMode.Polite
            }
    )
}

@Composable
private fun elapsed(since: Long?, clock: () -> Long): String {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(since) {
        while (true) {
            now = clock()
            delay(SECOND)
        }
    }
    val seconds = ((now - (since ?: now)) / SECOND).coerceAtLeast(0)
    return stringResource(R.string.call_log_duration, seconds / MINUTE, seconds % MINUTE)
}

private const val SECOND = 1_000L
private const val MINUTE = 60

internal fun endText(reason: EndReason, direction: CallDirection): Int = when (reason) {
    EndReason.HungUp -> R.string.call_ended
    EndReason.Declined -> if (direction == CallDirection.Outgoing) R.string.call_ended_declined else R.string.call_ended
    EndReason.NoAnswer -> R.string.call_ended_no_answer
    EndReason.Missed -> R.string.call_ended_missed
    EndReason.EveryoneLeft -> R.string.call_ended_everyone_left
    EndReason.ConnectionLost -> R.string.call_ended_connection
    EndReason.Failed -> R.string.call_ended_failed
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleGrid(people: List<CallPerson>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag(CallTags.GRID),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val speakingText = stringResource(R.string.call_speaking)
        for (person in people) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.width(110.dp).testTag(CallTags.PERSON).semantics(mergeDescendants = true) {
                        contentDescription = if (person.speaking) "${person.name}, $speakingText" else person.name
                    }
            ) {
                RobotAvatar(index = person.avatar, size = 72.dp, speaking = person.speaking)
                Text(person.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Buttons(state: CallUiState, actions: CallActions) {
    val phase = state.phase
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        when {
            phase == CallState.Ringing -> {
                RoundButton(
                    stringResource(R.string.call_decline),
                    R.drawable.ic_call_end,
                    hangUpColor(),
                    CallTags.DECLINE,
                    actions::decline
                )
                RoundButton(
                    stringResource(R.string.call_accept),
                    R.drawable.ic_call,
                    acceptColor(),
                    CallTags.ACCEPT,
                    actions::accept
                )
            }

            phase is CallState.Ended -> {
                RoundButton(
                    stringResource(R.string.call_close),
                    R.drawable.ic_call_end,
                    MaterialTheme.colorScheme.secondary,
                    CallTags.CLOSE,
                    actions::dismiss
                )
            }

            phase != CallState.Idle -> {
                ToggleButton(
                    stringResource(R.string.call_mute),
                    if (state.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                    on = !state.muted,
                    tag = CallTags.MUTE,
                    onToggle = actions::toggleMute
                )
                RoundButton(
                    stringResource(R.string.call_end),
                    R.drawable.ic_call_end,
                    hangUpColor(),
                    CallTags.END,
                    actions::hangup
                )
                ToggleButton(
                    stringResource(R.string.call_speaker),
                    R.drawable.ic_speaker,
                    on = state.speaker,
                    tag = CallTags.SPEAKER,
                    onToggle = actions::toggleSpeaker
                )
            }
        }
    }
}
