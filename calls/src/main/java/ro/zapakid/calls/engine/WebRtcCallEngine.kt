package ro.zapakid.calls.engine

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyDerivationAlgorithm
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription as RtcDescription
import org.webrtc.audio.JavaAudioDeviceModule
import ro.zapakid.calls.api.CallsApi
import ro.zapakid.calls.api.IceServer
import ro.zapakid.calls.api.Participant
import ro.zapakid.calls.api.SessionDescription
import ro.zapakid.calls.api.TrackRef
import ro.zapakid.calls.api.TracksRequest
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.KeyRing

/**
 * The production engine: one PeerConnection to Cloudflare Realtime through the calls service's proxy. The
 * microphone track is published once; every other participant's track is pulled into the same session. A
 * [FrameCryptor] (AES-GCM) sits on the sender and on every receiver, so the SFU only ever sees ciphertext; frames
 * are dropped, never sent clear, until the first key is in place.
 */
@Suppress("TooManyFunctions")
class WebRtcCallEngine(private val context: Context, private val api: CallsApi) : CallEngine {
    private val flow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<EngineEvent> = flow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val audio = context.getSystemService(AudioManager::class.java)

    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var mic: AudioTrack? = null
    private var keys: FrameCryptorKeyProvider? = null
    private var senderCryptor: FrameCryptor? = null
    private var ring: KeyRing? = null
    private var callId = ""
    private var sessionId = ""
    private var self: CallDevice? = null
    private var levels: Job? = null

    /** trackName → (owner, mid, cryptor) for every pulled track. */
    private val pulled = mutableMapOf<String, Pulled>()

    private class Pulled(val device: CallDevice, val mid: String, val cryptor: FrameCryptor?)

    override suspend fun start(callId: String, self: CallDevice, iceServers: List<IceServer>, ring: KeyRing) =
        lock.withLock {
            this.callId = callId
            this.self = self
            this.ring = ring
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val factory = createFactory().also { factory = it }
            val config =
                PeerConnection.RTCConfiguration(
                    iceServers.map {
                        PeerConnection.IceServer
                            .builder(it.urls)
                            .apply {
                                it.username?.let(::setUsername)
                                it.credential?.let(::setPassword)
                            }.createIceServer()
                    }
                ).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }
            val peer = checkNotNull(factory.createPeerConnection(config, observer)) { "no peer connection" }
            this.peer = peer
            val source = factory.createAudioSource(MediaConstraints())
            val mic = factory.createAudioTrack(MIC, source).also { mic = it }
            val transceiver =
                peer.addTransceiver(
                    mic,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
            val keys = createKeyProvider().also { keys = it }
            senderCryptor =
                FrameCryptorFactory
                    .createFrameCryptorForRtpSender(
                        factory,
                        transceiver.sender,
                        id(self),
                        FrameCryptorAlgorithm.AES_GCM,
                        keys
                    )
                    .apply { setEnabled(true) }
            ring.current?.let { install(it.epoch) }

            sessionId = api.newSession(callId)
            val offer = peer.create(offer = true)
            peer.setLocal(offer)
            val answer =
                api.newTracks(
                    callId,
                    sessionId,
                    TracksRequest(
                        sessionDescription = SessionDescription("offer", offer.description),
                        tracks = listOf(
                            TrackRef(location = "local", mid = transceiver.mid, trackName = trackName(self))
                        )
                    )
                )
            peer.setRemote(RtcDescription(RtcDescription.Type.ANSWER, checkNotNull(answer.sessionDescription).sdp))
            levels = scope.launch { pollLevels() }
        }

    override suspend fun updateParticipants(participants: List<Participant>) = lock.withLock {
        val peer = peer ?: return@withLock
        val factory = factory ?: return@withLock
        val wanted = participants.flatMap { p -> p.tracks.map { it to p } }.toMap()
        val gone = pulled.keys - wanted.keys
        if (gone.isNotEmpty()) closeTracks(peer, gone)
        val fresh = wanted.filterKeys { it !in pulled }.filterValues { it.sessionId != null }
        if (fresh.isEmpty()) return@withLock
        val response =
            api.newTracks(
                callId,
                sessionId,
                TracksRequest(
                    tracks = fresh.map { (name, p) ->
                        TrackRef(location = "remote", trackName = name, sessionId = p.sessionId)
                    }
                )
            )
        response.sessionDescription?.takeIf { response.requiresImmediateRenegotiation }?.let {
            peer.setRemote(RtcDescription(RtcDescription.Type.OFFER, it.sdp))
            val answer = peer.create(offer = false)
            peer.setLocal(answer)
            api.renegotiate(callId, sessionId, SessionDescription("answer", answer.description))
        }
        for (track in response.tracks) {
            val owner = fresh[track.trackName]?.device
            val mid = track.mid
            if (owner != null && mid != null) {
                val receiver = peer.transceivers.firstOrNull { it.mid == mid }?.receiver
                pulled[track.trackName] = Pulled(owner, mid, receiver?.let { cryptorFor(factory, it, owner) })
            }
        }
    }

    private suspend fun closeTracks(peer: PeerConnection, names: Set<String>) {
        val mids = names.mapNotNull { pulled[it]?.mid }
        names.forEach { pulled.remove(it)?.cryptor?.dispose() }
        peer.transceivers.filter { it.mid in mids }.forEach { it.stop() }
        val offer = peer.create(offer = true)
        peer.setLocal(offer)
        val answer = api.closeTracks(callId, sessionId, mids, SessionDescription("offer", offer.description))
        answer.sessionDescription?.let { peer.setRemote(RtcDescription(RtcDescription.Type.ANSWER, it.sdp)) }
    }

