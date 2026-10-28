package ro.zapakid.app

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import ro.zapakid.app.auth.AgeSignalsSource
import ro.zapakid.app.auth.AuthApi
import ro.zapakid.app.auth.AuthRepository
import ro.zapakid.app.auth.EncryptedSessionStore
import ro.zapakid.app.auth.HttpAuthApi
import ro.zapakid.app.auth.KeystoreBlobCipher
import ro.zapakid.app.auth.PlayAgeSignalsSource
import ro.zapakid.app.auth.PlayIntegrityAttestation
import ro.zapakid.app.auth.SessionStore
import ro.zapakid.app.auth.Tls
import ro.zapakid.app.auth.signin.AppleSignInConfig
import ro.zapakid.app.family.EncryptedKidSessionStore
import ro.zapakid.app.family.FamilyApi
import ro.zapakid.app.family.HttpFamilyApi
import ro.zapakid.app.family.KidSessionStore

/** Production wiring. Emulator tests pass fakes for the network, storage, age signal and Custom Tab. */
class AppGraph(
    context: Context,
    api: AuthApi? = null,
    store: SessionStore? = null,
    ageSignals: AgeSignalsSource? = null,
    familyApi: FamilyApi? = null,
    kidStore: KidSessionStore? = null,
    /** Opens Apple's authorize page. A Custom Tab in the app; tests replace the browser. */
    val openCustomTab: (
        Activity,
        Uri
    ) -> Unit = { host, uri -> CustomTabsIntent.Builder().build().launchUrl(host, uri) }
) {
    private val app = context.applicationContext

    val appleConfig = AppleSignInConfig(BuildConfig.APPLE_SERVICES_ID, BuildConfig.APPLE_REDIRECT_URI)

    private val http: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectionSpecs(Tls.connectionSpecs(Build.VERSION.SDK_INT))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            api =
                api ?: HttpAuthApi(
                    BuildConfig.API_BASE_URL.toHttpUrl(),
                    http,
                    PlayIntegrityAttestation(app, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
                ),
            store = store ?: EncryptedSessionStore(File(app.noBackupFilesDir, "session.bin"), KeystoreBlobCipher()),
            ageSignals = ageSignals ?: PlayAgeSignalsSource(app),
            appleConfig = appleConfig
        )
    }

    val familyApi: FamilyApi by lazy {
        familyApi ?: HttpFamilyApi(
            BuildConfig.API_BASE_URL.toHttpUrl(),
            http,
            PlayIntegrityAttestation(app, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
        )
    }

    /** A paired kid phone's session, encrypted like the parent session. */
    val kidSessionStore: KidSessionStore by lazy {
        kidStore ?: EncryptedKidSessionStore(File(app.noBackupFilesDir, "kid-session.bin"), KeystoreSessionCipher())
    }
}
