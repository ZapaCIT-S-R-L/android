package ro.zapakid.app.testing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.File

/**
 * Records every HTTP exchange of the JVM tests (the request the app built, the response the fake returned) for the
 * contract check against openapi.yaml (scripts/contract-check.js). Output: the file in system property
 * `contract.out` (set by Gradle); without it nothing is recorded.
 */
object ContractRecorder {
    const val OFF_SPEC = "X-Contract-Off-Spec"

    @Serializable
    private class Exchange(
        val method: String,
        val path: String,
        val requestBody: String?,
        val status: Int,
        val responseBody: String?,
        val offSpec: String? = null,
    )

    private val out: File? = System.getProperty("contract.out")?.let(::File)
    private val json = Json { explicitNulls = false }

    /** An OkHttpClient that records; use it for every HTTP client under test. */
    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()

    private val interceptor =
        Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val requestBody = request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }
            val responseBody = response.peekBody(Long.MAX_VALUE).string()
            record(
                Exchange(
                    method = request.method,
                    path = request.url.encodedPath,
                    requestBody = requestBody?.takeIf { it.isNotEmpty() },
                    status = response.code,
                    responseBody = responseBody.takeIf { it.isNotEmpty() },
                    offSpec = response.header(OFF_SPEC),
                ),
            )
            response
        }

    @Synchronized
    private fun record(exchange: Exchange) {
        val file = out ?: return
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(Exchange.serializer(), exchange) + "\n")
    }
}
