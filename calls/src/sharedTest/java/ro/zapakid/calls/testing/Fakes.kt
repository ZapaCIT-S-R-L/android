package ro.zapakid.calls.testing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import ro.zapakid.calls.CallEventsSource
import ro.zapakid.calls.api.CallEventsConnection
import ro.zapakid.calls.api.CallSignal
import ro.zapakid.calls.api.CallTicket
import ro.zapakid.calls.api.CallsApi
import ro.zapakid.calls.api.IceServer
import ro.zapakid.calls.api.Participant
import ro.zapakid.calls.api.SessionDescription
import ro.zapakid.calls.api.TracksRequest
import ro.zapakid.calls.api.TracksResponse
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.CallKeySealer
import ro.zapakid.calls.crypto.CallKeySealerException
import ro.zapakid.calls.crypto.KeyEnvelope
import ro.zapakid.calls.crypto.KeyRing
import ro.zapakid.calls.engine.CallEngine
import ro.zapakid.calls.engine.EngineEvent
import ro.zapakid.calls.ring.Ringback

// Ids sort acc-ana.1 < acc-ion.3 < acc-me.2 < acc-mia.4: Ana leads whenever she is in the call.
val ME = CallDevice("acc-me", "2")
val ANA = CallDevice("acc-ana", "1")
val ION = CallDevice("acc-ion", "3")
val MIA = CallDevice("acc-mia", "4")

fun participant(device: CallDevice) =
    Participant(device, listOf("audio-${device.account}_${device.device}"), "sess-${device.device}")

/** Records calls; the call ID is "call-1" unless set. */
class FakeCallsApi(var callId: String = "call-1") : CallsApi {
    val calls = mutableListOf<String>()
    var failCreate: Exception? = null

    private val ticket get() = CallTicket(callId, listOf(IceServer(listOf("stun:127.0.0.1:3478"))))

    override suspend fun create(conversationId: String): CallTicket {
        calls += "create $conversationId"
        failCreate?.let { throw it }
        return ticket
    }

    override suspend fun join(callId: String): CallTicket = ticket.also { calls += "join $callId" }

    override suspend fun leave(callId: String) {
        calls += "leave $callId"
    }

    override suspend fun decline(callId: String) {
        calls += "decline $callId"
    }

    override suspend fun newSession(callId: String) = "sess-me"

    override suspend fun newTracks(callId: String, sessionId: String, request: TracksRequest) = TracksResponse()

    override suspend fun renegotiate(callId: String, sessionId: String, answer: SessionDescription) = Unit

    override suspend fun closeTracks(callId: String, sessionId: String, mids: List<String>, offer: SessionDescription) =
        TracksResponse()
}

/** The calls service's event socket, driven by the test. */
class FakeEvents :
    CallEventsSource,
    CallEventsConnection {
    private val channel = Channel<CallSignal>(Channel.UNLIMITED)
    override val signals: Flow<CallSignal> = channel.receiveAsFlow()
    val sentKeys = mutableListOf<KeyEnvelope>()
    var pings = 0

    /** The server's key epoch: up by one on every roster change, as on the calls service. */
    var epoch = 0
    var connectedTo: String? = null
    var closed = false

    override suspend fun connect(callId: String): CallEventsConnection = this.also { connectedTo = callId }

    fun push(signal: CallSignal) {
        channel.trySend(signal)
    }

    fun roster(vararg devices: CallDevice) = push(CallSignal.Roster(devices.map(::participant), ++epoch))

    override fun sendKeys(epoch: Int, envelopes: List<KeyEnvelope>): Boolean = sentKeys.addAll(envelopes).let { true }

    override fun ping(): Boolean = true.also { pings++ }

    override fun close() {
        closed = true
    }
}

/** Media engine without audio: the test says when media connects, drops or who speaks. */
class FakeCallEngine : CallEngine {
    private val flow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    override val events: Flow<EngineEvent> = flow
    var started = false
    var stopped = false
    var released = false
        private set
    var isMuted = false
        private set
    var speakerOn = false
        private set
    val rotations = mutableListOf<Int>()
    var participants: List<Participant> = emptyList()
    var ring: KeyRing? = null

    override suspend fun start(callId: String, self: CallDevice, iceServers: List<IceServer>, ring: KeyRing) {
        started = true
        this.ring = ring
    }

    override suspend fun updateParticipants(participants: List<Participant>) {
        this.participants = participants
    }

    override fun onKeyRotated(epoch: Int) {
        rotations += epoch
    }

    override fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    override fun setSpeaker(on: Boolean) {
        speakerOn = on
    }

    override suspend fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
    }

    fun emit(event: EngineEvent) {
        flow.tryEmit(event)
    }
}

/** The caller's ringback without audio: records the starts and stops the controller asks for. */
class FakeRingback : Ringback {
    val events = mutableListOf<String>()
    var playing = false
        private set

    override fun start() {
        if (playing) return
        playing = true
        events += "start"
    }

    override fun stop() {
        if (!playing) return
        playing = false
        events += "stop"
    }
}

/** Same rules as the libsignal sealer: bound to sender and recipient. Not encryption; tests only. */
class TestSealer(private val self: CallDevice) : CallKeySealer {
    override suspend fun seal(payload: ByteArray, toDevice: CallDevice) =
        "${toDevice.device}|${self.device}|".encodeToByteArray() + payload

    override suspend fun open(blob: ByteArray, fromDevice: CallDevice): ByteArray {
        val header = "${self.device}|${fromDevice.device}|".encodeToByteArray()
        if (!blob.copyOfRange(
                0,
                minOf(header.size, blob.size)
            ).contentEquals(header)
        ) {
            throw CallKeySealerException("wrong device")
        }
        return blob.copyOfRange(header.size, blob.size)
    }
}
