package ro.zapakid.app.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.zapakid.app.auth.BlobCipher

/** Reads a QR code with the camera; returns null when the kid closes the scanner. */
fun interface QrScanner {
    suspend fun scan(): String?
}

/**
 * A paired kid phone: the kid session (`zkk_`), who the kid is, and the parent key fingerprint from the QR,
 * which the chat keys are checked against once they are set up (no trust on first use).
 */
@Serializable
data class KidSession(
    val token: String,
    val expiresAt: String,
    val kid: Kid,
    val family: FamilyRef,
    val parentFingerprint: String? = null
)

interface KidSessionStore {
    fun load(): KidSession?

    fun save(session: KidSession)

    fun clear()
}

/** The kid session as one encrypted file in noBackupFilesDir, like the parent session. */
class EncryptedKidSessionStore(private val file: File, private val cipher: BlobCipher) : KidSessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun load(): KidSession? = try {
        if (file.exists()) {
            json.decodeFromString(
                KidSession.serializer(),
                cipher.decrypt(file.readBytes()).decodeToString()
            )
        } else {
            null
        }
    } catch (e: GeneralSecurityException) {
        discard(e)
    } catch (e: IllegalArgumentException) {
        discard(e)
    } catch (e: IOException) {
        discard(e)
    }

    /** Unreadable or tampered: delete it; the phone pairs again. The exception carries no session data. */
    private fun discard(@Suppress("UNUSED_PARAMETER") cause: Exception): KidSession? {
        file.delete()
        return null
    }

    @Synchronized
    override fun save(session: KidSession) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(cipher.encrypt(json.encodeToString(KidSession.serializer(), session).encodeToByteArray()))
        check(tmp.renameTo(file)) { "Could not store the kid session" }
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }
}

sealed interface KidPairingState {
    data object Waiting : KidPairingState

    data object Pairing : KidPairingState

    data class Paired(val session: KidSession) : KidPairingState

    data class Failed(val error: FamilyError) : KidPairingState
}

/** The kid's phone: scan the parent's QR, redeem it, keep the kid session. */
class KidPairingViewModel(
    private val api: FamilyApi,
    private val store: KidSessionStore,
    private val scanner: QrScanner
) : ViewModel() {
    private val mutableState =
        MutableStateFlow<KidPairingState>(store.load()?.let { KidPairingState.Paired(it) } ?: KidPairingState.Waiting)
    val state: StateFlow<KidPairingState> = mutableState

    fun scan() {
        if (mutableState.value == KidPairingState.Pairing) return
        viewModelScope.launch {
            val text = scanner.scan() ?: return@launch
            redeem(text)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun redeem(text: String) {
        val qr = FamilyLinks.parsePairing(text)
        if (qr == null) {
            mutableState.value = KidPairingState.Failed(FamilyError.INVALID_CODE)
            return
        }
        mutableState.value = KidPairingState.Pairing
        mutableState.value =
            try {
                val paired = api.redeem(qr.code)
                val session =
                    KidSession(
                        paired.session.token,
                        paired.session.expiresAt,
                        paired.kid,
                        paired.family,
                        qr.fingerprint
                    )
                store.save(session)
                KidPairingState.Paired(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KidPairingState.Failed(FamilyViewModel.errorOf(e))
            }
    }

    private val mutableFamily = MutableStateFlow<Family?>(null)

    /** The kid's own family, read with the kid session (GET /v1/families). */
    val family: StateFlow<Family?> = mutableFamily

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // offline: the tab keeps its spinner
    suspend fun loadFamily() {
        val session = store.load() ?: return
        try {
            mutableFamily.value = api.families(session.token).firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableFamily.value = null
        }
    }

    fun tryAgain() {
        mutableState.value = KidPairingState.Waiting
    }
}
