package ro.zapakid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.zapakid.app.keys.DeviceRegistrar
import ro.zapakid.app.keys.DeviceRegistration

sealed interface DeviceSetupState {
    data object Idle : DeviceSetupState

    data object Working : DeviceSetupState

    data object Ready : DeviceSetupState

    data object WaitingForReshare : DeviceSetupState

    data object Failed : DeviceSetupState
}

/** Sets up this phone's encryption keys once the parent is signed in. */
class DeviceSetupViewModel(private val registrar: DeviceRegistrar) : ViewModel() {
    private val _state = MutableStateFlow<DeviceSetupState>(DeviceSetupState.Idle)
    val state: StateFlow<DeviceSetupState> = _state.asStateFlow()

    private var account: String? = null
    private var job: Job? = null

    /** Sets up [accountId] on this phone once per sign-in; another account is set up again from scratch. */
    fun start(accountId: String) {
        if (accountId != account) {
            account = accountId
            run()
        }
    }

    /**
     * After a sign-out: the next sign-in sets the phone up again, even for the same parent. The server binds the
     * device to the session that registered it, so a new session is a new device.
     */
    fun reset() {
        job?.cancel()
        account = null
        _state.value = DeviceSetupState.Idle
    }

    fun retry() {
        if (_state.value == DeviceSetupState.Failed) run()
    }

    private fun run() {
        job?.cancel()
        _state.value = DeviceSetupState.Working
        job = viewModelScope.launch {
            _state.value =
                when (registrar.register()) {
                    is DeviceRegistration.Result.Registered -> DeviceSetupState.Ready
                    DeviceRegistration.Result.WaitingForReshare -> DeviceSetupState.WaitingForReshare
                    is DeviceRegistration.Result.Failed -> DeviceSetupState.Failed
                }
        }
    }
}
