package com.zapacit.cuzaai

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zapacit.cuzaai.auth.AgeRange
import com.zapacit.cuzaai.auth.AuthRepository
import com.zapacit.cuzaai.auth.Provider
import com.zapacit.cuzaai.testing.FakeAgeSignals
import com.zapacit.cuzaai.testing.FakeAuthApi
import com.zapacit.cuzaai.testing.FakeIdTokenProvider
import com.zapacit.cuzaai.testing.InMemorySessionStore
import com.zapacit.cuzaai.ui.AuthViewModel
import com.zapacit.cuzaai.ui.ZapaKidApp
import com.zapacit.cuzaai.ui.theme.ZapaKidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Parent sign-in end to end in the UI, against a stubbed backend and stubbed Google/Apple sheets. */
@RunWith(AndroidJUnit4::class)
class SignInFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val api = FakeAuthApi()
    private val store = InMemorySessionStore()
    private val ages = FakeAgeSignals()
    private val google = FakeIdTokenProvider()
    private val apple = FakeIdTokenProvider()

    private fun launch(fontScale: Float = 1f) {
        val viewModel = AuthViewModel(AuthRepository(api, store, ages))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                ZapaKidTheme { ZapaKidApp(viewModel, google = google, apple = apple) }
            }
        }
    }

    @Test
    fun signInScreenOffersGoogleAndAppleWithLabelsAndLargeTouchTargets() {
        launch()

        for (label in listOf("Sign in with Google", "Sign in with Apple")) {
            compose
                .onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun signInScreenWorksAtDoubleFontSize() {
        launch(fontScale = 2f)

        compose.onNodeWithContentDescription("Sign in with Google").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Sign in with Apple").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun googleSignInReachesTheSignedInStateAndSignOutReturns() {
        ages.range = AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = 18, upper = null)
        launch()

        compose.onNodeWithContentDescription("Sign in with Google").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("agreement").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Signed in as Ana Pop").assertIsDisplayed()
        assertEquals(Provider.GOOGLE, api.signIns.single().provider)
        assertEquals("session-token-1", store.session?.token)

        compose.onNodeWithContentDescription("Sign out").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sign in with Google").assertIsDisplayed()
        assertNull(store.session)
        assertEquals(listOf("session-token-1"), api.logouts)
    }

    @Test
    fun appleSignInUsesTheAppleEndpoint() {
        launch()

        compose.onNodeWithContentDescription("Sign in with Apple").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("agreement").assertIsDisplayed()
        assertEquals(Provider.APPLE, api.signIns.single().provider)
    }

    @Test
    fun aMinorSeesTheBlockedScreen() {
        ages.range = AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = 13, upper = 15)
        launch()

        compose.onNodeWithContentDescription("Sign in with Google").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("blocked").assertIsDisplayed()
        compose.onNodeWithText("ZapaKid accounts are for parents", substring = true).assertIsDisplayed()
        assertNull(store.session)
    }
}
