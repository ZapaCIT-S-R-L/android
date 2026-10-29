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
        kidStore ?: EncryptedKidSessionStore(File(app.noBackupFilesDir, "kid-session.bin"), KeystoreBlobCipher())
    }

    /** Chat tokens for chat and calls, from the parent session or, on a kid phone, the kid session. */
    val chatTokens: ro.zapakid.app.shell.ChatTokenClient by lazy {
        ro.zapakid.app.shell.ChatTokenClient(BuildConfig.API_BASE_URL.toHttpUrl(), http, session = {
            authRepository.token() ?: kidSessionStore.load()?.token
        })
    }

    val callLog: ro.zapakid.calls.InMemoryCallLog by lazy { ro.zapakid.calls.InMemoryCallLog() }

    /**
     * The chat store of the signed-in account: its own database file and its own key, closed when the phone changes
     * account, so one account never opens another's stored conversations.
     */
    private val chatStores: ro.zapakid.app.shell.PerAccountChatStore by lazy {
        ro.zapakid.app.shell.PerAccountChatStore(
            openStore = { name ->
                ro.zapakid.chat.data.SqlCipherChatStore(
                    app,
                    ro.zapakid.chat.data.DatabaseKey.load(
                        File(app.noBackupFilesDir, "$name.key"),
                        KeystoreKeyWrapper()
                    ),
                    "$name.db"
                )
            },
            closeStore = { store -> (store as? ro.zapakid.chat.data.SqlCipherChatStore)?.close() },
            // The call log is process state rather than store state: a new account must not see the old one's calls.
            onSwitch = { callLog.clear() }
        )
    }

    /** The account this phone is signed in as: the parent's, or the paired kid's on a kid phone. */
    private fun account(): String =
        checkNotNull(authRepository.current()?.id ?: kidSessionStore.load()?.kid?.id) { "signed out" }

    val shell: ro.zapakid.app.shell.ShellDeps by lazy {
        ro.zapakid.app.shell.ShellDeps(
            http = http,
            chatUrl = BuildConfig.CHAT_BASE_URL.toHttpUrl(),
            callsUrl = BuildConfig.CALLS_BASE_URL.toHttpUrl(),
            tokens = chatTokens,
            // The libsignal sealers are wired here once the key-setup PRs land (android #12, #17).
            sealers = ro.zapakid.app.shell.SealerSource.None,
            store = { chatStores.forAccount(account()) },
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
