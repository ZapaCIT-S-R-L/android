package com.zapacit.cuzaai.auth

/**
 * Parent sign-in against the ZapaKid API (api-staging.zapakid.ro). Contract: openapi.yaml in ZapaCIT-S-R-L/api.
 *   POST /v1/auth/nonce                                                   -> {nonce, expiresAt}
 *   POST /v1/auth/google {idToken, nonce, ageRange}                       -> {session: {token, expiresAt}, parent}
 *   POST /v1/auth/apple/web      {codeChallenge, codeChallengeMethod: S256} -> {state, expiresAt}
 *   POST /v1/auth/apple/exchange {code, codeVerifier, nonce, ageRange}     -> same as google
 *   (Apple's form_post goes to the API's /v1/auth/apple/callback, which 303s to the App Link with the code.)
 *   GET  /v1/me          (Bearer)                                         -> Parent
 *   POST /v1/auth/logout (Bearer)                                         -> 204
 * Errors: {"error": {"code", "message"}}; apps branch on code.
 */
interface AuthApi {
    /** A one-time nonce (10 minutes) for the next sign-in. */
    suspend fun nonce(): String

    suspend fun signInWithGoogle(idToken: String, nonce: String, ageRange: AgeRange): Session

    /** Starts the Apple web flow; returns the flow's state for Apple's authorize URL. */
    suspend fun startAppleWeb(codeChallenge: String): String

    /** Trades the one-time code from the App Link (plus this phone's PKCE verifier) for a session. */
    suspend fun exchangeApple(code: String, codeVerifier: String, nonce: String, ageRange: AgeRange): Session

    suspend fun me(token: String): Parent

    suspend fun logout(token: String)
}

/** A non-2xx answer from the API. [code] is the API's error code, or http_<status> without a JSON body. */
class AuthApiException(
    val status: Int,
    val code: String,
    message: String,
    /** From Retry-After on 429, in seconds. */
    val retryAfterSeconds: Long? = null
) : Exception(message) {
    val isMinor: Boolean get() = code == "minor_not_allowed"
    val isAgeDeclined: Boolean get() = code == "age_declined"
    val isRateLimited: Boolean get() = status == HTTP_TOO_MANY_REQUESTS
    val isUnauthorized: Boolean get() = status == HTTP_UNAUTHORIZED
    val isInvalidTokenOrNonce: Boolean get() = code in setOf("invalid_nonce", "invalid_token", "invalid_code")

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
