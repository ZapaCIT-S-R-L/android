package com.zapacit.cuzaai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zapacit.cuzaai.auth.signin.AppleSignInConfig
import com.zapacit.cuzaai.auth.signin.AppleWebSignIn
import com.zapacit.cuzaai.auth.signin.GoogleIdTokenProvider
import com.zapacit.cuzaai.ui.AuthViewModel
import com.zapacit.cuzaai.ui.ZapaKidApp
import com.zapacit.cuzaai.ui.theme.ZapaKidTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as ZapaKidApplication).graph

    private val authViewModel: AuthViewModel by viewModels {
        viewModelFactory { initializer { AuthViewModel(graph.authRepository) } }
    }

    private val google by lazy { GoogleIdTokenProvider({ this }, BuildConfig.GOOGLE_SERVER_CLIENT_ID) }
    private val apple by lazy {
        AppleWebSignIn(
            activity = { this },
            config = AppleSignInConfig(BuildConfig.APPLE_SERVICES_ID, BuildConfig.APPLE_REDIRECT_URI)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZapaKidTheme { ZapaKidApp(authViewModel, google = google, apple = apple) }
        }
    }

    // launchMode="singleTask": the Apple return link comes back here and closes the Custom Tab.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { apple.onReturn(it) }
    }

    override fun onResume() {
        super.onResume()
        apple.onResumed()
    }
}
