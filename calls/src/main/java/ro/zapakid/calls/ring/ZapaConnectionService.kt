package ro.zapakid.calls.ring

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-managed Telecom connections, one per call. Answer, reject and disconnect from Telecom (Bluetooth, car,
 * watch) go to [CallRinger], which the app points at its call controller.
 */
class ZapaConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras
        val callId = extras.getString(TelecomCallSystem.EXTRA_CALL_ID).orEmpty()
        val title =
            ConversationTitle(
                extras.getString(TelecomCallSystem.EXTRA_TITLE).orEmpty(),
                extras.getInt(TelecomCallSystem.EXTRA_AVATAR),
                extras.getBoolean(TelecomCallSystem.EXTRA_GROUP)
            )
        return ZapaConnection(callId).apply {
            setRinging()
            connections[callId] = this
            CallNotifications.showIncoming(this@ZapaConnectionService, callId, title)
        }
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

        override fun onAnswer() {
            setActive()
            CallRinger.handler?.accept(callId)
        }

        override fun onReject() {
            finish(DisconnectCause.REJECTED)
            CallRinger.handler?.decline(callId)
        }

        override fun onDisconnect() {
            finish(DisconnectCause.LOCAL)
            CallRinger.handler?.hangup(callId)
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
    }
}

/** What Telecom and the ringing notification ask of the app's call controller. */
interface CallRingerHandler {
    fun accept(callId: String)

    fun decline(callId: String)

    fun hangup(callId: String)
}

object CallRinger {
    @Volatile
    var handler: CallRingerHandler? = null
}
