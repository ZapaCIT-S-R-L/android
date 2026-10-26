package com.zapacit.cuzaai.auth.signin

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Sign in with Apple, web flow (Apple has no native Android SDK).
 *  1. The app opens appleid.apple.com in a Custom Tab with a random state and nonce.
 *  2. Apple posts the result (form_post) to the API's [AppleSignInConfig.redirectUri].
 *  3. The API redirects to the app's verified App Link, the result in the URL fragment (never sent to any server):
 *     https://zapakid.ro/app/apple#state=...&id_token=...[&name=...]  or  #state=...&error=...
 *  4. The app checks the state and posts the id_token to POST /v1/auth/apple; the API checks the nonce.
 */
data class AppleSignInConfig(
    /** The Apple Services ID used as client_id (public, not a secret). */
    val servicesId: String,
    val redirectUri: String
)

data class AppleAuthorizationRequest(val url: String, val state: String, val nonce: String)

sealed interface AppleReturn {
    data class Success(val token: IdToken) : AppleReturn

    data object Cancelled : AppleReturn

    /** Wrong state, or no token: possibly forged, never used. */
    data object Rejected : AppleReturn
}

object AppleAuthorization {
    private const val AUTHORIZE = "https://appleid.apple.com/auth/authorize"

    fun create(config: AppleSignInConfig): AppleAuthorizationRequest {
        val state = Random256.next()
        val nonce = Random256.next()
        val url =
            AUTHORIZE
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client_id", config.servicesId)
                .addQueryParameter("redirect_uri", config.redirectUri)
                .addQueryParameter("response_type", "code id_token")
                .addQueryParameter("response_mode", "form_post")
                .addQueryParameter("scope", "name email")
                .addQueryParameter("state", state)
                .addQueryParameter("nonce", nonce)
                .build()
        return AppleAuthorizationRequest(url.toString(), state, nonce)
    }

    fun parseReturn(returnUrl: String, request: AppleAuthorizationRequest): AppleReturn {
        val fragment = returnUrl.substringAfter('#', missingDelimiterValue = "")
        // Parse the fragment as a query string with OkHttp's decoder.
        val params: HttpUrl = "https://return.invalid/?$fragment".toHttpUrl()
        val idToken = params.queryParameter("id_token")?.takeIf { it.isNotBlank() }
        return when {
            params.queryParameter("state") != request.state -> AppleReturn.Rejected
            params.queryParameter("error") != null -> AppleReturn.Cancelled
            idToken == null -> AppleReturn.Rejected
            else -> AppleReturn.Success(IdToken(idToken, request.nonce, name = params.queryParameter("name")))
        }
    }
}

/**
 * [IdTokenProvider] for Apple: opens the Custom Tab and waits for the App Link back.
 * MainActivity forwards the returning link to [onReturn], and calls [onResumed] whenever it resumes, so closing the
 * tab without finishing counts as a cancel.
 */
class AppleWebSignIn(private val activity: () -> Activity, private val config: AppleSignInConfig) : IdTokenProvider {
    private var pending: Pair<AppleAuthorizationRequest, CompletableDeferred<AppleReturn>>? = null

    override suspend fun signIn(): IdToken {
        if (config.servicesId.isBlank()) throw SignInNotConfigured("Sign in with Apple")
        val request = AppleAuthorization.create(config)
        val result = CompletableDeferred<AppleReturn>()
        pending?.second?.complete(AppleReturn.Cancelled)
        pending = request to result
        CustomTabsIntent.Builder().build().launchUrl(activity(), request.url.toUri())
        return when (val r = result.await()) {
            is AppleReturn.Success -> r.token
            AppleReturn.Cancelled -> throw SignInCancelled()
            AppleReturn.Rejected -> throw SignInFailed("Apple sign-in result rejected")
        }
    }

    /** @return true when the link was an Apple sign-in return and was handled. */
    fun onReturn(uri: Uri): Boolean {
        val (request, result) = pending ?: return false
        pending = null
        result.complete(AppleAuthorization.parseReturn(uri.toString(), request))
        return true
    }

    /** MainActivity is singleTask: a return link arrives in onNewIntent before onResume, so this only cancels. */
    fun onResumed() {
        pending?.let { (_, result) ->
            pending = null
            result.complete(AppleReturn.Cancelled)
        }
    }
}
