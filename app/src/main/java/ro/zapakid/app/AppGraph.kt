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
    familyApi: FamilyApi? = null,
    kidStore: KidSessionStore? = null,
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

    private val integrity by lazy { PlayIntegrityAttestation(app, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER) }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            api =
                api ?: HttpAuthApi(
                    BuildConfig.API_BASE_URL.toHttpUrl(),
                    http,
                    integrity
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
            api = HttpDeviceApi(BuildConfig.API_BASE_URL.toHttpUrl(), http, integrity),
            session = authRepository::currentSession,
            // TODO(reshare): ask another family phone by QR (next PR); until then the phone just waits.
            reshare = {}
        )
    }

    val familyApi: FamilyApi by lazy {
        familyApi ?: HttpFamilyApi(
            BuildConfig.API_BASE_URL.toHttpUrl(),
            http,
            integrity
        )
    }

    /** A paired kid phone's session, encrypted like the parent session. */
    val kidSessionStore: KidSessionStore by lazy {
        kidStore ?: EncryptedKidSessionStore(File(app.noBackupFilesDir, "kid-session.bin"), KeystoreBlobCipher())
    }

    /** Chat tokens for chat and calls, from the parent session or, on a kid phone, the kid session. */
    val chatTokens: ro.zapakid.app.shell.ChatTokenClient by lazy {
        ro.zapakid.app.shell.ChatTokenClient(BuildConfig.API_BASE_URL.toHttpUrl(), http, session = {
            authRepository.token() ?: kidSessionStore.load()?.token
        })
    }

    val callLog: ro.zapakid.calls.CallLog by lazy { ro.zapakid.calls.InMemoryCallLog() }

    val shell: ro.zapakid.app.shell.ShellDeps by lazy {
        ro.zapakid.app.shell.ShellDeps(
            http = http,
            chatUrl = BuildConfig.CHAT_BASE_URL.toHttpUrl(),
            callsUrl = BuildConfig.CALLS_BASE_URL.toHttpUrl(),
            tokens = chatTokens,
            sealers =
                ro.zapakid.app.keys.LibsignalSealerSource(
                    deviceRegistration,
                    accountKeys,
                    authRepository::currentSession,
                    ro.zapakid.app.keys.HttpPreKeyBundles(
                        BuildConfig.API_BASE_URL.toHttpUrl(),
                        http,
                        authRepository::token
                    )
                ),
            store = {
                ro.zapakid.chat.data.SqlCipherChatStore(
                    app,
                    ro.zapakid.chat.data.DatabaseKey.load(
                        File(app.noBackupFilesDir, "chat-db.key"),
                        KeystoreKeyWrapper()
                    )
                )
            },
            engine = {
                ro.zapakid.calls.engine.WebRtcCallEngine(
                    app,
                    ro.zapakid.calls.api.HttpCallsApi(BuildConfig.CALLS_BASE_URL.toHttpUrl(), http, chatTokens)
                )
            },
            callLog = callLog
        )
    }
}

/** The Keystore cipher of the session store, wrapping the chat database key. */
private class KeystoreKeyWrapper : ro.zapakid.chat.data.KeyWrapper {
    private val cipher = KeystoreBlobCipher()

    override fun wrap(plain: ByteArray) = cipher.encrypt(plain)

    override fun unwrap(wrapped: ByteArray) = cipher.decrypt(wrapped)
}
