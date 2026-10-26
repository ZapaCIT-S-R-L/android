package ro.zapakid.app.auth

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlinx.coroutines.tasks.await

/**
 * Standard Play Integrity API. [cloudProjectNumber] is the Google Cloud project linked in Play Console (public);
 * 0 means not set up in this build: no token, and the server decides.
 */
class PlayIntegrityAttestation(context: Context, private val cloudProjectNumber: Long) : RequestAttestation {
    private val manager by lazy { IntegrityManagerFactory.createStandard(context.applicationContext) }

    private val cache =
        IntegrityTokenCache<StandardIntegrityTokenProvider>(
            prepare = {
                manager
                    .prepareIntegrityToken(
                        PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build()
                    ).await()
            },
            request = { provider, hash ->
                provider
                    .request(StandardIntegrityTokenRequest.builder().setRequestHash(hash).build())
                    .await()
                    .token()
            },
            isProviderInvalid = {
                it is StandardIntegrityException &&
                    it.errorCode == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID
            }
        )

    override suspend fun token(requestHash: String): String? =
        if (cloudProjectNumber <= 0) null else cache.token(requestHash)
}
