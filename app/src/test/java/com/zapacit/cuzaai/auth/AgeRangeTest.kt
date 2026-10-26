package com.zapacit.cuzaai.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Google Play Age Signals result means for a parent account. */
class AgeRangeTest {
    @Test
    fun anUpperBoundBelow18IsAMinor() {
        assertTrue(AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = 13, upper = 15).isMinor)
        assertTrue(AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = 16, upper = 17).isMinor)
        assertTrue(AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = null, upper = 12).isMinor)
    }

    @Test
    fun adultsAndUnknownAgesAreNotBlockedOnThePhone() {
        assertFalse(AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, lower = 18, upper = null).isMinor)
        assertFalse(AgeRange.unknown().isMinor)
        assertFalse(
            "a range that reaches 18 is not proof of a minor",
            AgeRange(AgeRange.SOURCE_GOOGLE_PLAY, 16, 18).isMinor
        )
    }

    @Test
    fun anErrorIsSentAsUnknownWithTheErrorCode() {
        val range = AgeRange.error(-1)
        assertEquals(AgeRange.SOURCE_GOOGLE_PLAY, range.source)
        assertEquals(-1, range.error)
        assertFalse(range.isMinor)
    }
}
