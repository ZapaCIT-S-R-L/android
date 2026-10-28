package ro.zapakid.calls.ring

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/**
 * [CallSystem] over Android Telecom with a self-managed phone account: other calls are put on hold correctly,
 * Bluetooth headsets and car kits get the call, and Do Not Disturb rules apply. The ringing UI itself is ours
 * (a full-screen notification, see [CallNotifications]).
 */
class TelecomCallSystem(
    private val context: Context,
    private val label: CharSequence = context.applicationInfo.loadLabel(context.packageManager)
) : CallSystem {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val handle = PhoneAccountHandle(ComponentName(context, ZapaConnectionService::class.java), ACCOUNT_ID)

    @Volatile
    private var registered = false

    /** Registers the phone account; idempotent, and done before every incoming call (a push may start the process). */
    fun register() {
        if (registered) return
        val account =
            PhoneAccount
                .builder(handle, label)
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(SCHEME))
                .build()
        telecom.registerPhoneAccount(account)
        registered = true
    }

    override fun reportIncoming(callId: String, title: ConversationTitle) = IncomingRouting.report(
        canRing = CallNotifications.canRing(context),
        telecomAllowed = allowed(),
        ways =
            object : IncomingRouting.Ways {
                override fun register() = this@TelecomCallSystem.register()

                override fun addToTelecom() = addIncoming(callId, title)

                override fun showNotification() = CallNotifications.showIncoming(context, callId, title)

                override fun missed() = CallRinger.send(callId, CallRinger.Action.MISSED)
            }
    )

    private fun addIncoming(callId: String, title: ConversationTitle) {
        val extras =
            Bundle().apply {
                putString(EXTRA_CALL_ID, callId)
                putString(EXTRA_TITLE, title.name)
                putInt(EXTRA_AVATAR, title.avatar)
                putBoolean(EXTRA_GROUP, title.group)
            }
        telecom.addNewIncomingCall(
            handle,
            Bundle().apply {
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, extras)
            }
        )
    }

    override fun reportOutgoing(callId: String, title: ConversationTitle) {
        if (!allowed()) return
        register()
        val extras =
            Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                putBundle(
                    TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                    Bundle().apply {
                        putString(EXTRA_CALL_ID, callId)
                        putString(EXTRA_TITLE, title.name)
                    }
                )
            }
        @Suppress("MissingPermission") // MANAGE_OWN_CALLS is a normal permission, granted at install.
        telecom.placeCall(android.net.Uri.fromParts(SCHEME, callId, null), extras)
    }

    override fun reportEnded(callId: String) {
        ZapaConnectionService.end(callId)
        CallNotifications.cancel(context, callId)
    }

    private fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.MANAGE_OWN_CALLS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACCOUNT_ID = "zapakid-calls"
        const val SCHEME = "zapakid"
        const val EXTRA_CALL_ID = "ro.zapakid.calls.CALL_ID"
        const val EXTRA_TITLE = "ro.zapakid.calls.TITLE"
        const val EXTRA_AVATAR = "ro.zapakid.calls.AVATAR"
        const val EXTRA_GROUP = "ro.zapakid.calls.GROUP"
    }
}
