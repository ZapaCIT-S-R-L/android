package ro.zapakid.app.auth.signin

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Sign in with Apple on Android: Apple's web flow, per the API contract (POST /v1/auth/apple/web).
 *  1. The app gets a nonce, makes a PKCE verifier, and the API returns the flow's `state`.
 *  2. A Custom Tab opens Apple with the Services ID, the API's callback, `state` and SHA-256(nonce).
 *  3. Apple posts to the API's callback, which verifies Apple's token and 303s to the verified App Link
 *     https://zapakid.ro/app/apple#code=...&state=...  (the API's own one-time code, never an Apple token).
 *  4. The app checks `state` and trades the code + verifier at POST /v1/auth/apple/exchange.
 */
data class AppleSignInConfig(
    /** The Apple Services ID used as client_id (public, not a secret). */
    val servicesId: String,
    val redirectUri: String
) {
    companion object {
        val STAGING =
            AppleSignInConfig(
                servicesId = "ro.zapakid.signin",
                redirectUri = "https://api-staging.zapakid.ro/v1/auth/apple/callback"
            )
    }
}

sealed interface AppleReturn {
    data class Code(val code: String) : AppleReturn

    data object Cancelled : AppleReturn

    /** An error from the API's callback (invalid_state, invalid_token, provider_unavailable) or from Apple. */
    data class Error(val code: String) : AppleReturn

    /** Wrong link, wrong state, or no code: possibly forged, never used. */
    data object Rejected : AppleReturn
}

object AppleAuthorization {
    private const val AUTHORIZE = "https://appleid.apple.com/auth/authorize"
    private const val RETURN_HOST = "zapakid.ro"
    private const val RETURN_PATH = "/app/apple"
    private val CANCELLED = setOf("user_cancelled_authorize")

    fun url(config: AppleSignInConfig, state: String, providerNonce: String): String = AUTHORIZE
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("client_id", config.servicesId)
        .addQueryParameter("redirect_uri", config.redirectUri)
        .addQueryParameter("response_type", "code id_token")
        .addQueryParameter("response_mode", "form_post")
        .addQueryParameter("scope", "name email")
        .addQueryParameter("state", state)
        .addQueryParameter("nonce", providerNonce)
        .build()
        .toString()

    fun parseReturn(returnUrl: String, expectedState: String): AppleReturn {
        val link = returnUrl.toHttpUrlOrNull()
        val fragment = returnUrl.substringAfter('#', missingDelimiterValue = "")
        // Parse the fragment as a query string with OkHttp's decoder.
        val params: HttpUrl = "https://return.invalid/?$fragment".toHttpUrl()
        val error = params.queryParameter("error")
        val code = params.queryParameter("code")?.takeIf { it.isNotBlank() }
        return when {
            link == null || !link.isHttps || link.host != RETURN_HOST || link.encodedPath != RETURN_PATH ->
                AppleReturn.Rejected

            params.queryParameter("state") != expectedState -> AppleReturn.Rejected

            error in CANCELLED -> AppleReturn.Cancelled

            error != null -> AppleReturn.Error(error)

            code == null -> AppleReturn.Rejected

            else -> AppleReturn.Code(code)
        }
    }
}

/** Opens Apple's authorize URL and returns the App Link the API redirected back to. */
interface AppleBrowser {
    /** @throws SignInCancelled when the parent closes the tab without finishing. */
    suspend fun authorize(url: String): String
}

/**
 * [AppleBrowser] with a Custom Tab. MainActivity (singleTask) forwards the returning App Link to [onReturn] in
 * onNewIntent, and calls [onResumed] whenever it resumes: a resume without a link means the tab was closed.
 */
class CustomTabAppleBrowser(
    private val activity: () -> Activity,
    private val openCustomTab: (
        Activity,
        Uri
    ) -> Unit = { host, uri -> CustomTabsIntent.Builder().build().launchUrl(host, uri) }
) : AppleBrowser {
    private var pending: CompletableDeferred<String>? = null

    override suspend fun authorize(url: String): String {
        pending?.cancel()
        val result = CompletableDeferred<String>()
        pending = result
        openCustomTab(activity(), url.toUri())
        return result.await()
    }

    /** @return true when a sign-in was waiting for this link. */
    fun onReturn(uri: Uri): Boolean {
        val result = pending ?: return false
        pending = null
        result.complete(uri.toString())
        return true
    }

    fun onResumed() {
        pending?.let {
            pending = null
            it.completeExceptionally(SignInCancelled())
        }
    }
}
