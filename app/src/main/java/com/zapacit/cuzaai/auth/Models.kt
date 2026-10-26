package com.zapacit.cuzaai.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The two sign-in methods for parents. [path] is the API endpoint: POST /v1/auth/{path}. */
enum class Provider(val path: String) {
    GOOGLE("google"),
    APPLE("apple")
}

/** The signed-in parent, as GET /v1/me returns it. */
@Serializable
data class Parent(val id: String, val name: String? = null, val email: String? = null)

/** A parent session: the bearer token for the API and the profile it belongs to. Stored only encrypted. */
@Serializable
data class Session(val token: String, val parent: Parent)

/**
 * The age signal sent with every parent sign-in. On Android it comes from Google Play Age Signals.
 * Unknown values are null and left out of the request. The API decides; the phone only blocks clear minors early.
 */
@Serializable
data class AgeRange(
    val source: String,
    val lower: Int? = null,
    val upper: Int? = null,
    /** Play Age Signals error code when the check failed (for example -1: not available in this country). */
    val error: Int? = null
) {
    /** True only when the whole range is under 18. */
    val isMinor: Boolean get() = upper != null && upper < ADULT_AGE

    companion object {
        const val SOURCE_GOOGLE_PLAY = "google_play"
        const val ADULT_AGE = 18

        fun unknown() = AgeRange(source = SOURCE_GOOGLE_PLAY)

        fun error(code: Int) = AgeRange(source = SOURCE_GOOGLE_PLAY, error = code)
    }
}

/** Wire body of POST /v1/auth/google and /v1/auth/apple. */
@Serializable
internal data class SignInRequest(
    @SerialName("id_token") val idToken: String,
    val nonce: String? = null,
    val name: String? = null,
    @SerialName("age_range") val ageRange: AgeRange
)

@Serializable
internal data class MeResponse(val parent: Parent)

@Serializable
internal data class ErrorBody(val error: String, val message: String? = null)
