package ro.zapakid.calls.api

import java.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.SerialName
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

/** One participant in the roster, in join order (the first one is the key leader). */
data class Participant(val device: CallDevice, val tracks: List<String>, val sessionId: String?)

/** What the per-call event socket (`GET /v1/calls/{id}/events`) tells this phone. */
sealed interface CallSignal {
    data class Roster(val participants: List<Participant>) : CallSignal

    data class Key(val envelope: KeyEnvelope) : CallSignal

    data class Joined(val device: CallDevice) : CallSignal

    data class Left(val device: CallDevice) : CallSignal

    data class Declined(val device: CallDevice) : CallSignal

    /** 45 s and nobody answered. */
    data object RingTimeout : CallSignal

    data class Ended(val reason: String) : CallSignal

    /** The socket closed or failed; [code] is the WebSocket close code, or null after a network failure. */
    data class Disconnected(val code: Int?) : CallSignal
}

interface CallEventsConnection {
    /** Single consumer; buffered so nothing is lost before collection starts. */
    val signals: Flow<CallSignal>

    fun sendKey(envelope: KeyEnvelope): Boolean

    fun close()
}

/** Opens the per-call event socket. The chat token goes in the Authorization header, never in the URL. */
class CallEventsClient(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    private val tokens: ChatTokenProvider
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
        val connection = Connection()
        connection.socket = client.newWebSocket(request, connection.listener)
        return connection
    }

    private class Connection : CallEventsConnection {
        lateinit var socket: WebSocket
        private val channel = Channel<CallSignal>(Channel.UNLIMITED)
        override val signals: Flow<CallSignal> = channel.receiveAsFlow()

        val listener =
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    SignalCodec.decode(text)?.let { channel.trySend(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    channel.trySend(CallSignal.Disconnected(code))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    channel.trySend(CallSignal.Disconnected(null))
                }
            }

        override fun sendKey(envelope: KeyEnvelope): Boolean = socket.send(SignalCodec.encodeKey(envelope))

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
internal object SignalCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Device(val account: String, val device: String) {
        fun toDevice() = CallDevice(account, device)
    }

    @Serializable
    private data class WireParticipant(
        val account: String,
        val device: String,
        val tracks: List<String> = emptyList(),
        val sessionId: String? = null
    )

    @Serializable
    private data class Roster(val participants: List<WireParticipant>)

    @Serializable
    private data class Key(val epoch: Int, val from: Device, val to: Device, val blob: String)

    @Serializable
    private data class Ended(val reason: String = "ended")

    @Serializable
    private data class OutgoingKey(
        @SerialName("type") val type: String,
        val epoch: Int,
        val toDevice: Device,
        val blob: String
    )

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun decode(text: String): CallSignal? = try {
        val element = json.parseToJsonElement(text)
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "roster" ->
                CallSignal.Roster(
                    json.decodeFromJsonElement(Roster.serializer(), element).participants.map {
                        Participant(CallDevice(it.account, it.device), it.tracks, it.sessionId)
                    }
                )

            "key" -> {
                val key = json.decodeFromJsonElement(Key.serializer(), element)
                CallSignal.Key(
                    KeyEnvelope(key.epoch, key.from.toDevice(), key.to.toDevice(), Base64.getDecoder().decode(key.blob))
                )
            }

            "joined" -> CallSignal.Joined(json.decodeFromJsonElement(Device.serializer(), element).toDevice())

            "left" -> CallSignal.Left(json.decodeFromJsonElement(Device.serializer(), element).toDevice())

            "declined" -> CallSignal.Declined(json.decodeFromJsonElement(Device.serializer(), element).toDevice())

            "ring_timeout" -> CallSignal.RingTimeout

            "ended" -> CallSignal.Ended(json.decodeFromJsonElement(Ended.serializer(), element).reason)

            else -> null
        }
    } catch (e: Exception) {
        null
    }

    fun encodeKey(envelope: KeyEnvelope): String = json.encodeToString(
        OutgoingKey.serializer(),
        OutgoingKey(
            "key",
            envelope.epoch,
            Device(envelope.toDevice.account, envelope.toDevice.device),
            Base64.getEncoder().encodeToString(envelope.blob)
        )
    )
}
