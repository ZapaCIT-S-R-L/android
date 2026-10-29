package ro.zapakid.calls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.zapakid.calls.api.CallEventsConnection
import ro.zapakid.calls.api.CallSignal
import ro.zapakid.calls.api.CallTicket
import ro.zapakid.calls.api.CallsApi
import ro.zapakid.calls.api.Participant
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.CallKeyManager
import ro.zapakid.calls.crypto.CallKeySealer
import ro.zapakid.calls.crypto.KeyRing
import ro.zapakid.calls.engine.CallEngine
import ro.zapakid.calls.engine.EngineEvent

/** Opens the per-call event socket; [ro.zapakid.calls.api.CallEventsClient] in the app, a fake in tests. */
fun interface CallEventsSource {
    suspend fun connect(callId: String): CallEventsConnection
}

/** Names and robot avatars for the accounts in a call (from the family and contacts). */
interface CallPeople {
    fun name(account: String): String

    /** Index into the robot avatars (0 until [ro.zapakid.calls.ui.RobotAvatars.COUNT]). */
    fun avatar(account: String): Int

    object Default : CallPeople {
        override fun name(account: String) = account

        override fun avatar(account: String) = Math.floorMod(account.hashCode(), AVATARS)

        private const val AVATARS = 8
    }
}

data class CallPerson(val device: CallDevice, val name: String, val avatar: Int, val speaking: Boolean = false)

/** Everything the call screens draw. */
data class CallUiState(
    val phase: CallState = CallState.Idle,
    val callId: String? = null,
    val conversationId: String? = null,
    val title: String = "",
    val titleAvatar: Int = 0,
    val direction: CallDirection = CallDirection.Outgoing,
    val group: Boolean = false,
    val people: List<CallPerson> = emptyList(),
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val selfSpeaking: Boolean = false,
    /** True once a call key is in place: audio leaves the phone only encrypted. */
    val encrypted: Boolean = false,
    val connectedAtMillis: Long? = null
)

data class CallTiming(val ringMillis: Long = 45_000, val reconnectMillis: Long = 20_000)

/**
 * Runs one call at a time: the calls API, the event socket, key rotation and the media engine, folded into one
 * [CallUiState]. Call its methods from the main thread (or the [scope]'s single thread).
 */
