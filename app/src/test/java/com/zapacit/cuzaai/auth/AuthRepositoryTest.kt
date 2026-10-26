package com.zapacit.cuzaai.auth

import com.zapacit.cuzaai.testing.ANA
import com.zapacit.cuzaai.testing.FakeAgeSignals
import com.zapacit.cuzaai.testing.FakeAuthApi
import com.zapacit.cuzaai.testing.InMemorySessionStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    private val api = FakeAuthApi()
    private val store = InMemorySessionStore()
    private val ages = FakeAgeSignals()
    private val repository = AuthRepository(api, store, ages)

    @Test
    fun signInSendsTheAgeSignalAndStoresTheSession() = runTest {
        ages.range = AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = 18, upper = null)

        val result = repository.signIn(Provider.GOOGLE, idToken = "id", nonce = "n")

        assertEquals(SignInResult.SignedIn(ANA), result)
        assertEquals(ages.range, api.signIns.single().ageRange)
        assertEquals("session-token-1", store.session?.token)
    }

    @Test
    fun aMinorOnThePhoneIsBlockedWithoutContactingTheServer() = runTest {
        ages.range = AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = 13, upper = 15)

        val result = repository.signIn(Provider.GOOGLE, "id", "n")

        assertEquals(SignInResult.Blocked, result)
        assertTrue("the sign-in token is not sent anywhere", api.signIns.isEmpty())
        assertNull(store.session)
    }

    @Test
    fun aMinorRejectedByTheServerIsBlockedAndNoSessionIsKept() = runTest {
        api.signInError = AuthApiException(status = 403, code = "minor", message = "Parents must be 18 or older.")

        val result = repository.signIn(Provider.APPLE, "id", "n")

        assertEquals(SignInResult.Blocked, result)
        assertNull(store.session)
    }

    @Test
    fun otherServerErrorsAreFailuresNotBlocks() = runTest {
        api.signInError = AuthApiException(status = 500, code = "internal", message = "Boom")

        val result = repository.signIn(Provider.GOOGLE, "id", "n")

        assertEquals(SignInResult.Failed(SignInError.SERVER), result)
        assertNull(store.session)
    }

    @Test
    fun logoutClearsTheSessionAndTellsTheServer() = runTest {
        store.session = Session("tok", ANA)

        repository.logout()

        assertEquals(listOf("tok"), api.logouts)
        assertNull(store.session)
    }

    @Test
    fun logoutClearsTheSessionEvenWhenTheServerIsUnreachable() = runTest {
        store.session = Session("tok", ANA)
        api.logoutError = IOException("offline")

        repository.logout()

        assertNull(store.session)
    }

    @Test
    fun restoreRefreshesTheProfileForAStoredSession() = runTest {
        store.session = Session("tok", ANA.copy(name = "Old name"))

        assertEquals(ANA, repository.restore())
        assertEquals(ANA, store.session?.parent)
    }

    @Test
    fun restoreDropsASessionTheServerNoLongerAccepts() = runTest {
        store.session = Session("tok", ANA)
        api.meError = AuthApiException(status = 401, code = "unauthorized", message = "Sign in again.")

        assertNull(repository.restore())
        assertNull(store.session)
    }

    @Test
    fun restoreKeepsTheSessionWhenOffline() = runTest {
        store.session = Session("tok", ANA)
        api.meError = AuthApiException(status = 503, code = "http_503", message = "Unavailable")

        assertEquals(ANA, repository.restore())
        assertEquals("tok", store.session?.token)
    }
}
