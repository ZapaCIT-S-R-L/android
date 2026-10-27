package ro.zapakid.calls.ring

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import ro.zapakid.calls.CallState
import ro.zapakid.calls.CallUiState
import ro.zapakid.calls.ui.CallActions
import ro.zapakid.calls.ui.CallScreen

/**
 * The full-screen incoming call, shown over the lock screen. Answer opens the app on the call screen (unlocking
 * first when the phone is locked); Decline just stops the ringing.
 */
class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        val callId = intent.getStringExtra(TelecomCallSystem.EXTRA_CALL_ID)
        if (callId == null) {
            finish()
            return
        }
        val state =
            CallUiState(
                phase = CallState.Ringing,
                callId = callId,
                title = intent.getStringExtra(TelecomCallSystem.EXTRA_TITLE).orEmpty(),
                titleAvatar = intent.getIntExtra(TelecomCallSystem.EXTRA_AVATAR, 0),
                group = intent.getBooleanExtra(TelecomCallSystem.EXTRA_GROUP, false)
            )
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                CallScreen(state, actions(callId))
            }
        }
    }

    @Suppress("DEPRECATION") // the window flags are the only way on Android 8.0 (API 26)
    private fun showOverLockScreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun actions(callId: String) = object : CallActions {
        override fun accept() {
            sendBroadcast(
                Intent(
                    this@IncomingCallActivity,
                    CallActionReceiver::class.java
                ).setAction(CallActionReceiver.ACCEPT).putExtra(TelecomCallSystem.EXTRA_CALL_ID, callId)
            )
            finish()
        }

        override fun decline() {
            sendBroadcast(
                Intent(
                    this@IncomingCallActivity,
                    CallActionReceiver::class.java
                ).setAction(CallActionReceiver.DECLINE).putExtra(TelecomCallSystem.EXTRA_CALL_ID, callId)
            )
            finish()
        }

        override fun hangup() = decline()

        override fun toggleMute() = Unit

        override fun toggleSpeaker() = Unit

        override fun dismiss() = finish()
    }

    companion object {
        fun intent(context: Context, callId: String, title: ConversationTitle): Intent =
            Intent(context, IncomingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(TelecomCallSystem.EXTRA_CALL_ID, callId)
                .putExtra(TelecomCallSystem.EXTRA_TITLE, title.name)
                .putExtra(TelecomCallSystem.EXTRA_AVATAR, title.avatar)
                .putExtra(TelecomCallSystem.EXTRA_GROUP, title.group)
    }
}
