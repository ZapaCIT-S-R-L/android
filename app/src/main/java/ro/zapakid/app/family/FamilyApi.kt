package ro.zapakid.app.family

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ro.zapakid.app.auth.Attestation
import ro.zapakid.app.auth.RequestAttestation

/**
 * Families and kid pairing on the account service (api.zapakid.ro):
 *   POST /v1/families {name?}                                  -> Family (200 existing, 201 created)
 *   GET  /v1/families                                          -> {families: [Family]}
 *   POST /v1/families/{id}/parents/invitations                 -> {code, expiresAt}
 *   POST /v1/families/join {code}                              -> Family
 *   POST /v1/families/{id}/kids {nickname, avatar, grade}      -> Kid
 *   POST /v1/families/{id}/kids/{kidId}/pairing                -> {code, expiresAt, kid}
 *   POST /v1/pairing/redeem {code} (no session, attested)      -> {session, kid, family}
 */
interface FamilyApi {
    suspend fun myFamily(token: String, name: String? = null): Family

    suspend fun families(token: String): List<Family>

    suspend fun inviteParent(token: String, familyId: String): OneTimeCode

    suspend fun join(token: String, code: String): Family

    suspend fun addKid(token: String, familyId: String, nickname: String, avatar: String, grade: Int?): Kid

    suspend fun pairKid(token: String, familyId: String, kidId: String): KidPairing

    suspend fun redeem(code: String): PairedKid
}

class FamilyApiException(val status: Int, val code: String, message: String) : Exception(message)

class HttpFamilyApi(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    private val attestation: RequestAttestation? = null
) : FamilyApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun myFamily(token: String, name: String?): Family = call(
        Family.serializer(),
        token,
        listOf("families"),
        json.encodeToString(CreateFamilyRequest.serializer(), CreateFamilyRequest(name))
    )

    override suspend fun families(token: String): List<Family> =
        call(FamiliesResponse.serializer(), token, listOf("families"), body = null).families

    override suspend fun inviteParent(token: String, familyId: String): OneTimeCode =
        call(OneTimeCode.serializer(), token, listOf("families", familyId, "parents", "invitations"), "")

    override suspend fun join(token: String, code: String): Family = call(
        Family.serializer(),
        token,
        listOf("families", "join"),
        json.encodeToString(CodeRequest.serializer(), CodeRequest(code))
    )

    override suspend fun addKid(token: String, familyId: String, nickname: String, avatar: String, grade: Int?): Kid =
        call(
            Kid.serializer(),
            token,
            listOf("families", familyId, "kids"),
            json.encodeToString(AddKidRequest.serializer(), AddKidRequest(nickname.trim(), avatar, grade))
        )

    override suspend fun pairKid(token: String, familyId: String, kidId: String): KidPairing =
        call(KidPairing.serializer(), token, listOf("families", familyId, "kids", kidId, "pairing"), "")

    override suspend fun redeem(code: String): PairedKid {
        val body = json.encodeToString(CodeRequest.serializer(), CodeRequest(code))
        val header = attestation?.let {
            runCatching { it.token(Attestation.requestHash(body.encodeToByteArray())) }.getOrNull()
        }
        return call(PairedKid.serializer(), token = null, listOf("pairing", "redeem"), body, header)
    }

    /** [body] null = GET; "" = POST without a body. Path segments are encoded one by one. */
    private suspend fun <T> call(
        serializer: KSerializer<T>,
        token: String?,
        segments: List<String>,
        body: String?,
        attestationToken: String? = null
    ): T = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
        val request =
            Request.Builder().url(url).header("Accept", "application/json").apply {
                token?.let { header("Authorization", "Bearer $it") }
                attestationToken?.let { header(Attestation.HEADER, it) }
                when {
                    body == null -> get()
                    body.isEmpty() -> post(ByteArray(0).toRequestBody(null))
                    else -> post(body.toRequestBody(jsonType))
                }
            }
        client.newCall(request.build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                val detail = (error as? kotlinx.serialization.json.JsonObject)?.get(
                    "error"
                ) as? kotlinx.serialization.json.JsonObject
                fun field(name: String) = (detail?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.content
                throw FamilyApiException(
                    response.code,
                    field("code") ?: "http_${response.code}",
                    field("message") ?: "Server error ${response.code}"
                )
            }
            json.decodeFromString(serializer, text)
        }
    }
}
