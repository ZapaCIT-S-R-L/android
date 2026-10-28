package ro.zapakid.calls.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable as tap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.zapakid.calls.R

internal val BigButton = 84.dp
internal val KidButton = 96.dp

@Composable
internal fun buttonSize() = if (LocalUserRole.current.playful) KidButton else BigButton

/** Accept (green), hang up (red) and their icons, from the look's tokens. */
@Composable
internal fun acceptColor() = Color(LocalPalette.current.accept)

@Composable
internal fun hangUpColor() = Color(LocalPalette.current.hangUp)

/** A big round button with its label under it; one haptic tick per press. */
@Composable
internal fun RoundButton(
    label: String,
    @DrawableRes icon: Int,
    color: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.White
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier =
            modifier
                .widthIn(min = buttonSize())
                .testTag(tag)
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick(label) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                        true
                    }
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(buttonSize())
                    .clip(CircleShape)
                    .background(color)
                    .tap {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(40.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** On/off round button (microphone, speaker): TalkBack reads the name and "On" or "Off". */
@Composable
internal fun ToggleButton(
    label: String,
    @DrawableRes icon: Int,
    on: Boolean,
    tag: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val state = stringResource(if (on) R.string.call_on else R.string.call_off)
    val palette = LocalPalette.current
    val background = if (on) Color(palette.callButton) else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (on) Color(palette.callIcon) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier =
            modifier
                .widthIn(min = buttonSize())
                .testTag(tag)
                .toggleable(value = on, role = Role.Switch) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                }.semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = state
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(buttonSize()).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}
