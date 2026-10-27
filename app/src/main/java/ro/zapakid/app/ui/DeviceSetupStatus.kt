package ro.zapakid.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.zapakid.app.R

/** This phone's encryption setup (device registration), below the account line. */
@Composable
internal fun DeviceSetupStatus(state: DeviceSetupState, onRetry: () -> Unit) {
    val text =
        when (state) {
            DeviceSetupState.Idle, DeviceSetupState.Working -> stringResource(R.string.device_setting_up)
            DeviceSetupState.Ready -> stringResource(R.string.device_ready)
            DeviceSetupState.WaitingForReshare -> stringResource(R.string.device_waiting_for_reshare)
            DeviceSetupState.Failed -> stringResource(R.string.device_failed)
        }
    if (state ==
        DeviceSetupState.Working
    ) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = text })
    }
    Text(
        text = text,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
    if (state == DeviceSetupState.Failed) {
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.age_declined_try_again))
        }
    }
}
