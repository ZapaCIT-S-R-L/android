package com.zapacit.cuzaai.ui

import com.zapacit.cuzaai.auth.AgeRange
import com.zapacit.cuzaai.auth.AuthApiException
import com.zapacit.cuzaai.auth.AuthRepository
import com.zapacit.cuzaai.auth.Provider
import com.zapacit.cuzaai.auth.Session
import com.zapacit.cuzaai.auth.SignInError
import com.zapacit.cuzaai.testing.ANA
import com.zapacit.cuzaai.testing.FakeAgeSignals
import com.zapacit.cuzaai.testing.FakeAuthApi
import com.zapacit.cuzaai.testing.FakeIdTokenProvider
import com.zapacit.cuzaai.testing.InMemorySessionStore
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

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val api = FakeAuthApi()
    private val store = InMemorySessionStore()
    private val ages = FakeAgeSignals()
    private val google = FakeIdTokenProvider()
    private val apple = FakeIdTokenProvider()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AuthViewModel(AuthRepository(api, store, ages))

    @Test
    fun withoutASessionTheSignInScreenShows() = runTest {
        assertEquals(AuthUiState.SignedOut(), viewModel().state.value)
    }

    @Test
    fun aStoredSessionGoesStraightToTheSignedInState() = runTest {
        store.session = Session("tok", ANA)
        assertEquals(AuthUiState.SignedIn(ANA), viewModel().state.value)
    }

    @Test
    fun googleSignInShowsTheAgreementThenTheSignedInState() = runTest {
        val vm = viewModel()

        vm.signIn(Provider.GOOGLE, google)
        assertEquals(AuthUiState.Agreement(ANA), vm.state.value)
        assertEquals(Provider.GOOGLE, api.signIns.single().provider)
        assertEquals("nonce-1", api.signIns.single().nonce)

        vm.acceptAgreement()
        assertEquals(AuthUiState.SignedIn(ANA), vm.state.value)
    }

    @Test
    fun aMinorSeesTheBlockedScreen() = runTest {
        ages.range = AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = 13, upper = 15)
        val vm = viewModel()

        vm.signIn(Provider.GOOGLE, google)

        assertEquals(AuthUiState.Blocked, vm.state.value)
    }

    @Test
    fun cancellingTheAccountSheetJustReturnsToSignIn() = runTest {
        google.token = null
        val vm = viewModel()

        vm.signIn(Provider.GOOGLE, google)

        assertEquals(AuthUiState.SignedOut(), vm.state.value)
    }

    @Test
    fun aServerErrorIsShownOnTheSignInScreen() = runTest {
        api.signInError = AuthApiException(500, "internal", "Boom")
        val vm = viewModel()

        vm.signIn(Provider.APPLE, apple)

        assertEquals(AuthUiState.SignedOut(error = SignInError.SERVER), vm.state.value)
    }

    @Test
    fun logoutReturnsToSignInAndForgetsTheSession() = runTest {
        store.session = Session("tok", ANA)
        val vm = viewModel()

        vm.logout()

        assertEquals(AuthUiState.SignedOut(), vm.state.value)
        assertEquals(null, store.session)
    }
}
