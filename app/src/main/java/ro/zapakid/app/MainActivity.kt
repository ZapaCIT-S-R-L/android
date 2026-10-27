package ro.zapakid.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ro.zapakid.app.auth.signin.CustomTabAppleBrowser
import ro.zapakid.app.auth.signin.GoogleIdTokenProvider
import ro.zapakid.app.family.FamilyViewModel
import ro.zapakid.app.family.GmsQrScanner
import ro.zapakid.app.family.KidPairingViewModel
import ro.zapakid.app.ui.AuthViewModel
import ro.zapakid.app.ui.ZapaKidApp
import ro.zapakid.app.ui.theme.ZapaKidTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as ZapaKidApplication).graph

    private val authViewModel: AuthViewModel by viewModels {
        viewModelFactory { initializer { AuthViewModel(graph.authRepository) } }
    }

    private val familyViewModel: FamilyViewModel by viewModels {
        viewModelFactory { initializer { FamilyViewModel(graph.familyApi, graph.authRepository::token) } }
    }

    private val kidViewModel: KidPairingViewModel by viewModels {
        viewModelFactory {
            initializer { KidPairingViewModel(graph.familyApi, graph.kidSessionStore, GmsQrScanner(application)) }
        }
    }

    private val google by lazy { GoogleIdTokenProvider({ this }, BuildConfig.GOOGLE_SERVER_CLIENT_ID) }
    private val apple by lazy { CustomTabAppleBrowser(activity = { this }, openCustomTab = graph.openCustomTab) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZapaKidTheme {
                ZapaKidApp(
                    authViewModel,
                    google = google,
                    apple = apple,
                    family = familyViewModel,
                    kid = kidViewModel,
                    shell = graph.shell
                )
            }
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
