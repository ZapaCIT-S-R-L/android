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
import ro.zapakid.app.keys.AccountKeys
import ro.zapakid.app.keys.DeviceRegistrar
import ro.zapakid.app.keys.DeviceRegistration
import ro.zapakid.app.keys.HttpDeviceApi
import ro.zapakid.app.keys.PlayBlockStoreApi

/** Production wiring. Emulator tests pass fakes for the network, storage, age signal and Custom Tab. */
class AppGraph(
    context: Context,
    api: AuthApi? = null,
    store: SessionStore? = null,
    ageSignals: AgeSignalsSource? = null,
    private val deviceRegistrar: DeviceRegistrar? = null,
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

    /** This phone's libsignal records: Keystore-encrypted files in noBackupFilesDir (never backed up). */
    private val accountKeys by lazy {
        AccountKeys(File(app.noBackupFilesDir, "keys"), KeystoreBlobCipher("zapakid.keys.v1"), PlayBlockStoreApi(app))
    }

    val deviceRegistration: DeviceRegistrar by lazy {
        deviceRegistrar ?: DeviceRegistration(
            accounts = accountKeys,
            api = HttpDeviceApi(BuildConfig.API_BASE_URL.toHttpUrl(), http),
            session = authRepository::currentSession,
            // TODO(reshare): ask another family phone by QR (next PR); until then the phone just waits.
            reshare = {}
        )
    }
}
