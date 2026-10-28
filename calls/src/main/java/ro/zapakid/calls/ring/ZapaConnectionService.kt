package ro.zapakid.calls.ring

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-managed Telecom connections, one per call. Answer, reject and disconnect from Telecom (Bluetooth, car,
 * watch) go to [CallRinger], which the app points at its call controller.
 */
class ZapaConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) ?: request.extras
        val callId = extras.getString(TelecomCallSystem.EXTRA_CALL_ID).orEmpty()
        val title =
            ConversationTitle(
                extras.getString(TelecomCallSystem.EXTRA_TITLE).orEmpty(),
                extras.getInt(TelecomCallSystem.EXTRA_AVATAR),
                extras.getBoolean(TelecomCallSystem.EXTRA_GROUP)
            )
        // Without notifications or the full-screen intent nobody would see it ring: reject it, log it as missed.
        if (!CallNotifications.canRing(this)) {
            CallRinger.send(callId, CallRinger.Action.MISSED)
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.REJECTED))
        }
        return ZapaConnection(callId).apply {
            setRinging()
            connections[callId] = this
            CallNotifications.showIncoming(this@ZapaConnectionService, callId, title)
        }
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        val extras = request.extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) ?: request.extras
        extras.getString(TelecomCallSystem.EXTRA_CALL_ID)?.let { CallRinger.send(it, CallRinger.Action.MISSED) }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val callId = request.extras.getString(TelecomCallSystem.EXTRA_CALL_ID).orEmpty()
        return ZapaConnection(callId).apply {
            setDialing()
            connections[callId] = this
        }
    }

    private class ZapaConnection(private val callId: String) : Connection() {
        init {
            connectionProperties = PROPERTY_SELF_MANAGED
            audioModeIsVoip = true
            connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD
        }

        override fun onShowIncomingCallUi() = Unit // the full-screen notification is already up

        // Telecom hears "active" only once the call controller has the answer (CallRinger.onAccepted).
        override fun onAnswer() = CallRinger.send(callId, CallRinger.Action.ACCEPT)

        override fun onReject() {
            finish(DisconnectCause.REJECTED)
            CallRinger.send(callId, CallRinger.Action.DECLINE)
        }

        override fun onDisconnect() {
            finish(DisconnectCause.LOCAL)
            CallRinger.send(callId, CallRinger.Action.HANGUP)
        }

        fun finish(cause: Int) {
            setDisconnected(DisconnectCause(cause))
            destroy()
            connections.remove(callId)
        }
    }

    companion object {
        private val connections = ConcurrentHashMap<String, Connection>()

        fun end(callId: String) {
            (connections[callId] as? ZapaConnection)?.finish(DisconnectCause.REMOTE)
        }

        fun active(callId: String) {
            connections[callId]?.setActive()
        }

        /** An answer no call controller took: end it with an error rather than leave Telecom thinking it's live. */
        fun fail(callId: String) {
            (connections[callId] as? ZapaConnection)?.finish(DisconnectCause.ERROR)
        }
    }
}
