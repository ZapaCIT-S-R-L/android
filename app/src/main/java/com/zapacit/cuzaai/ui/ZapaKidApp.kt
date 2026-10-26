package com.zapacit.cuzaai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapacit.cuzaai.auth.signin.AppleBrowser
import com.zapacit.cuzaai.auth.signin.IdTokenProvider

/** The app's root: picks the screen for the sign-in state. */
@Composable
fun ZapaKidApp(viewModel: AuthViewModel, google: IdTokenProvider, apple: AppleBrowser) {
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

        is AuthUiState.SignedIn -> HomeScreen(parent = s.parent, onSignOut = viewModel::logout)

        AuthUiState.Blocked -> BlockedScreen(onBack = viewModel::backToSignIn)

        AuthUiState.AgeDeclined -> AgeDeclinedScreen(onTryAgain = viewModel::backToSignIn)
    }
}
