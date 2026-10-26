package com.zapacit.cuzaai

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import com.zapacit.cuzaai.auth.AgeSignalsSource
import com.zapacit.cuzaai.auth.AuthApi
import com.zapacit.cuzaai.auth.AuthRepository
import com.zapacit.cuzaai.auth.EncryptedSessionStore
import com.zapacit.cuzaai.auth.HttpAuthApi
import com.zapacit.cuzaai.auth.KeystoreSessionCipher
import com.zapacit.cuzaai.auth.PlayAgeSignalsSource
import com.zapacit.cuzaai.auth.SessionStore
import com.zapacit.cuzaai.auth.Tls
import com.zapacit.cuzaai.auth.signin.AppleSignInConfig
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Production wiring. Emulator tests pass fakes for the network, storage, age signal and Custom Tab. */
class AppGraph(
    context: Context,
    api: AuthApi? = null,
    store: SessionStore? = null,
    ageSignals: AgeSignalsSource? = null,
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
            api = api ?: HttpAuthApi(BuildConfig.API_BASE_URL.toHttpUrl(), http),
            store = store ?: EncryptedSessionStore(File(app.noBackupFilesDir, "session.bin"), KeystoreSessionCipher()),
            ageSignals = ageSignals ?: PlayAgeSignalsSource(app),
            appleConfig = appleConfig
        )
    }
}
