package ro.zapakid.calls.ui

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.zapakid.calls.R

/** A permission calls need, with the kid-friendly words shown before the system dialog. */
enum class CallPermission(
    val permission: String,
    @StringRes val title: Int,
    @StringRes val body: Int,
    /** Shown when the permission was refused: what stops working, and where to turn it on. */
    @StringRes val denied: Int? = null
) {
    Microphone(
        Manifest.permission.RECORD_AUDIO,
        R.string.permission_mic_title,
        R.string.permission_mic_body,
        R.string.permission_mic_denied
    ),
    Notifications(
        "android.permission.POST_NOTIFICATIONS",
        R.string.permission_notifications_title,
        R.string.permission_notifications_body
    );

    companion object {
        /** Notifications need asking only on Android 13 and newer. */
        fun needed(sdk: Int = Build.VERSION.SDK_INT): List<CallPermission> =
            if (sdk >= Build.VERSION_CODES.TIRAMISU) entries else listOf(Microphone)
    }
}

/** Explains one permission in plain words; "OK, allow" then opens the system dialog. */
@Composable
fun PermissionExplainer(permission: CallPermission, onAllow: () -> Unit, onNotNow: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("permission_${permission.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RobotAvatar(index = 1, size = 96.dp)
        Text(
            stringResource(permission.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Text(stringResource(permission.body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.permission_allow))
        }
        TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.permission_not_now))
        }
    }
}
