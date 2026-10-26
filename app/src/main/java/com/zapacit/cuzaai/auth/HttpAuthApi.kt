package com.zapacit.cuzaai.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** [AuthApi] over HTTPS with OkHttp. Never logs request or response bodies (tokens). */
class HttpAuthApi(private val baseUrl: HttpUrl, private val client: OkHttpClient) : AuthApi {
    // Nullable fields without a default (ageRange bounds) are sent as null; ones with a default are left out.
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    override suspend fun nonce(): String {
        val request = Request.Builder().url(endpoint("auth/nonce")).post(EMPTY).build()
        return json.decodeFromString(NonceResponse.serializer(), execute(request)).nonce
    }

    override suspend fun signIn(
        provider: Provider,
        token: String,
        nonce: String,
        displayName: String?,
        ageRange: AgeRange
    ): Session {
        val body =
            when (provider) {
                Provider.GOOGLE ->
                    json.encodeToString(GoogleSignInRequest.serializer(), GoogleSignInRequest(token, nonce, ageRange))

                Provider.APPLE ->
                    json.encodeToString(
                        AppleSignInRequest.serializer(),
                        AppleSignInRequest(token, nonce, ageRange, displayName)
                    )
            }
        val request =
            Request
                .Builder()
                .url(endpoint("auth/${provider.path}"))
                .post(body.toRequestBody(jsonType))
                .build()
        val response = json.decodeFromString(SignInResponse.serializer(), execute(request))
        return Session(response.session.token, response.session.expiresAt, response.parent)
    }

    override suspend fun me(token: String): Parent {
        val request =
            Request
                .Builder()
                .url(endpoint("me"))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        return json.decodeFromString(Parent.serializer(), execute(request))
    }

    override suspend fun logout(token: String) {
        val request =
            Request
                .Builder()
                .url(endpoint("auth/logout"))
                .header("Authorization", "Bearer $token")
                .post(EMPTY)
                .build()
        execute(request)
    }

    private fun endpoint(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client
            .newCall(request.newBuilder().header("Accept", "application/json").build())
            .execute()
            .use { response -> readOrThrow(response) }
    }

    private fun readOrThrow(response: Response): String {
        val text = response.body.string()
        if (response.isSuccessful) return text
        val error = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        throw AuthApiException(
            status = response.code,
            code = error?.error?.code ?: "http_${response.code}",
            message = error?.error?.message ?: "Server error ${response.code}",
            retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull()
        )
    }

    private companion object {
        val EMPTY = ByteArray(0).toRequestBody(null)
    }
}
