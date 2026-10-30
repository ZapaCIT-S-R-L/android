package ro.zapakid.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ro.zapakid.app.auth.Nonces
import ro.zapakid.app.auth.Pkce
import ro.zapakid.app.keys.DeviceRegistration
import ro.zapakid.app.testing.APPLE_CODE
import ro.zapakid.app.testing.FakeAgeSignals
import ro.zapakid.app.testing.FakeAuthApi
import ro.zapakid.app.testing.InMemorySessionStore
import ro.zapakid.app.testing.NONCE

/**
 * Sign in with Apple's return trip on a real device: the API's callback answers 303 to the App Link
 * https://zapakid.ro/app/apple#code=...&state=..., the system routes it through the manifest's intent filter to the
 * singleTask MainActivity, and the app exchanges the code. Apple and the API are stubbed; the Custom Tab is replaced
 * by a launcher that fires the App Link the way the browser does after the redirect.
 */
@RunWith(AndroidJUnit4::class)
class AppleAppLinkTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<ZapaKidApplication>()
    private val original = app.graph

    @After
    fun restore() {
        app.graph = original
    }

    private fun appLink(fragment: String) = Intent(Intent.ACTION_VIEW, "https://zapakid.ro/app/apple#$fragment".toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(app.packageName)

    @Test
    fun theAppLinkFromTheApiCallbackFinishesTheAppleSignIn() {
        val api = FakeAuthApi()
        val opened = mutableListOf<Uri>()
        app.graph =
            AppGraph(
                app,
                api = api,
                store = InMemorySessionStore(),
                ageSignals = FakeAgeSignals(),
                deviceRegistrar = { DeviceRegistration.Result.Registered(1) },
                openCustomTab = { activity: Activity, uri: Uri ->
                    opened += uri
                    // What happens after Apple: the API's callback redirects the browser to the App Link.
                    activity.startActivity(appLink("code=$APPLE_CODE&state=${uri.getQueryParameter("state")}"))
                }
            )

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("Sign in with Apple").performClick()
            compose.waitUntil(timeoutMillis = 10_000) { api.appleExchanges.isNotEmpty() }
            compose.waitForIdle()

            compose.onNodeWithTag("agreement").assertIsDisplayed()
            val authorize = opened.single()
            assertEquals("appleid.apple.com", authorize.host)
            assertEquals("ro.zapakid.signin", authorize.getQueryParameter("client_id"))
            assertEquals(Nonces.providerNonce(NONCE), authorize.getQueryParameter("nonce"))
            val exchange = api.appleExchanges.single()
            assertEquals(APPLE_CODE, exchange.code)
            assertEquals(api.appleChallenges.single(), Pkce.challenge(exchange.codeVerifier))
        }
    }

    @Test
    fun theAppLinkResolvesToMainActivity() {
        val matches = app.packageManager.queryIntentActivities(appLink("code=x&state=y"), 0)
        assertEquals(MainActivity::class.java.name, matches.single().activityInfo.name)
    }

    @Test
    fun otherZapaKidLinksDoNotOpenTheApp() {
        for (url in listOf(
            "https://zapakid.ro/app/other",
            "https://www.zapakid.ro/app/apple",
            "http://zapakid.ro/app/apple"
        )) {
            val intent =
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(app.packageName)
            assertTrue(url, app.packageManager.queryIntentActivities(intent, 0).isEmpty())
        }
    }
}
