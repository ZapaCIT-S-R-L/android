package ro.zapakid.calls.api

import java.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.KeyEnvelope

/** One participant in the roster, in join order. */
data class Participant(val device: CallDevice, val tracks: List<String>, val sessionId: String?)

/** What the per-call event socket (`GET /v1/calls/{callId}/events`) tells this phone. */
sealed interface CallSignal {
    /** The call after every change: participants, the key epoch and its leader. */
    data class Roster(val participants: List<Participant>, val epoch: Int, val state: String = "active") : CallSignal

    /** A sealed call key for this device. */
    data class Key(val envelope: KeyEnvelope) : CallSignal

    data class Joined(val device: CallDevice) : CallSignal

    data class Left(val device: CallDevice) : CallSignal

    data class Declined(val account: String) : CallSignal

    /** Not sent by the calls service (it ends the call with `no_answer`); kept for the local ring timer. */
    data object RingTimeout : CallSignal

    /** `hung_up`, `no_answer`, `declined`, `max_duration` or `disconnected`; the server then closes the socket. */
    data class Ended(val reason: String) : CallSignal

    /** `invalid_message`, `not_participant`, `stale_epoch` (with the current [epoch]). */
    data class ServerError(val code: String, val epoch: Int? = null) : CallSignal

    /** The socket closed or failed; [code] is the WebSocket close code, or null after a network failure. */
    data class Disconnected(val code: Int?) : CallSignal
}

interface CallEventsConnection {
    /** Single consumer; buffered so nothing is lost before collection starts. */
    val signals: Flow<CallSignal>

    /** One `keys` message: this epoch's key sealed for every other participant device. */
    fun sendKeys(epoch: Int, envelopes: List<KeyEnvelope>): Boolean

    /** `{"type":"ping"}`; send every 30 s. */
    fun ping(): Boolean

    fun close()
}

/** Opens the per-call event socket. The chat token goes in the Authorization header, never in the URL. */
class CallEventsClient(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    private val tokens: ChatTokenProvider,
    /** This phone: incoming keys are addressed to it. */
    private val self: CallDevice
) {
    init {
        requireSecure(baseUrl)
    }

    suspend fun connect(callId: String): CallEventsConnection {
        val url = baseUrl.newBuilder().addPathSegment("calls").addPathSegment(callId).addPathSegment("events").build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer ${tokens.token()}")
                .build()
        val connection = Connection(SignalCodec(self))
        connection.socket = client.newWebSocket(request, connection.listener)
        return connection
    }

    private class Connection(private val codec: SignalCodec) : CallEventsConnection {
        lateinit var socket: WebSocket
        private val channel = Channel<CallSignal>(Channel.UNLIMITED)
        override val signals: Flow<CallSignal> = channel.receiveAsFlow()

        val listener =
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    codec.decode(text)?.let { channel.trySend(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    channel.trySend(CallSignal.Disconnected(code))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    channel.trySend(CallSignal.Disconnected(null))
                }
            }

        override fun sendKeys(epoch: Int, envelopes: List<KeyEnvelope>): Boolean =
            envelopes.isEmpty() || socket.send(SignalCodec.encodeKeys(epoch, envelopes))

        override fun ping(): Boolean = socket.send(SignalCodec.PING)

        override fun close() {
            socket.close(NORMAL_CLOSURE, null)
            channel.close()
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

/** JSON for the event socket. Unknown types and broken messages decode to null and are skipped. */
internal class SignalCodec(private val self: CallDevice) {
    @Serializable
    private data class Roster(val call: WireCall)

    @Serializable
    private data class Key(val epoch: Int, val fromAccount: String, val fromDevice: Int, val blob: String)

    @Serializable
    private data class Ended(val reason: String = "hung_up")

    @Serializable
    private data class Declined(val accountId: String)

    @Serializable
    private data class Problem(val code: String = "", val epoch: Int? = null)

    @Serializable
    private data class OutEnvelope(val toAccount: String, val toDevice: Int, val blob: String)

    @Serializable
    private data class OutKeys(val type: String, val epoch: Int, val envelopes: List<OutEnvelope>)

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "CyclomaticComplexMethod")
    fun decode(text: String): CallSignal? = try {
        val element = json.parseToJsonElement(text)
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "roster" -> {
                val call = json.decodeFromJsonElement(Roster.serializer(), element).call
                CallSignal.Roster(
                    call.participants.map {
                        Participant(CallDevice(it.accountId, it.deviceId.toString()), it.tracks, it.sessionId)
                    },
                    call.epoch,
                    call.state
                )
            }

            "key" -> {
                val key = json.decodeFromJsonElement(Key.serializer(), element)
                val from = CallDevice(key.fromAccount, key.fromDevice.toString())
                CallSignal.Key(KeyEnvelope(key.epoch, from, self, Base64.getDecoder().decode(key.blob)))
            }

            "joined" -> CallSignal.Joined(device(element))

            "left" -> CallSignal.Left(device(element))

            "declined" -> CallSignal.Declined(json.decodeFromJsonElement(Declined.serializer(), element).accountId)

            "ended" -> CallSignal.Ended(json.decodeFromJsonElement(Ended.serializer(), element).reason)

            "error" -> json.decodeFromJsonElement(Problem.serializer(), element).let {
                CallSignal.ServerError(it.code, it.epoch)
            }

            else -> null // keys_ack, pong, and anything newer
        }
    } catch (e: Exception) {
        null
    }

    private fun device(element: kotlinx.serialization.json.JsonElement): CallDevice =
        json.decodeFromJsonElement(WireDevice.serializer(), element).let {
            CallDevice(it.accountId, it.deviceId.toString())
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        const val PING = """{"type":"ping"}"""

        fun encodeKeys(epoch: Int, envelopes: List<KeyEnvelope>): String = json.encodeToString(
            OutKeys.serializer(),
            OutKeys(
                "keys",
                epoch,
                envelopes.map {
                    OutEnvelope(
                        it.toDevice.account,
                        it.toDevice.device.toInt(),
                        Base64.getEncoder().encodeToString(it.blob)
                    )
                }
            )
        )
    }
}
