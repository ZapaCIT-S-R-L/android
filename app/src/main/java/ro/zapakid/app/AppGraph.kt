package ro.zapakid.app

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import ro.zapakid.app.shell.ChatTokenClient
import ro.zapakid.app.shell.SealerSource
import ro.zapakid.app.shell.Sealers
import ro.zapakid.app.shell.Services
import ro.zapakid.app.shell.ShellDeps
import ro.zapakid.app.shell.ShellSessions
import ro.zapakid.calls.CallController
import ro.zapakid.calls.CallPeople
import ro.zapakid.calls.InMemoryCallLog
import ro.zapakid.calls.api.CallEventsClient
import ro.zapakid.calls.api.HttpCallsApi
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.engine.WebRtcCallEngine
import ro.zapakid.chat.ChatDevice
import ro.zapakid.chat.ChatRepository
import ro.zapakid.chat.ChatSync
import ro.zapakid.chat.data.DatabaseKey
import ro.zapakid.chat.data.KeyWrapper
import ro.zapakid.chat.data.SqlCipherChatStore
import ro.zapakid.chat.net.HttpChatApi

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
    val chatTokens: ChatTokenClient by lazy {
        ChatTokenClient(BuildConfig.API_BASE_URL.toHttpUrl(), http, session = {
            authRepository.token() ?: kidSessionStore.load()?.token
        })
    }

    val callLog: InMemoryCallLog by lazy { InMemoryCallLog() }

    /**
     * The session's scope. The call controller's own state is not thread-safe, so it stays on the main thread like
     * the composition it serves — and it outlives that composition, so a rotation reconnects instead of orphaning a
     * live call and its media.
     */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Outlives the composition and waits on the network: a sign-out tears down here, while the shell goes away. */
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The retained sign-in machinery: one chat store per account, a shutdown that closes only the outgoing
     * account's store, and the session the shell reconnects to after a configuration change.
     */
    val sessions: ShellSessions<Services> by lazy {
        ShellSessions(
            sessionScope,
            shutdownScope,
            callLog,
            openStore = { name ->
                SqlCipherChatStore(
                    app,
                    DatabaseKey.load(File(app.noBackupFilesDir, "$name.key"), KeystoreKeyWrapper()),
                    "$name.db"
                )
            },
            closeStore = { store -> (store as? SqlCipherChatStore)?.close() },
            build = { buildServices() }
        )
    }

    /** The phone signed out: release the call, then the sync, then that account's chat store, in that order. */
    fun signedOut() = sessions.signedOut()

    val shell: ShellDeps by lazy { ShellDeps(sessions.session, callLog) }

    /** Builds the signed-in shell's services once per sign-in, or null while this phone's keys aren't ready. */
    private suspend fun buildServices(): Services? {
        // The libsignal sealers are wired here once the key-setup PRs land (android #12, #17).
        val sealers = SealerSource.None.sealers() ?: return null
        val me = runCatching { chatTokens.identity() }.getOrNull()
        val deviceId = me?.device?.toIntOrNull()
        return if (me == null || deviceId == null) null else newServices(sealers, me.account, me.device, deviceId)
    }

    /** The chat stack, its sync and the call controller, all on the session's scope. */
    private fun newServices(sealers: Sealers, account: String, device: String, deviceId: Int): Services {
        val callsUrl = BuildConfig.CALLS_BASE_URL.toHttpUrl()
        val chatApi = HttpChatApi(BuildConfig.CHAT_BASE_URL.toHttpUrl(), http, chatTokens)
        val chats =
            ChatRepository(chatApi, sessions.stores.forAccount(account), sealers.chat, ChatDevice(account, deviceId))
        val sync = ChatSync(chats, chatApi, chatApi)
        val syncJob = sessionScope.launch { sync.run() }
        val callsApi = HttpCallsApi(callsUrl, http, chatTokens)
        val calls =
            CallController(
                callsApi,
                CallEventsClient(callsUrl, http, chatTokens, CallDevice(account, device))::connect,
                WebRtcCallEngine(app, callsApi),
                sealers.calls,
                CallDevice(account, device),
                callLog,
                sessionScope,
                CallPeople.Default
            )
        return Services(account, chats, sync, calls, syncJob)
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

/** The Keystore cipher of the session store, wrapping the chat database key. */
private class KeystoreKeyWrapper : KeyWrapper {
    private val cipher = KeystoreBlobCipher()

    override fun wrap(plain: ByteArray) = cipher.encrypt(plain)

    override fun unwrap(wrapped: ByteArray) = cipher.decrypt(wrapped)
}
