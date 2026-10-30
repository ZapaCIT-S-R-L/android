package ro.zapakid.calls.ring

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The ring-ring the caller hears while an outgoing call is ringing. The tone is made on the caller's own phone and is
 * never sent as media, so the callee hears nothing.
 */
interface Ringback {
    /** Starts the tone; already playing does nothing. */
    fun start()

    /** Stops the tone and gives the audio back; nothing playing does nothing. */
    fun stop()

    /** No tone: tests, and phones where the audio could not be set up. */
    object Silent : Ringback {
        override fun start() = Unit

        override fun stop() = Unit
    }
}

/**
 * The platform ringback on the call's own stream ([AudioManager.STREAM_VOICE_CALL]), so it follows the call's route —
 * earpiece or speaker — and the call's volume rather than the media volume. A tone that cannot be created is dropped:
 * a missing ringback must never end a call.
 */
class ToneRingback(private val volume: Int = VOLUME) : Ringback {
    private var tone: ToneGenerator? = null

    override fun start() {
        if (tone != null) return
        val created = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, volume) }.getOrNull() ?: return
        tone = created
        runCatching { created.startTone(ToneGenerator.TONE_CDMA_NETWORK_USA_RINGBACK, RING_MILLIS) }
    }

    override fun stop() {
        val tone = this.tone ?: return
        this.tone = null
        runCatching {
            tone.stopTone()
            tone.release()
        }
    }

    private companion object {
        // ToneGenerator takes 0..100: loud enough to hear, under the call audio.
        const val VOLUME = 80

        // Longer than the ring timeout (CallTiming.ringMillis, 45 s), so one tone covers the whole ringing phase.
        const val RING_MILLIS = 60_000
    }
}