    private fun cryptorFor(factory: PeerConnectionFactory, receiver: RtpReceiver, owner: CallDevice): FrameCryptor =
        FrameCryptorFactory
            .createFrameCryptorForRtpReceiver(factory, receiver, id(owner), FrameCryptorAlgorithm.AES_GCM, keys)
            .apply { setEnabled(true) }

    override fun onKeyRotated(epoch: Int) {
        scope.launch { lock.withLock { install(epoch) } }
    }

    /** The shared key for this epoch goes into the native key ring; the sender switches to it. */
    private fun install(epoch: Int) {
        val key = ring?.get(epoch) ?: return
        val index = KeyRing.indexOf(epoch)
        val bytes = key.bytes()
        try {
            keys?.setSharedKey(index, bytes)
        } finally {
            bytes.fill(0)
        }
        senderCryptor?.keyIndex = index
    }

    override fun setMuted(muted: Boolean) {
        mic?.setEnabled(!muted)
    }

    @Suppress("DEPRECATION")
    override fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                audio.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(audio::setCommunicationDevice)
            } else {
                audio.clearCommunicationDevice()
            }
        } else {
            audio.isSpeakerphoneOn = on
        }
    }

    override suspend fun stop() = lock.withLock {
        levels?.cancel()
        pulled.values.forEach { it.cryptor?.dispose() }
        pulled.clear()
        senderCryptor?.dispose()
        senderCryptor = null
        peer?.close()
        peer?.dispose()
        peer = null
        mic = null
        keys?.dispose()
        keys = null
        factory?.dispose()
        factory = null
        setSpeaker(false)
        audio.mode = AudioManager.MODE_NORMAL
    }

    /** Releases the engine for good (the app's process ends or calls are switched off). */
    override fun release() = scope.cancel()

    private fun createFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val adm =
            JavaAudioDeviceModule
                .builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
        return PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory().also {
            adm.release()
        }
    }

    private fun createKeyProvider(): FrameCryptorKeyProvider = keyProvider()

    internal companion object {
        const val MIC = "mic"
        const val RATCHET_SALT = "ZapaKid call frame key v1" // the same as iOS
        const val MAGIC_BYTES = 16
        const val KEY_BITS = 256
        const val FAILURE_TOLERANCE = -1
        const val LEVEL_POLL_MILLIS = 300L
        const val SPEAKING_LEVEL = 0.05

        fun id(device: CallDevice) = device.id

        /** `audio-<accountId>_<deviceId>`: the calls API allows only A-Za-z0-9_- in track names. */
        fun trackName(device: CallDevice) = "audio-${device.account}_${device.device}"

        /**
         * Shared-key mode with a ring of [KeyRing.SIZE]. The "unencrypted magic bytes" let frames that start with
         * them pass undecrypted; a fresh random value per call means no injected frame can ever match.
         */
        fun keyProvider(): FrameCryptorKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
            true,
            RATCHET_SALT.encodeToByteArray(),
            0,
            ByteArray(MAGIC_BYTES).also(SecureRandom()::nextBytes),
            FAILURE_TOLERANCE,
            KeyRing.SIZE,
            true,
            FrameCryptorKeyDerivationAlgorithm.HKDF,
            KEY_BITS
        )
    }

    private suspend fun pollLevels() {
        while (scope.isActive) {
            delay(LEVEL_POLL_MILLIS)
            peer?.let { flow.tryEmit(EngineEvent.Speaking(speaking(it))) }
        }
    }

    /** Who is above the speaking level: this phone's microphone (media-source) and each pulled track. */
    private suspend fun speaking(peer: PeerConnection): Set<CallDevice> {
        val report = suspendCancellableCoroutine { cont -> peer.getStats { cont.resume(it) } }
        return report.statsMap.values
            .filter { ((it.members["audioLevel"] as? Number)?.toDouble() ?: 0.0) >= SPEAKING_LEVEL }
            .mapNotNull { stats ->
                when (stats.type) {
                    "media-source" -> self
                    "inbound-rtp" -> pulled.values.firstOrNull { it.mid == stats.members["mid"] }?.device
                    else -> null
                }
            }.toSet()
    }

    private val observer =
        object : PeerConnection.Observer {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                        flow.tryEmit(EngineEvent.Connected)

                    PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED ->
                        flow.tryEmit(EngineEvent.Lost)

                    else -> Unit
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

            override fun onIceCandidate(candidate: IceCandidate) = Unit

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit
        }
}

private suspend fun PeerConnection.create(offer: Boolean): RtcDescription = suspendCancellableCoroutine { cont ->
    val observer =
        object : SdpObserver {
            override fun onCreateSuccess(description: RtcDescription) = cont.resume(description)

            override fun onCreateFailure(error: String?) =
                cont.resumeWithException(IllegalStateException("SDP: $error"))

            override fun onSetSuccess() = Unit

            override fun onSetFailure(error: String?) = Unit
        }
    if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
}

private suspend fun PeerConnection.setLocal(description: RtcDescription) = suspendCancellableCoroutine { cont ->
    setLocalDescription(setObserver(cont), description)
}

private suspend fun PeerConnection.setRemote(description: RtcDescription) = suspendCancellableCoroutine { cont ->
    setRemoteDescription(setObserver(cont), description)
}

private fun setObserver(cont: kotlinx.coroutines.CancellableContinuation<Unit>) = object : SdpObserver {
    override fun onCreateSuccess(description: RtcDescription) = Unit

    override fun onCreateFailure(error: String?) = Unit

    override fun onSetSuccess() = cont.resume(Unit)

    override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException("SDP: $error"))
}
