package ro.zapakid.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.zapakid.app.R
import ro.zapakid.app.auth.signin.AppleBrowser
import ro.zapakid.app.auth.signin.IdTokenProvider
import ro.zapakid.app.family.FamilyScreen
import ro.zapakid.app.family.FamilyViewModel
import ro.zapakid.app.family.KidPairScreen
import ro.zapakid.app.family.KidPairingState
import ro.zapakid.app.family.KidPairingViewModel

/** The app's root: picks the screen for the sign-in state; a paired kid phone goes straight to the kid's app. */
@Composable
fun ZapaKidApp(
    viewModel: AuthViewModel,
    deviceSetup: DeviceSetupViewModel,
    google: IdTokenProvider,
    apple: AppleBrowser,
    family: FamilyViewModel? = null,
    kid: KidPairingViewModel? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var kidMode by rememberSaveable { mutableStateOf(false) }
    val kidState = kid?.state?.collectAsStateWithLifecycle()?.value
    // A paired phone is a kid phone: stay on the kid's screen even when the session is revoked, so it shows pairing.
    LaunchedEffect(kidState is KidPairingState.Paired) { if (kidState is KidPairingState.Paired) kidMode = true }
    val showKid = kidMode || kidState is KidPairingState.Paired
    if (kid != null && kidState != null && showKid) {
        KidPairScreen(kidState, onScan = kid::scan, onTryAgain = kid::tryAgain, onBack = { kidMode = false })
        return
    }
    when (val s = state) {
        is AuthUiState.SignedOut -> {
            // A sign-out forgets this phone's setup, so signing in again registers the new session even for the same
            // parent: the server binds a device to the session that registered it.
            LaunchedEffect(Unit) { deviceSetup.reset() }
            SignInScreen(
                busy = s.busy,
                error = s.error,
                retryAfterSeconds = s.retryAfterSeconds,
                onGoogle = { viewModel.signInWithGoogle(google) },
                onApple = { viewModel.signInWithApple(apple) },
                onKidPhone = { kidMode = true }
            )
        }

        is AuthUiState.Agreement -> AgreementScreen(onContinue = viewModel::acceptAgreement)

        is AuthUiState.SignedIn -> {
            LaunchedEffect(s.parent.id) { deviceSetup.start(s.parent.id) }
            val device by deviceSetup.state.collectAsStateWithLifecycle()
            if (family == null) {
                HomeScreen(s.parent, device, onRetryDevice = deviceSetup::retry, onSignOut = viewModel::logout)
            } else {
                FamilyRoute(
                    family,
                    onSignOut = viewModel::logout,
                    device = device,
                    onRetryDevice = deviceSetup::retry
                )
            }
        }

        AuthUiState.Blocked -> BlockedScreen(onBack = viewModel::backToSignIn)

        AuthUiState.AgeDeclined -> AgeDeclinedScreen(onTryAgain = viewModel::backToSignIn)

        AuthUiState.AttestationFailed -> AttestationFailedScreen(onBack = viewModel::backToSignIn)
    }
}

/** The parent's family screen, loaded (and created on first open) when shown. */
@Composable
fun FamilyRoute(
    family: FamilyViewModel,
    onSignOut: () -> Unit,
    device: DeviceSetupState? = null,
    onRetryDevice: () -> Unit = {}
) {
    val state by family.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.family == null) family.load() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            FamilyScreen(
                state = state,
                onInviteParent = family::inviteParent,
                onAddKid = family::addKid,
                onPairKid = family::pairKid,
                onCloseQr = family::closeQr,
                onDismissError = family::clearError
            )
        }
        if (device != null) DeviceSetupStatus(device, onRetryDevice)
        TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
    }
}
