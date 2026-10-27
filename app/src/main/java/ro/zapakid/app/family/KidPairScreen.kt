@file:Suppress("MatchingDeclarationName")

package ro.zapakid.app.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.zapakid.app.R
import ro.zapakid.calls.ui.RobotAvatar

object KidPairTags {
    const val SCREEN = "kid_pair"
    const val SCAN = "kid_pair_scan"
    const val STATUS = "kid_pair_status"
}

/** The kid's phone before pairing: one big button, plain words. */
@Composable
fun KidPairScreen(state: KidPairingState, onScan: () -> Unit, onTryAgain: () -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag(KidPairTags.SCREEN)) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val paired = state as? KidPairingState.Paired
            RobotAvatar(index = paired?.session?.kid?.avatar?.let(Avatars::index) ?: 2, size = 140.dp)
            Text(
                if (paired !=
                    null
                ) {
                    stringResource(R.string.kid_pair_hello, paired.session.kid.nickname)
                } else {
                    stringResource(R.string.kid_pair_title)
                },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            val status =
                when (state) {
                    KidPairingState.Waiting -> stringResource(R.string.kid_pair_body)
                    KidPairingState.Pairing -> stringResource(R.string.kid_pair_working)
                    is KidPairingState.Paired -> stringResource(R.string.kid_pair_done)
                    is KidPairingState.Failed -> stringResource(errorText(state.error))
                }
            Text(
                status,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(KidPairTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite }
            )
            when (state) {
                KidPairingState.Pairing -> CircularProgressIndicator()

                KidPairingState.Waiting ->
                    Button(onScan, Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(KidPairTags.SCAN)) {
                        Text(stringResource(R.string.kid_pair_scan), style = MaterialTheme.typography.titleLarge)
                    }

                is KidPairingState.Failed ->
                    Button(onTryAgain, Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        Text(stringResource(R.string.kid_pair_try_again))
                    }

                is KidPairingState.Paired -> Unit
            }
            if (state !is KidPairingState.Paired) TextButton(onBack) { Text(stringResource(R.string.kid_pair_back)) }
        }
    }
}
