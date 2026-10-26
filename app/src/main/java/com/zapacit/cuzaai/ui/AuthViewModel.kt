package com.zapacit.cuzaai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapacit.cuzaai.auth.AuthRepository
import com.zapacit.cuzaai.auth.Parent
import com.zapacit.cuzaai.auth.SignInError
import com.zapacit.cuzaai.auth.SignInResult
import com.zapacit.cuzaai.auth.signin.AppleBrowser
import com.zapacit.cuzaai.auth.signin.IdTokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
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

    /** From the blocked or age-declined screen. */
    fun backToSignIn() {
        val current = _state.value
        if (current == AuthUiState.Blocked || current == AuthUiState.AgeDeclined) _state.value = AuthUiState.SignedOut()
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _state.value = AuthUiState.SignedOut()
        }
    }
}
