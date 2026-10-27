package ro.zapakid.calls.api

import kotlinx.serialization.Serializable

// Wire models of calls.zapakid.ro, from the build brief. Kept field-for-field with services/calls/openapi.yaml
// once it lands; unknown fields are ignored so the server can add some without breaking older apps.

@Serializable
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    override fun toString(): String = "IceServer(urls=$urls)"
}

@Serializable
data class CallTicket(val callId: String, val iceServers: List<IceServer> = emptyList())

@Serializable
internal data class CreateCallRequest(val conversationId: String)

@Serializable
internal data class SessionResponse(val sessionId: String)

@Serializable
data class SessionDescription(val type: String, val sdp: String)

/** A track in a Realtime request: `local` publishes this phone's track, `remote` pulls another session's. */
@Serializable
data class TrackRef(
    val location: String? = null,
    val trackName: String,
    val mid: String? = null,
    val sessionId: String? = null
)

@Serializable
data class TracksRequest(val sessionDescription: SessionDescription? = null, val tracks: List<TrackRef>)

@Serializable
data class TracksResponse(
    val sessionDescription: SessionDescription? = null,
    val tracks: List<TrackRef> = emptyList(),
    val requiresImmediateRenegotiation: Boolean = false
)

@Serializable
internal data class RenegotiateRequest(val sessionDescription: SessionDescription)

@Serializable
internal data class CloseTracksRequest(
    val sessionDescription: SessionDescription,
    val tracks: List<Mid>,
    val force: Boolean = false
)

@Serializable
internal data class Mid(val mid: String)

@Serializable
internal data class ErrorBody(val error: ErrorDetail? = null)

@Serializable
internal data class ErrorDetail(val code: String? = null, val message: String? = null)

class CallsApiException(val status: Int, val code: String, message: String, val retryAfterSeconds: Long? = null) :
    Exception(message)

/**
 * The short-lived (≤ 15 min) ES256 chat token from the account service (`POST /v1/chat/token`), whose audience
 * includes calls.zapakid.ro. [refresh] fetches a new one after a 401.
 */
interface ChatTokenProvider {
    suspend fun token(): String

    suspend fun refresh(): String
}
