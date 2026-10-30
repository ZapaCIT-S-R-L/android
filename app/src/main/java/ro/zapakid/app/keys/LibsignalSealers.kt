package ro.zapakid.app.keys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignalProtocolStore
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.CallKeySealer
import ro.zapakid.calls.crypto.CallKeySealerException
import ro.zapakid.chat.ChatDevice
import ro.zapakid.chat.crypto.CiphertextType
import ro.zapakid.chat.crypto.MessageSealer
import ro.zapakid.chat.crypto.MessageSealerException
import ro.zapakid.chat.crypto.Sealed

/** Prekey bundles for every device of an account (the key directory). */
fun interface PreKeyBundleSource {
    suspend fun bundles(accountId: String): List<PreKeyBundle>
}

/**
 * One phone's libsignal sessions over its protocol store: no session with a device yet -> PQXDH from the key
 * directory's prekey bundle, then the double ratchet. Identity changes are refused by the store (never trusted
 * silently). One lock, so two sends never race on a session.
 */
class LibsignalSessions(
    private val store: SignalProtocolStore,
    private val local: SignalProtocolAddress,
    private val bundles: PreKeyBundleSource
) {
    private val lock = Mutex()

    /** The serialized libsignal message and whether it starts a session (a prekey message). */
    suspend fun encrypt(plaintext: ByteArray, to: SignalProtocolAddress): Pair<ByteArray, Boolean> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!store.containsSession(to)) {
                val bundle = bundles.bundles(to.name).firstOrNull { it.deviceId == to.deviceId }
                    ?: throw NoBundleException(to)
                SessionBuilder(store, to, local).process(bundle)
            }
            val message = SessionCipher(store, local, to).encrypt(plaintext)
            message.serialize() to (message.type == CiphertextMessage.PREKEY_TYPE)
        }
    }

    suspend fun decrypt(content: ByteArray, preKey: Boolean, from: SignalProtocolAddress): ByteArray = lock.withLock {
        withContext(Dispatchers.IO) {
            val cipher = SessionCipher(store, local, from)
            if (preKey) cipher.decrypt(PreKeySignalMessage(content)) else cipher.decrypt(SignalMessage(content))
        }
    }

    class NoBundleException(address: SignalProtocolAddress) :
        Exception("no prekey bundle for ${address.name}.${address.deviceId}")
}

/** Chat messages: one libsignal message per device, `prekey_message` or `message`. Sender keys come later. */
class LibsignalMessageSealer(private val sessions: LibsignalSessions) : MessageSealer {
    override suspend fun seal(plaintext: ByteArray, toDevice: ChatDevice): Sealed = guard {
        val (content, preKey) = sessions.encrypt(plaintext, SignalProtocolAddress(toDevice.account, toDevice.device))
        Sealed(if (preKey) CiphertextType.PreKey else CiphertextType.Message, content)
    }

    override suspend fun open(type: CiphertextType, content: ByteArray, fromDevice: ChatDevice): ByteArray = guard {
        if (type == CiphertextType.SenderKey) throw MessageSealerException("sender keys are not used yet")
        val from = SignalProtocolAddress(fromDevice.account, fromDevice.device)
        sessions.decrypt(content, type == CiphertextType.PreKey, from)
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: MessageSealerException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw MessageSealerException("${e.javaClass.simpleName}: ${e.message}", e)
    }
}

/**
 * Call keys over the same sessions, byte for byte as iOS: one type byte (1 = prekey message, 0 = message), then the
 * libsignal message. Devices are named `<accountId>.<deviceId>`.
 */
class LibsignalCallKeySealer(private val sessions: LibsignalSessions) : CallKeySealer {
    override suspend fun seal(payload: ByteArray, toDevice: CallDevice): ByteArray = guard {
        val (content, preKey) = sessions.encrypt(payload, address(toDevice))
        byteArrayOf(if (preKey) PREKEY else MESSAGE) + content
    }

    override suspend fun open(blob: ByteArray, fromDevice: CallDevice): ByteArray = guard {
        if (blob.isEmpty()) throw CallKeySealerException("empty blob")
        sessions.decrypt(blob.copyOfRange(1, blob.size), blob[0] == PREKEY, address(fromDevice))
    }

    private fun address(device: CallDevice): SignalProtocolAddress {
        val id = device.device.toIntOrNull() ?: throw CallKeySealerException("bad device id")
        return SignalProtocolAddress(device.account, id)
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: CallKeySealerException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CallKeySealerException("${e.javaClass.simpleName}: ${e.message}", e)
    }

    private companion object {
        const val PREKEY: Byte = 1
        const val MESSAGE: Byte = 0
    }
}

/** `GET /v1/keys/{accountId}/` + `*` (every device) on the account API, with the session: one bundle per device. */
class HttpPreKeyBundles(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    private val session: () -> String?
) : PreKeyBundleSource {
    @Serializable
    private data class Directory(val identityKey: String, val devices: List<DeviceBundle>)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun bundles(accountId: String): List<PreKeyBundle> = withContext(Dispatchers.IO) {
        val token = checkNotNull(session()) { "signed out" }
        val url = baseUrl.newBuilder().addPathSegment("keys").addPathSegment(accountId).addPathSegment("*").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "key directory: HTTP ${response.code}" }
            val directory = json.decodeFromString(Directory.serializer(), response.body.string())
            directory.devices.map { PreKeyBundles.from(directory.identityKey, it) }
        }
    }
}

/**
 * The live sealers for the shell: only once this phone is registered with the key directory (parent or paired
 * kid). Until then null, and chat and calls show "getting ready".
 */
class LibsignalSealerSource(
    private val registration: DeviceRegistrar,
    private val accounts: AccountKeys,
    /** The account this phone holds keys for: the parent's id, or the paired kid's. */
    private val accountId: () -> String?,
    private val bundles: PreKeyBundleSource
) : ro.zapakid.app.shell.SealerSource {
    @Suppress("ReturnCount") // each step can say "not ready yet"
    override suspend fun sealers(): ro.zapakid.app.shell.Sealers? {
        val account = accountId() ?: return null
        val registered = registration.register() as? DeviceRegistration.Result.Registered ?: return null
        val records = accounts.records(account)
        val identity =
            (LocalIdentity(records, accounts.backup(account)).resolve(true) {} as? LocalIdentity.State.Ready)
                ?.identity ?: return null
        val sessions =
            LibsignalSessions(
                ZapaKidProtocolStore(records, identity),
                SignalProtocolAddress(account, registered.deviceId),
                bundles
            )
        return ro.zapakid.app.shell.Sealers(LibsignalCallKeySealer(sessions), LibsignalMessageSealer(sessions))
    }
}
