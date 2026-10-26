package ro.zapakid.app.auth

import android.content.Context
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import kotlinx.coroutines.tasks.await

/** The phone's age signal for the signed-in Google Play user. */
interface AgeSignalsSource {
    suspend fun check(): AgeRange
}

/** Google Play Age Signals. Where the API isn't offered (most of the EU today) the result is `unavailable`. */
class PlayAgeSignalsSource(context: Context) : AgeSignalsSource {
    private val manager = AgeSignalsManagerFactory.create(context.applicationContext)

    override suspend fun check(): AgeRange = try {
        val result = manager.checkAgeSignals(AgeSignalsRequest.builder().build()).await()
        PlayAgeMapping.fromResult(result.ageLower(), result.ageUpper())
    } catch (
        @Suppress("SwallowedException") e: AgeSignalsException
    ) {
        PlayAgeMapping.fromError(e.errorCode)
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: RuntimeException
    ) {
        // Play services missing or broken: unavailable; the API decides.
        PlayAgeMapping.fromError(AgeSignalsErrorCode.INTERNAL_ERROR)
    }
}

/** Play Age Signals → the API's AgeRange (openapi.yaml, schema AgeRange). Play has no "declined" outcome. */
object PlayAgeMapping {
    fun fromResult(lower: Int?, upper: Int?): AgeRange = if (lower == null && upper == null) {
        AgeRange.unavailable("UNKNOWN")
    } else {
        AgeRange.shared(lower, upper, "SHARED")
    }

    fun fromError(code: Int): AgeRange = AgeRange.unavailable(
        when (code) {
            AgeSignalsErrorCode.API_NOT_AVAILABLE -> "API_NOT_AVAILABLE"
            AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND -> "PLAY_STORE_NOT_FOUND"
            AgeSignalsErrorCode.NETWORK_ERROR -> "NETWORK_ERROR"
            AgeSignalsErrorCode.PLAY_SERVICES_NOT_FOUND -> "PLAY_SERVICES_NOT_FOUND"
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE -> "CANNOT_BIND_TO_SERVICE"
            AgeSignalsErrorCode.PLAY_STORE_VERSION_OUTDATED -> "PLAY_STORE_VERSION_OUTDATED"
            AgeSignalsErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> "PLAY_SERVICES_VERSION_OUTDATED"
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR -> "CLIENT_TRANSIENT_ERROR"
            AgeSignalsErrorCode.APP_NOT_OWNED -> "APP_NOT_OWNED"
            AgeSignalsErrorCode.SDK_VERSION_OUTDATED -> "SDK_VERSION_OUTDATED"
            AgeSignalsErrorCode.INTERNAL_ERROR -> "INTERNAL_ERROR"
            else -> "ERROR"
        }
    )
}
