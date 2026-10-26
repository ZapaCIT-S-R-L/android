package com.zapacit.cuzaai

import android.content.Context
import android.os.Build
import com.zapacit.cuzaai.auth.AuthRepository
import com.zapacit.cuzaai.auth.EncryptedSessionStore
import com.zapacit.cuzaai.auth.HttpAuthApi
import com.zapacit.cuzaai.auth.KeystoreSessionCipher
import com.zapacit.cuzaai.auth.PlayAgeSignalsSource
import com.zapacit.cuzaai.auth.Tls
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Production wiring. Tests build the same classes with fakes instead. */
class AppGraph(context: Context) {
    private val app = context.applicationContext

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
            api = HttpAuthApi(BuildConfig.API_BASE_URL.toHttpUrl(), http),
            store = EncryptedSessionStore(File(app.noBackupFilesDir, "session.bin"), KeystoreSessionCipher()),
            ageSignals = PlayAgeSignalsSource(app)
        )
    }
}
