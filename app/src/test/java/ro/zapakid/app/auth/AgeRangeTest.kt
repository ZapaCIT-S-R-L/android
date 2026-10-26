package ro.zapakid.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a Play Age Signals result means for a parent account, by the API's rule (shared and lowerBound >= 18). */
class AgeRangeTest {
    @Test
    fun aSharedRangeBelow18IsAMinor() {
        assertTrue(AgeRange.shared(13, 15, "SUPERVISED").isMinor)
        assertTrue(AgeRange.shared(16, 17, "SUPERVISED").isMinor)
    }

    @Test
    fun aSharedRangeThatDoesNotShow18PlusIsAMinor() {
        assertTrue("16 and up is not proof of 18+", AgeRange.shared(16, null, "SUPERVISED").isMinor)
        assertTrue(AgeRange.shared(null, null, "SUPERVISED").isMinor)
    }

    @Test
    fun adultsAndUnavailableAreNotBlockedOnThePhone() {
        assertFalse(AgeRange.shared(18, null, "VERIFIED").isMinor)
        assertFalse(AgeRange.unavailable("API_NOT_AVAILABLE").isMinor)
    }

    @Test
    fun declinedIsLeftToTheServer() {
        assertFalse(AgeRange.declined().isMinor)
        assertEquals(AgeAvailability.DECLINED, AgeRange.declined().availability)
    }

    @Test
    fun unavailableHasNoBoundsAndThePlatformStatus() {
        val range = AgeRange.unavailable("PLAY_STORE_NOT_FOUND")
        assertEquals(AgeAvailability.UNAVAILABLE, range.availability)
        assertEquals(AgeRange.SOURCE_GOOGLE_PLAY, range.source)
        assertNull(range.lowerBound)
        assertNull(range.upperBound)
        assertEquals("PLAY_STORE_NOT_FOUND", range.platformStatus)
    }

    @Test
    fun playResultsMapLikeTheContractSays() {
        assertEquals(AgeRange.shared(18, null, "SHARED"), PlayAgeMapping.fromResult(lower = 18, upper = null))
        assertEquals(AgeRange.shared(13, 15, "SHARED"), PlayAgeMapping.fromResult(lower = 13, upper = 15))
        assertEquals(AgeRange.unavailable("UNKNOWN"), PlayAgeMapping.fromResult(lower = null, upper = null))
        assertEquals(AgeRange.unavailable("API_NOT_AVAILABLE"), PlayAgeMapping.fromError(-1))
        assertEquals(AgeRange.unavailable("NETWORK_ERROR"), PlayAgeMapping.fromError(-3))
        assertEquals(AgeRange.unavailable("ERROR"), PlayAgeMapping.fromError(-999))
    }
}
