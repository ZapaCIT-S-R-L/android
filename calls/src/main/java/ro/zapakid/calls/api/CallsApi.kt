package ro.zapakid.calls.api

/** calls.zapakid.ro: call control and the Cloudflare Realtime proxy (the Realtime app secret stays on the server). */
interface CallsApi {
    /** Starts a call in a conversation; the service rings the other members. */
    suspend fun create(conversationId: String): CallTicket

    suspend fun join(callId: String): CallTicket

    suspend fun leave(callId: String)

    suspend fun decline(callId: String)

    /** A Realtime session for this phone in this call; the service only lets it touch its own session. */
    suspend fun newSession(callId: String): String

    suspend fun newTracks(callId: String, sessionId: String, request: TracksRequest): TracksResponse

    suspend fun renegotiate(callId: String, sessionId: String, answer: SessionDescription)

    /** Stops tracks; the answer completes the renegotiation. */
    suspend fun closeTracks(
        callId: String,
        sessionId: String,
        mids: List<String>,
        offer: SessionDescription
    ): TracksResponse
}
