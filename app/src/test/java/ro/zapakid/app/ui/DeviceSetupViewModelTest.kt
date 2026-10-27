package ro.zapakid.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ro.zapakid.app.keys.DeviceRegistrar
import ro.zapakid.app.keys.DeviceRegistration

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSetupViewModelTest {
    private class FakeRegistrar(val results: ArrayDeque<DeviceRegistration.Result>) : DeviceRegistrar {
        var calls = 0

        override suspend fun register(): DeviceRegistration.Result {
            calls++
            return results.removeFirst()
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aRegisteredPhoneIsReady() = runTest {
        val vm = DeviceSetupViewModel(FakeRegistrar(ArrayDeque(listOf(DeviceRegistration.Result.Registered(2)))))
        assertEquals(DeviceSetupState.Idle, vm.state.value)

        vm.start("account-a")

        assertEquals(DeviceSetupState.Ready, vm.state.value)
    }

    @Test
    fun startRunsOnlyOnce() = runTest {
        val registrar = FakeRegistrar(ArrayDeque(listOf(DeviceRegistration.Result.Registered(2))))
        val vm = DeviceSetupViewModel(registrar)

        vm.start("account-a")
        vm.start("account-a")

        assertEquals(1, registrar.calls)
    }

    @Test
    fun waitingForAReshareIsShown() = runTest {
        val vm = DeviceSetupViewModel(FakeRegistrar(ArrayDeque(listOf(DeviceRegistration.Result.WaitingForReshare))))
        vm.start("account-a")
        assertEquals(DeviceSetupState.WaitingForReshare, vm.state.value)
    }

    @Test
    fun aFailureCanBeRetried() = runTest {
        val vm =
            DeviceSetupViewModel(
                FakeRegistrar(
                    ArrayDeque(
                        listOf(
                            DeviceRegistration.Result.Failed(DeviceRegistration.Error.NETWORK),
                            DeviceRegistration.Result.Registered(2)
                        )
                    )
                )
            )
        vm.start("account-a")
        assertEquals(DeviceSetupState.Failed, vm.state.value)

        vm.retry()
        assertEquals(DeviceSetupState.Ready, vm.state.value)
    }

    @Test
    fun anotherAccountSetsUpAgain() = runTest {
        val registrar =
            FakeRegistrar(
                ArrayDeque(listOf(DeviceRegistration.Result.Registered(2), DeviceRegistration.Result.WaitingForReshare))
            )
        val vm = DeviceSetupViewModel(registrar)

        vm.start("account-a")
        assertEquals(DeviceSetupState.Ready, vm.state.value)
        vm.start("account-b")

        assertEquals(2, registrar.calls)
        assertEquals(DeviceSetupState.WaitingForReshare, vm.state.value)
    }
}
