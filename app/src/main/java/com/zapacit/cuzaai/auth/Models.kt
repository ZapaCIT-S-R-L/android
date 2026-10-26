package com.zapacit.cuzaai.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The two sign-in methods for parents. [path] is the API endpoint: POST /v1/auth/{path}. */
enum class Provider(val path: String) {
    GOOGLE("google"),
    APPLE("apple")
}

/** The signed-in parent (the fields the app uses from the API's Parent schema). */
@Serializable
data class Parent(val id: String, val email: String? = null, val displayName: String? = null)

/** A parent session: the opaque bearer token, its expiry (ISO 8601) and the profile. Stored only encrypted. */
@Serializable
data class Session(val token: String, val expiresAt: String, val parent: Parent)

@Serializable
enum class AgeAvailability {
    @SerialName("shared")
    SHARED,

    @SerialName("declined")
    DECLINED,

    @SerialName("unavailable")
    UNAVAILABLE
}

/**
 * The phone's age signal, sent with every parent sign-in (API schema AgeRange). On Android: Google Play Age Signals.
 * [lowerBound] and [upperBound] are required by the API and sent as null when open or unknown.
 */
@Serializable
data class AgeRange(
    val availability: AgeAvailability,
    val source: String,
    val lowerBound: Int?,
    val upperBound: Int?,
    /** The platform's raw status, letters and underscores only. Left out when null. */
    val platformStatus: String? = null
) {
    /** The API's rule: a shared range is adult only when lowerBound >= 18. The phone blocks these early. */
    val isMinor: Boolean get() = availability == AgeAvailability.SHARED && (lowerBound ?: 0) < ADULT_AGE

    companion object {
        const val SOURCE_GOOGLE_PLAY = "google_play_age_signals"
        const val ADULT_AGE = 18

        fun shared(lower: Int?, upper: Int?, platformStatus: String?) =
            AgeRange(AgeAvailability.SHARED, SOURCE_GOOGLE_PLAY, lower, upper, platformStatus)

        fun unavailable(platformStatus: String?) =
            AgeRange(AgeAvailability.UNAVAILABLE, SOURCE_GOOGLE_PLAY, null, null, platformStatus)

        fun declined() = AgeRange(AgeAvailability.DECLINED, SOURCE_GOOGLE_PLAY, null, null)
    }
}

@Serializable
internal data class NonceResponse(val nonce: String)

@Serializable
internal data class GoogleSignInRequest(val idToken: String, val nonce: String, val ageRange: AgeRange)

@Serializable
internal data class AppleWebStartRequest(val codeChallenge: String, val codeChallengeMethod: String)

@Serializable
internal data class AppleWebStartResponse(val state: String)

@Serializable
internal data class AppleExchangeRequest(
    val code: String,
    val codeVerifier: String,
    val nonce: String,
    val ageRange: AgeRange
)

@Serializable
internal data class SessionToken(val token: String, val expiresAt: String)

@Serializable
internal data class SignInResponse(val session: SessionToken, val parent: Parent, val newAccount: Boolean = false)

@Serializable
internal data class ErrorBody(val error: ErrorDetail)

@Serializable
internal data class ErrorDetail(val code: String, val message: String? = null)
