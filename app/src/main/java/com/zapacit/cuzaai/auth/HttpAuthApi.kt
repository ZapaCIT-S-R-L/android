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
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val jsonType = "application/json".toMediaType()

    override suspend fun signIn(
        provider: Provider,
        idToken: String,
        nonce: String?,
        name: String?,
        ageRange: AgeRange
    ): Session {
        val body = json.encodeToString(SignInRequest.serializer(), SignInRequest(idToken, nonce, name, ageRange))
        val request =
            Request
                .Builder()
                .url(endpoint("auth/${provider.path}"))
                .post(body.toRequestBody(jsonType))
                .build()
        return json.decodeFromString(Session.serializer(), execute(request))
    }

    override suspend fun me(token: String): Parent {
        val request =
            Request
                .Builder()
                .url(endpoint("me"))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        return json.decodeFromString(MeResponse.serializer(), execute(request)).parent
    }

    override suspend fun logout(token: String) {
        val request =
            Request
                .Builder()
                .url(endpoint("auth/logout"))
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
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
            code = error?.error ?: "http_${response.code}",
            message = error?.message ?: "Server error ${response.code}"
        )
    }
}
