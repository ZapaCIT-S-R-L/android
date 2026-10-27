package ro.zapakid.app

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ro.zapakid.app.auth.AgeRange
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.auth.AuthRepository
import ro.zapakid.app.auth.Provider
import ro.zapakid.app.keys.DeviceRegistrar
import ro.zapakid.app.keys.DeviceRegistration
import ro.zapakid.app.testing.APPLE_CONFIG
import ro.zapakid.app.testing.FakeAgeSignals
import ro.zapakid.app.testing.FakeAppleBrowser
import ro.zapakid.app.testing.FakeAuthApi
import ro.zapakid.app.testing.FakeIdTokenProvider
import ro.zapakid.app.testing.InMemorySessionStore
import ro.zapakid.app.ui.AuthViewModel
import ro.zapakid.app.ui.DeviceSetupViewModel
import ro.zapakid.app.ui.ZapaKidApp
import ro.zapakid.app.ui.theme.ZapaKidTheme

/** Parent sign-in end to end in the UI, against a stubbed backend and stubbed Google/Apple sheets. */
@RunWith(AndroidJUnit4::class)
class SignInFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val api = FakeAuthApi()
    private val store = InMemorySessionStore()
    private val ages = FakeAgeSignals()
    private val google = FakeIdTokenProvider()
    private val apple = FakeAppleBrowser()

    /** Stands in for device registration: answers [deviceResults] in order. */
    private val deviceResults = ArrayDeque(listOf<DeviceRegistration.Result>(DeviceRegistration.Result.Registered(2)))
    private val registrar = DeviceRegistrar { deviceResults.removeFirst() }

    private fun launch(fontScale: Float = 1f) {
        val viewModel = AuthViewModel(AuthRepository(api, store, ages, APPLE_CONFIG))
        val deviceSetup = DeviceSetupViewModel(registrar)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                ZapaKidTheme { ZapaKidApp(viewModel, deviceSetup, google = google, apple = apple) }
            }
        }
    }

    private fun signInWithGoogle() {
        compose.onNodeWithContentDescription("Sign in with Google").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
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
        ages.range = AgeRange.shared(18, null, "SHARED")
        launch()

        compose.onNodeWithContentDescription("Sign in with Google").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("agreement").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Signed in as Ana Pop").assertIsDisplayed()
        assertEquals(Provider.GOOGLE, api.signIns.single().provider)
        assertEquals("zks_session-token-1", store.session?.token)

        compose.onNodeWithContentDescription("Sign out").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sign in with Google").assertIsDisplayed()
        assertNull(store.session)
        assertEquals(listOf("zks_session-token-1"), api.logouts)
    }

    @Test
    fun appleSignInUsesTheAppleEndpoint() {
        launch()

        compose.onNodeWithContentDescription("Sign in with Apple").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("agreement").assertIsDisplayed()
        assertEquals(Provider.APPLE, api.signIns.single().provider)
        assertEquals("the browser opened Apple once", 1, apple.opened.size)
        assertEquals(1, api.appleExchanges.size)
    }

    @Test
    fun aMinorSeesTheBlockedScreen() {
        ages.range = AgeRange.shared(13, 15, "SHARED")
        launch()

        compose.onNodeWithContentDescription("Sign in with Google").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("blocked").assertIsDisplayed()
        compose.onNodeWithText("ZapaKid accounts are for parents", substring = true).assertIsDisplayed()
        assertNull(store.session)
    }

    @Test
    fun aDeclinedAgeSeesTheAgeDeclinedScreenAndCanTryAgain() {
        api.signInError = AuthApiException(403, "age_declined", "Declined.")
        launch()

        compose.onNodeWithContentDescription("Sign in with Google").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("age-declined").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sign in with Google").assertIsDisplayed()
    }

    @Test
    fun rateLimitingTellsHowLongToWait() {
        api.nonceError = AuthApiException(429, "rate_limited", "Slow down.", retryAfterSeconds = 120)
        launch()

        compose.onNodeWithContentDescription("Sign in with Google").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Try again in 2 minutes", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFailedAppCheckExplainsAndOffersBack() {
        api.signInError = AuthApiException(403, "attestation_failed", "Not genuine.")
        launch()

        compose.onNodeWithContentDescription("Sign in with Google").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("attestation-failed").assertIsDisplayed()
        compose.onNodeWithText("Google Play", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Back").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sign in with Google").assertIsDisplayed()
    }

    @Test
    fun afterSignInThePhoneSetsUpEncryption() {
        launch()
        signInWithGoogle()

        compose.onNodeWithText("End-to-end encryption is set up on this phone.").assertIsDisplayed()
    }

    @Test
    fun aPhoneWaitingForItsKeysSaysSo() {
        deviceResults.clear()
        deviceResults += DeviceRegistration.Result.WaitingForReshare
        launch()
        signInWithGoogle()

        compose.onNodeWithText("Waiting for your other phone", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFailedSetupOffersRetry() {
        deviceResults.clear()
        deviceResults += DeviceRegistration.Result.Failed(DeviceRegistration.Error.NETWORK)
        deviceResults += DeviceRegistration.Result.Registered(2)
        launch()
        signInWithGoogle()

        compose.onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("End-to-end encryption is set up on this phone.").assertIsDisplayed()
    }
}
