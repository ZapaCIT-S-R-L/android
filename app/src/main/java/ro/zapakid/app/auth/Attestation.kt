package ro.zapakid.app.auth

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App attestation for sign-in (openapi.yaml, "Draft: App attestation"; required on sign-in from launch).
 * On Android: a standard Play Integrity token whose requestHash is the SHA-256 of the exact request body, sent in the
 * ZapaKid-Attestation header. The server decodes it with Google and answers 403 attestation_failed if it doesn't pass.
 */
interface RequestAttestation {
    /** A token for this request, or null when none can be made here (the server then decides). */
    suspend fun token(requestHash: String): String?
}

object Attestation {
    const val HEADER = "ZapaKid-Attestation"

    /** Lowercase hex SHA-256 (platform MessageDigest) of the bytes sent. */
    fun requestHash(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
}

/**
 * Keeps one prepared Play Integrity token provider [P] (preparing is slow; requests are fast). Prepares again once
 * when Play reports the provider expired. Any other failure gives no token, so sign-in still reaches the server,
 * which enforces the policy.
 */
class IntegrityTokenCache<P : Any>(
    private val prepare: suspend () -> P,
    private val request: suspend (P, String) -> String,
    private val isProviderInvalid: (Throwable) -> Boolean
) : RequestAttestation {
    private val lock = Mutex()
    private var provider: P? = null

    override suspend fun token(requestHash: String): String? = try {
        try {
            request(provider(), requestHash)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            if (!isProviderInvalid(e)) throw e
            lock.withLock { provider = null }
            request(provider(), requestHash)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception
    ) {
        null
    }

    private suspend fun provider(): P = lock.withLock { provider ?: prepare().also { provider = it } }
}