@Suppress("TooManyFunctions", "LongParameterList") // constructor injection: every collaborator is swappable in tests
class CallController(
    private val api: CallsApi,
    private val events: CallEventsSource,
    private val engine: CallEngine,
    private val sealer: CallKeySealer,
    private val self: CallDevice,
    private val log: CallLog,
    private val scope: CoroutineScope,
    private val people: CallPeople = CallPeople.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timing: CallTiming = CallTiming()
) {
    private val mutableState = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = mutableState

    private var call: Job? = null
    private var timer: Job? = null
    private var connection: CallEventsConnection? = null
    private var keys: CallKeyManager? = null
    private var ring = KeyRing()
    private var mediaUp = false
    private var joined = false
    private var mediaStarted = false
    private var declines = 0
    private var expectedOthers = 1
    private var hadOthers = false
    private var startedAt = 0L
    private var finished = false

    private val busy: Boolean
        get() = mutableState.value.phase.let { it != CallState.Idle && it !is CallState.Ended }

    /** Calls a conversation. [others] is how many other members ring (1 for a 1:1 call). */
    fun startCall(conversationId: String, title: String, others: Int = 1, avatar: Int = 0): Boolean {
        if (busy) return false
        begin(CallUiState(conversationId = conversationId, title = title, titleAvatar = avatar, group = others > 1))
        expectedOthers = others
        dispatch(CallEvent.Dial)
        call =
            scope.launch {
                guarded {
                    val ticket = api.create(conversationId)
                    mutableState.update { it.copy(callId = ticket.callId) }
                    joined = true
                    listen(ticket.callId)
                    startMedia(ticket)
                }
            }
        ringTimer()
        return true
    }

    /** The push said someone calls; the phone rings until [accept], [decline] or the ring timeout. */
    fun incoming(
        callId: String,
        conversationId: String?,
        title: String,
        group: Boolean = false,
        avatar: Int = 0
    ): Boolean {
        if (busy) return false
        begin(
            CallUiState(
                callId = callId,
                conversationId = conversationId,
                title = title,
                titleAvatar = avatar,
                group = group,
                direction = CallDirection.Incoming
            )
        )
        dispatch(CallEvent.IncomingRing)
        call = scope.launch { guarded { listen(callId) } }
        ringTimer()
        return true
    }

    fun accept() {
        val callId = mutableState.value.callId ?: return
        if (mutableState.value.phase != CallState.Ringing) return
        dispatch(CallEvent.Accept)
        scope.launch {
            guarded {
                val ticket = api.join(callId)
                joined = true
                startMedia(ticket)
            }
        }
    }

    fun decline() {
        val callId = mutableState.value.callId ?: return
        if (mutableState.value.phase != CallState.Ringing) return
        scope.launch { runCatchingApi { api.decline(callId) } }
        dispatch(CallEvent.Decline)
    }

    fun hangup() = dispatch(CallEvent.Hangup)

    fun toggleMute() {
        val muted = !mutableState.value.muted
        engine.setMuted(muted)
        mutableState.update { it.copy(muted = muted, selfSpeaking = false) }
    }

    fun toggleSpeaker() {
        val on = !mutableState.value.speaker
        engine.setSpeaker(on)
        mutableState.update { it.copy(speaker = on) }
    }

    /** Leaves the ended screen. */
    fun dismiss() = dispatch(CallEvent.Reset)

    private fun begin(initial: CallUiState) {
        ring = KeyRing()
        keys = null
        connection = null
        mediaUp = false
        joined = false
        mediaStarted = false
        declines = 0
        hadOthers = false
        finished = false
        startedAt = clock()
        mutableState.value = initial
    }

    private suspend fun listen(callId: String) {
        keys = CallKeyManager(callId, self, sealer, ring)
        val socket = events.connect(callId)
        connection = socket
        scope.launch { socket.signals.collect { guarded { onSignal(it) } } }
        scope.launch {
            while (!finished) {
                delay(PING_MILLIS)
                if (!finished) socket.ping()
            }
        }
    }

    private suspend fun startMedia(ticket: CallTicket) {
        if (mediaStarted || finished) return
        mediaStarted = true
        scope.launch { engine.events.collect(::onEngine) }
        engine.start(ticket.callId, self, ticket.iceServers, ring)
    }

    private suspend fun onSignal(signal: CallSignal) {
        when (signal) {
            is CallSignal.Roster -> onRoster(signal.participants, signal.epoch, signal.keyLeader)

            is CallSignal.Key ->
                if (keys?.onEnvelope(signal.envelope) == true) {
                    engine.onKeyRotated(signal.envelope.epoch)
                    keyChanged()
                }

            is CallSignal.Declined -> if (++declines >= expectedOthers) dispatch(CallEvent.RemoteDeclined)

            is CallSignal.ServerError -> Unit

            // stale_epoch: the next roster brings the current epoch

            CallSignal.RingTimeout -> dispatch(CallEvent.RingTimeout)

            is CallSignal.Ended -> dispatch(endEvent(signal.reason))

            is CallSignal.Disconnected -> if (busy) lost()

            is CallSignal.Joined, is CallSignal.Left -> Unit // a roster update follows
        }
    }

    /** The calls service's end reasons as call events (`no_answer` while ringing is the ring timeout). */
    private fun endEvent(reason: String): CallEvent = when (reason) {
        "no_answer" -> CallEvent.RingTimeout
        "declined" -> CallEvent.RemoteDeclined
        else -> CallEvent.RemoteEnded
    }

    private suspend fun onRoster(participants: List<Participant>, epoch: Int, keyLeader: CallDevice?) {
        val devices = participants.map { it.device }
        val others = participants.filter { it.device != self }
        mutableState.update { state ->
            state.copy(
                people = others.map {
                    CallPerson(it.device, people.name(it.device.account), people.avatar(it.device.account))
                }
            )
        }
        if (self !in devices) return
        val manager = keys ?: return
        val before = manager.currentEpoch
        val envelopes = manager.onRoster(devices, serverEpoch = epoch, keyLeader = keyLeader)
        envelopes.firstOrNull()?.let { connection?.sendKeys(it.epoch, envelopes) }
        if (manager.currentEpoch != before) {
            engine.onKeyRotated(manager.currentEpoch)
            keyChanged()
        }
        if (mediaStarted) engine.updateParticipants(others)
        if (others.isNotEmpty()) {
            hadOthers = true
            dispatch(CallEvent.RemoteJoined)
        } else if (hadOthers) {
            dispatch(CallEvent.OthersLeft)
        }
        connectedIfReady()
    }

    private fun keyChanged() {
        mutableState.update { it.copy(encrypted = ring.current != null) }
        connectedIfReady()
    }

    private fun onEngine(event: EngineEvent) {
        when (event) {
            EngineEvent.Connected -> {
                mediaUp = true
                connectedIfReady()
            }

            EngineEvent.Lost -> {
                mediaUp = false
                lost()
            }

            is EngineEvent.Speaking ->
                mutableState.update { state ->
                    state.copy(
                        people = state.people.map { it.copy(speaking = it.device in event.devices) },
                        selfSpeaking = !state.muted && self in event.devices
                    )
                }
        }
    }

    /** In call only with audio flowing and a key in place: nothing is ever sent unencrypted. */
    private fun connectedIfReady() {
        val waiting = mutableState.value.phase.let { it == CallState.Connecting || it == CallState.Reconnecting }
        if (waiting && mediaUp && keys?.hasCurrentKey == true) {
            dispatch(CallEvent.MediaConnected)
            mutableState.update { it.copy(connectedAtMillis = it.connectedAtMillis ?: clock()) }
        }
    }

    private fun lost() {
        if (mutableState.value.phase != CallState.InCall) return
        dispatch(CallEvent.MediaLost)
        timer?.cancel()
        timer =
            scope.launch {
                delay(timing.reconnectMillis)
                dispatch(CallEvent.ReconnectTimeout)
            }
    }

    private fun ringTimer() {
        timer?.cancel()
        timer =
            scope.launch {
                delay(timing.ringMillis)
                dispatch(CallEvent.RingTimeout)
            }
    }

    private fun dispatch(event: CallEvent) {
        val before = mutableState.value.phase
        val after = CallStateMachine.reduce(before, event)
        if (after == before) return
        mutableState.update { it.copy(phase = after) }
        if (after is CallState.Ended) finish(after.reason)
        if (after == CallState.Idle) mutableState.value = CallUiState()
    }

    private fun finish(reason: EndReason) {
        if (finished) return
        finished = true
        val state = mutableState.value
        val wasJoined = joined
        val socket = connection
        timer?.cancel()
        call?.cancel()
        keys?.close()
        ring.clear()
        mutableState.update {
            it.copy(encrypted = false, people = it.people.map { p -> p.copy(speaking = false) }, selfSpeaking = false)
        }
        log.add(
            CallLogEntry(
                callId = state.callId ?: "",
                conversationId = state.conversationId,
                title = state.title,
                direction = state.direction,
                reason = reason,
                startedAtMillis = startedAt,
                durationSeconds = state.connectedAtMillis?.let { (clock() - it) / MILLIS } ?: 0,
                group = state.group
            )
        )
        scope.launch {
            withContext(NonCancellable) {
                socket?.close()
                if (mediaStarted) runCatchingApi { engine.stop() }
                val callId = state.callId
                if (wasJoined && callId != null) runCatchingApi { api.leave(callId) }
            }
        }
    }

    /** Any failure while setting up or running the call ends it as failed; cancellation passes through. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            dispatch(CallEvent.Failed)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun runCatchingApi(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
        }
    }

    /** Class name of the last failure (no message: messages may carry server text), for diagnostics. */
    var lastError: String? = null
        private set

    private companion object {
        const val MILLIS = 1000L
        const val PING_MILLIS = 30_000L
    }
}
