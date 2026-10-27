package ro.zapakid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.zapakid.app.auth.signin.AppleBrowser
import ro.zapakid.app.auth.signin.IdTokenProvider

/** The app's root: picks the screen for the sign-in state. */
@Composable
fun ZapaKidApp(
    viewModel: AuthViewModel,
    deviceSetup: DeviceSetupViewModel,
    google: IdTokenProvider,
    apple: AppleBrowser
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is AuthUiState.SignedOut ->
            SignInScreen(
                busy = s.busy,
                error = s.error,
                retryAfterSeconds = s.retryAfterSeconds,
                onGoogle = { viewModel.signInWithGoogle(google) },
                onApple = { viewModel.signInWithApple(apple) }
            )

        is AuthUiState.Agreement -> AgreementScreen(onContinue = viewModel::acceptAgreement)

        is AuthUiState.SignedIn -> {
            LaunchedEffect(Unit) { deviceSetup.start() }
            val device by deviceSetup.state.collectAsStateWithLifecycle()
            HomeScreen(
                parent = s.parent,
                device = device,
                onRetryDevice = deviceSetup::retry,
                onSignOut = viewModel::logout
            )
        }

        AuthUiState.Blocked -> BlockedScreen(onBack = viewModel::backToSignIn)

        AuthUiState.AgeDeclined -> AgeDeclinedScreen(onTryAgain = viewModel::backToSignIn)

        AuthUiState.AttestationFailed -> AttestationFailedScreen(onBack = viewModel::backToSignIn)
    }
}
