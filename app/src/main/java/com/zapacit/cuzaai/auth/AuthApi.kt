package com.zapacit.cuzaai.auth

/**
 * Parent sign-in against the ZapaKid API (api-staging.zapakid.ro). Contract: openapi.yaml in ZapaCIT-S-R-L/api.
 *   POST /v1/auth/google, POST /v1/auth/apple  {id_token, nonce?, name?, age_range} -> {token, parent}
 *   GET  /v1/me                                 (Bearer)                         -> {parent}
 *   POST /v1/auth/logout                        (Bearer)                         -> 204
 * Errors: {"error": "<code>", "message": "..."}; 403 "minor" means the account is blocked.
 */
interface AuthApi {
    suspend fun signIn(provider: Provider, idToken: String, nonce: String?, name: String?, ageRange: AgeRange): Session

    suspend fun me(token: String): Parent

    suspend fun logout(token: String)
}

/** A non-2xx answer from the API. [code] is the API's error code, or http_<status> without a JSON body. */
class AuthApiException(val status: Int, val code: String, message: String) : Exception(message) {
    val isMinor: Boolean get() = code == "minor"
    val isUnauthorized: Boolean get() = status == 401
}
