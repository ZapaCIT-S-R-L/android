package com.zapacit.cuzaai.auth.signin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign in with Apple on Android is the web flow: a Custom Tab to Apple, back to the app through a verified link. */
class AppleSignInTest {
    private val config =
        AppleSignInConfig(
            servicesId = "ro.zapakid.signin",
            redirectUri = "https://api-staging.zapakid.ro/v1/auth/apple/callback"
        )

    @Test
    fun theAuthorizationUrlAsksForAnIdTokenWithStateAndNonce() {
        val request = AppleAuthorization.create(config)
        val url = request.url.toHttpUrl()

        assertEquals("appleid.apple.com", url.host)
        assertEquals("/auth/authorize", url.encodedPath)
        assertEquals("ro.zapakid.signin", url.queryParameter("client_id"))
        assertEquals(config.redirectUri, url.queryParameter("redirect_uri"))
        assertEquals("code id_token", url.queryParameter("response_type"))
        assertEquals("form_post", url.queryParameter("response_mode"))
        assertEquals("name email", url.queryParameter("scope"))
        assertEquals(request.state, url.queryParameter("state"))
        assertEquals(request.nonce, url.queryParameter("nonce"))
    }

    @Test
    fun stateAndNonceAreRandomAndLongEnough() {
        val a = AppleAuthorization.create(config)
        val b = AppleAuthorization.create(config)
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.nonce, b.nonce)
        assertTrue(a.state.length >= 32 && a.nonce.length >= 32)
    }

    @Test
    fun theReturnWithTheRightStateGivesTheIdToken() {
        val request = AppleAuthorization.create(config)
        val result =
            AppleAuthorization.parseReturn(
                "https://zapakid.ro/app/apple#state=${request.state}&id_token=eyJ.abc.def&name=Ana%20Pop",
                request
            )
        assertEquals(AppleReturn.Success(IdToken("eyJ.abc.def", request.nonce, name = "Ana Pop")), result)
    }

    @Test
    fun aReturnWithAnotherStateIsRejected() {
        val request = AppleAuthorization.create(config)
        val result = AppleAuthorization.parseReturn("https://zapakid.ro/app/apple#state=forged&id_token=eyJ", request)
        assertEquals(AppleReturn.Rejected, result)
    }

    @Test
    fun theUserCancellingAtAppleIsACancel() {
        val request = AppleAuthorization.create(config)
        val result =
            AppleAuthorization.parseReturn(
                "https://zapakid.ro/app/apple#state=${request.state}&error=user_cancelled_authorize",
                request
            )
        assertEquals(AppleReturn.Cancelled, result)
    }

    @Test
    fun aReturnWithoutATokenIsRejected() {
        val request = AppleAuthorization.create(config)
        assertEquals(
            AppleReturn.Rejected,
            AppleAuthorization.parseReturn("https://zapakid.ro/app/apple#state=${request.state}", request)
        )
    }
}
