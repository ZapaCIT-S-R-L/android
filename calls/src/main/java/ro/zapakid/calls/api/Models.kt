package ro.zapakid.calls.api

import kotlinx.serialization.Serializable

// Wire models of calls.zapakid.ro, field for field with zapakid-api services/calls/openapi.yaml. Unknown fields are
// ignored so the server can add some without breaking older apps.

@Serializable
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    override fun toString(): String = "IceServer(urls=$urls)"
}

@Serializable
data class WireDevice(val accountId: String, val deviceId: Int)

@Serializable
data class WireParticipant(
    val accountId: String,
    val deviceId: Int,
    val role: String = "parent",
    val sessionId: String? = null,
    val tracks: List<String> = emptyList(),
    val connected: Boolean = false
)

/** The `Call` schema: state, the current key epoch and leader, participants in join order. */
@Serializable
data class WireCall(
    val callId: String,
    val conversationId: String = "",
    val state: String = "ringing",
    val epoch: Int = 0,
    val keyLeader: WireDevice? = null,
    val participants: List<WireParticipant> = emptyList(),
    val declined: List<String> = emptyList(),
    val endReason: String? = null
)

@Serializable
internal data class CallGrant(val call: WireCall, val iceServers: List<IceServer> = emptyList())

/** What starting or joining a call returns. */
data class CallTicket(val callId: String, val iceServers: List<IceServer> = emptyList(), val epoch: Int = 0)

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
