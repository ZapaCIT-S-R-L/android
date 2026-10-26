package com.zapacit.cuzaai.auth

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

/** Google Play Age Signals. Where the API isn't available (most of the EU today) the result is unknown + error code. */
class PlayAgeSignalsSource(context: Context) : AgeSignalsSource {
    private val manager = AgeSignalsManagerFactory.create(context.applicationContext)

    override suspend fun check(): AgeRange = try {
        val result = manager.checkAgeSignals(AgeSignalsRequest.builder().build()).await()
        AgeRange(source = AgeRange.SOURCE_GOOGLE_PLAY, lower = result.ageLower(), upper = result.ageUpper())
    } catch (
        @Suppress("SwallowedException") e: AgeSignalsException
    ) {
        AgeRange.error(e.errorCode)
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: RuntimeException
    ) {
        // Play services missing or broken: unknown; the API decides.
        AgeRange.error(AgeSignalsErrorCode.INTERNAL_ERROR)
    }
}
