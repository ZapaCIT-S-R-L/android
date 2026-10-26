package ro.zapakid.app.auth.signin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import ro.zapakid.app.testing.APPLE_CODE
import ro.zapakid.app.testing.APPLE_CONFIG
import ro.zapakid.app.testing.APPLE_STATE

/**
 * Sign in with Apple on Android: the web flow from the API contract (POST /v1/auth/apple/web). The app opens Apple
 * with the API's state; the API's callback 303s to the App Link https://zapakid.ro/app/apple#code=...&state=...
 */
class AppleSignInTest {
    private val providerNonce = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun theAuthorizationUrlFollowsTheContract() {
        val url = AppleAuthorization.url(APPLE_CONFIG, state = APPLE_STATE, providerNonce = providerNonce).toHttpUrl()

        assertEquals("https", url.scheme)
        assertEquals("appleid.apple.com", url.host)
        assertEquals("/auth/authorize", url.encodedPath)
        assertEquals("ro.zapakid.signin", url.queryParameter("client_id"))
        assertEquals("https://api-staging.zapakid.ro/v1/auth/apple/callback", url.queryParameter("redirect_uri"))
        assertEquals("code id_token", url.queryParameter("response_type"))
        assertEquals("form_post", url.queryParameter("response_mode"))
        assertEquals("name email", url.queryParameter("scope"))
        assertEquals(APPLE_STATE, url.queryParameter("state"))
        assertEquals(providerNonce, url.queryParameter("nonce"))
    }

    @Test
    fun theStagingConfigUsesTheZapaKidServicesId() {
        assertEquals("ro.zapakid.signin", AppleSignInConfig.STAGING.servicesId)
        assertEquals("https://api-staging.zapakid.ro/v1/auth/apple/callback", AppleSignInConfig.STAGING.redirectUri)
    }

    @Test
    fun theReturnWithTheRightStateGivesTheOneTimeCode() {
        val result = AppleAuthorization.parseReturn(
            "https://zapakid.ro/app/apple#code=$APPLE_CODE&state=$APPLE_STATE",
            APPLE_STATE
        )
        assertEquals(AppleReturn.Code(APPLE_CODE), result)
    }

    @Test
    fun aReturnWithAnotherStateIsRejected() {
        val result = AppleAuthorization.parseReturn(
            "https://zapakid.ro/app/apple#code=$APPLE_CODE&state=forged",
            APPLE_STATE
        )
        assertEquals(AppleReturn.Rejected, result)
    }

    @Test
    fun aReturnOnAnotherHostOrPathIsRejected() {
        val fragment = "#code=$APPLE_CODE&state=$APPLE_STATE"
        for (url in listOf(
            "https://evil.example/app/apple",
            "https://zapakid.ro/app/other",
            "http://zapakid.ro/app/apple"
        )) {
            assertEquals(url, AppleReturn.Rejected, AppleAuthorization.parseReturn(url + fragment, APPLE_STATE))
        }
    }

    @Test
    fun theUserCancellingAtAppleIsACancel() {
        val result =
            AppleAuthorization.parseReturn(
                "https://zapakid.ro/app/apple#error=user_cancelled_authorize&state=$APPLE_STATE",
                APPLE_STATE
            )
        assertEquals(AppleReturn.Cancelled, result)
    }

    @Test
    fun anApiErrorIsPassedOn() {
        val result =
            AppleAuthorization.parseReturn(
                "https://zapakid.ro/app/apple#error=provider_unavailable&state=$APPLE_STATE",
                APPLE_STATE
            )
        assertEquals(AppleReturn.Error("provider_unavailable"), result)
    }

    @Test
    fun aReturnWithoutACodeIsRejected() {
        assertEquals(
            AppleReturn.Rejected,
            AppleAuthorization.parseReturn("https://zapakid.ro/app/apple#state=$APPLE_STATE", APPLE_STATE)
        )
    }
}
