package com.zapacit.cuzaai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapacit.cuzaai.auth.AuthRepository
import com.zapacit.cuzaai.auth.Parent
import com.zapacit.cuzaai.auth.Provider
import com.zapacit.cuzaai.auth.SignInError
import com.zapacit.cuzaai.auth.SignInResult
import com.zapacit.cuzaai.auth.signin.IdTokenProvider
import com.zapacit.cuzaai.auth.signin.SignInCancelled
import com.zapacit.cuzaai.auth.signin.SignInFailed
import com.zapacit.cuzaai.auth.signin.SignInNotConfigured
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data class SignedOut(val error: SignInError? = null, val busy: Boolean = false) : AuthUiState

    /** Signed in, before the parental agreement is accepted. */
    data class Agreement(val parent: Parent) : AuthUiState

    data class SignedIn(val parent: Parent) : AuthUiState

    data object Blocked : AuthUiState
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

    fun signIn(provider: Provider, tokens: IdTokenProvider) {
        if ((_state.value as? AuthUiState.SignedOut)?.busy == true) return
        _state.value = AuthUiState.SignedOut(busy = true)
        viewModelScope.launch {
            val token =
                try {
                    tokens.signIn()
                } catch (ignored: SignInCancelled) {
                    _state.value = AuthUiState.SignedOut()
                    return@launch
                } catch (ignored: SignInNotConfigured) {
                    _state.value = AuthUiState.SignedOut(error = SignInError.NOT_CONFIGURED)
                    return@launch
                } catch (ignored: SignInFailed) {
                    _state.value = AuthUiState.SignedOut(error = SignInError.PROVIDER)
                    return@launch
                }
            _state.value =
                when (val result = repository.signIn(provider, token.idToken, token.nonce, token.name)) {
                    is SignInResult.SignedIn -> AuthUiState.Agreement(result.parent)
                    SignInResult.Blocked -> AuthUiState.Blocked
                    is SignInResult.Failed -> AuthUiState.SignedOut(error = result.error)
                }
        }
    }

    // TODO(parental-agreement): record the acceptance through the API (consent record, kept for the account's
    //  lifetime + 5 years) once the legal text and the endpoint exist. Until then it is accepted on screen only.
    fun acceptAgreement() {
        val current = _state.value
        if (current is AuthUiState.Agreement) _state.value = AuthUiState.SignedIn(current.parent)
    }

    fun dismissBlocked() {
        if (_state.value == AuthUiState.Blocked) _state.value = AuthUiState.SignedOut()
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _state.value = AuthUiState.SignedOut()
        }
    }
}
