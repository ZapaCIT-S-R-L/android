package ro.zapakid.calls.ring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import ro.zapakid.calls.R

/**
 * The ringing notification: a full-screen incoming call (lock screen included) with big Answer and Decline,
 * a gentle ringtone and vibration from the "Calls" channel.
 */
object CallNotifications {
    const val CHANNEL = "calls_incoming"
    private val VIBRATION = longArrayOf(0, 400, 600, 400, 600)

    fun createChannel(context: Context) {
        val channel =
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.call_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.call_channel_description)
                vibrationPattern = VIBRATION
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Whether a ringing call can be shown: notifications on, and on Android 14+ the full-screen intent allowed. */
    fun canRing(context: Context): Boolean = canRing(
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        sdk = Build.VERSION.SDK_INT,
        fullScreenAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    )

    fun canRing(notificationsEnabled: Boolean, sdk: Int, fullScreenAllowed: Boolean): Boolean =
        notificationsEnabled && (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || fullScreenAllowed)

    fun showIncoming(context: Context, callId: String, title: ConversationTitle) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val app = context.applicationContext
        // Answer or Decline from anywhere (Telecom, Bluetooth, watch) takes the ringing UI down.
        CallRinger.stopRinging = { id ->
            cancel(app, id)
            IncomingCallActivity.close(id)
        }
        createChannel(context)
        val caller = Person.Builder().setName(title.name).setImportant(true).build()
        val fullScreen =
            PendingIntent.getActivity(
                context,
                callId.hashCode(),
                IncomingCallActivity.intent(context, callId, title),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_call)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setTimeoutAfter(RING_MILLIS)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        caller,
                        action(context, callId, CallActionReceiver.DECLINE),
                        action(context, callId, CallActionReceiver.ACCEPT)
                    )
                ).build()
        @Suppress("MissingPermission") // checked above: areNotificationsEnabled()
        NotificationManagerCompat.from(context).notify(callId, NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context, callId: String) =
        NotificationManagerCompat.from(context).cancel(callId, NOTIFICATION_ID)

    private fun action(context: Context, callId: String, what: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        (callId + what).hashCode(),
        Intent(
            context,
            CallActionReceiver::class.java
        ).setAction(what).putExtra(TelecomCallSystem.EXTRA_CALL_ID, callId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private const val NOTIFICATION_ID = 45
    private const val RING_MILLIS = 45_000L
}

/** Answer / Decline from the notification. Not exported: only our own PendingIntents reach it. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(TelecomCallSystem.EXTRA_CALL_ID) ?: return
        CallNotifications.cancel(context, callId)
        when (intent.action) {
            ACCEPT -> {
                // Waits for the app's call controller when a push woke a fresh process; opening the app wires it.
                CallRinger.send(callId, CallRinger.Action.ACCEPT)
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    ContextCompat.startActivity(context, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)
                }
            }

            DECLINE -> {
                CallRinger.send(callId, CallRinger.Action.DECLINE)
                ZapaConnectionService.end(callId)
            }
        }
    }

    companion object {
        const val ACCEPT = "ro.zapakid.calls.ACCEPT"
        const val DECLINE = "ro.zapakid.calls.DECLINE"
    }
}
