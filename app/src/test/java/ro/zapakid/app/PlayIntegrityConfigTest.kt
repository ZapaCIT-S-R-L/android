package ro.zapakid.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Play Integrity tokens are requested for the Google Cloud project linked to the Play app ro.zapakid.app. */
class PlayIntegrityConfigTest {
    @Test
    fun theCloudProjectIsTheOneLinkedInPlayConsole() {
        assertEquals(64042746735L, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
    }
}
