package ro.zapakid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.zapakid.app.auth.AuthRepository
import ro.zapakid.app.auth.Parent
import ro.zapakid.app.auth.SignInError
import ro.zapakid.app.auth.SignInResult
import ro.zapakid.app.auth.signin.AppleBrowser
import ro.zapakid.app.auth.signin.IdTokenProvider

sealed interface AuthUiState {
    data class SignedOut(
        val error: SignInError? = null,
        val busy: Boolean = false,
        /** With [SignInError.RATE_LIMITED]: how long to wait, from Retry-After. */
        val retryAfterSeconds: Long? = null
    ) : AuthUiState

    /** Signed in, before the parental agreement is accepted. */
    data class Agreement(val parent: Parent) : AuthUiState

    data class SignedIn(val parent: Parent) : AuthUiState

    data object Blocked : AuthUiState

    data object AgeDeclined : AuthUiState

    data object AttestationFailed : AuthUiState
}

class AuthViewModel(
    private val repository: AuthRepository,
    /** Runs once the session is gone, so the app graph can shut the signed-in shell down: call, sync, chat store. */
    private val onSignedOut: () -> Unit = {}
) : ViewModel() {
    private val _state =
        MutableStateFlow<AuthUiState>(
            repository.current()?.let { AuthUiState.SignedIn(it) } ?: AuthUiState.SignedOut()
        )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        if (_state.value is AuthUiState.SignedIn) {
            viewModelScope.launch {
                val parent = repository.restore()
                _state.value = parent?.let { AuthUiState.SignedIn(it) } ?: AuthUiState.SignedOut()
            }
        }
    }

    fun signInWithGoogle(sheet: IdTokenProvider) = run { repository.signInWithGoogle(sheet) }

    fun signInWithApple(browser: AppleBrowser) = run { repository.signInWithApple(browser) }

    private fun run(signIn: suspend () -> SignInResult) {
        if ((_state.value as? AuthUiState.SignedOut)?.busy == true) return
        _state.value = AuthUiState.SignedOut(busy = true)
        viewModelScope.launch {
            _state.value =
                when (val result = signIn()) {
                    is SignInResult.SignedIn -> AuthUiState.Agreement(result.parent)

                    SignInResult.Blocked -> AuthUiState.Blocked

                    SignInResult.AgeDeclined -> AuthUiState.AgeDeclined

                    SignInResult.AttestationFailed -> AuthUiState.AttestationFailed

                    SignInResult.Cancelled -> AuthUiState.SignedOut()

                    is SignInResult.Failed ->
                        AuthUiState.SignedOut(error = result.error, retryAfterSeconds = result.retryAfterSeconds)
                }
        }
    }

    // TODO(parental-agreement): record the acceptance through the API (consent record, kept for the account's
    //  lifetime + 5 years) once the legal text and the endpoint exist. Until then it is accepted on screen only.
    fun acceptAgreement() {
        val current = _state.value
        if (current is AuthUiState.Agreement) _state.value = AuthUiState.SignedIn(current.parent)
    }

    /** From the blocked, age-declined or app-check screen. */
    fun backToSignIn() {
        val current = _state.value
        if (current in BACK_TO_SIGN_IN) _state.value = AuthUiState.SignedOut()
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            onSignedOut()
            _state.value = AuthUiState.SignedOut()
        }
    }
}

private val BACK_TO_SIGN_IN = setOf(AuthUiState.Blocked, AuthUiState.AgeDeclined, AuthUiState.AttestationFailed)
