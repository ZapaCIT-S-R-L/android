package ro.zapakid.calls.engine

import kotlinx.coroutines.flow.Flow
import ro.zapakid.calls.api.IceServer
import ro.zapakid.calls.api.Participant
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.KeyRing

/** What the media engine reports back to the call. */
sealed interface EngineEvent {
    /** Audio flows to and from the SFU. */
    data object Connected : EngineEvent

    /** ICE disconnected or failed; the call shows "reconnecting". */
    data object Lost : EngineEvent

    /** Who is talking right now (audio level above the speaking threshold). */
    data class Speaking(val devices: Set<CallDevice>) : EngineEvent
}

/**
 * The audio side of a call: one Realtime session, the microphone track published, every other participant's
 * track pulled. Frames are encrypted with the newest key in [KeyRing] before they leave the phone and decrypted
 * with the key named in each frame. The production engine is [WebRtcCallEngine]; tests and previews use a fake.
 */
interface CallEngine {
    val events: Flow<EngineEvent>

    suspend fun start(callId: String, self: CallDevice, iceServers: List<IceServer>, ring: KeyRing)

    /** The roster changed: pull new tracks, drop the ones that left. */
    suspend fun updateParticipants(participants: List<Participant>)

    /** A new epoch key is in the ring: start sending with it (receivers keep the older ones a while). */
    fun onKeyRotated(epoch: Int)

    fun setMuted(muted: Boolean)

    fun setSpeaker(on: Boolean)

    suspend fun stop()

    /**
     * Gives up the engine for good: its own scope and whatever it holds beyond one call. [stop] ends a call and
     * leaves the engine reusable, this does not. Default: nothing to give up.
     */
    fun release() = Unit
}
