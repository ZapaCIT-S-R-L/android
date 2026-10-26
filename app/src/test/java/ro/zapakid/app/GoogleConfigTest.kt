package ro.zapakid.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sign in with Google: Credential Manager asks for tokens for ZapaKid's OAuth web (server) client (public ID). */
class GoogleConfigTest {
    @Test
    fun theWebClientIdIsZapaKidsServerClient() {
        assertEquals(
            "64042746735-r70nv5pmlro7hpksk1te3ur008ungss1.apps.googleusercontent.com",
            BuildConfig.GOOGLE_SERVER_CLIENT_ID
        )
    }
}
