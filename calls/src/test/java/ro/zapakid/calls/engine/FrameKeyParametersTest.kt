package ro.zapakid.calls.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.KeyRing

/** The FrameCryptor settings must be the same on Android and iOS, or the two can't hear each other. */
class FrameKeyParametersTest {
    @Test
    fun sharedKeyHkdfAes256WithTheIosSaltAndARingOf16() {
        assertEquals("ZapaKid call frame key v1", WebRtcCallEngine.RATCHET_SALT)
        assertEquals(256, WebRtcCallEngine.KEY_BITS)
        assertEquals(16, KeyRing.SIZE)
        assertEquals(-1, WebRtcCallEngine.FAILURE_TOLERANCE)
    }

    @Test
    fun trackNamesAreAllowedByTheCallsApi() {
        val name = WebRtcCallEngine.trackName(CallDevice("7f1c7a52-2f7e-4c2b-9a51-0b9a1c3f0e11", "12"))

        assertTrue(name, Regex("^[A-Za-z0-9_-]{1,64}$").matches(name))
    }

    @Test
    fun participantIdsAreTheRosterIds() {
        assertEquals("acc.3", WebRtcCallEngine.id(CallDevice("acc", "3")))
    }
}
