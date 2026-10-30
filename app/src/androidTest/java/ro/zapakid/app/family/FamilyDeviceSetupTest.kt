package ro.zapakid.app.family

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ro.zapakid.app.ui.DeviceSetupState
import ro.zapakid.app.ui.FamilyRoute

/** Main's family screen and device registration must coexist after integrating the two feature branches. */
class FamilyDeviceSetupTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun familyScreenKeepsTheEncryptionFailureAndRetryAction() {
        val family = Family(
            "f1", "Pop", parents = listOf(FamilyParent("p1", "Ana")),
            kids = listOf(Kid("k1", "Mia", "robot-3", -1))
        )
        val model = FamilyViewModel(StubFamilyApi(family), token = { "session" })
        var retries = 0
        compose.setContent {
            MaterialTheme {
                FamilyRoute(
                    model,
                    onSignOut = {},
                    device = DeviceSetupState.Failed,
                    onRetryDevice = { retries++ }
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Mia").assertIsDisplayed()
        compose.onNodeWithText("Couldn't set up encryption on this phone.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    private class StubFamilyApi(private val family: Family) : FamilyApi {
        override suspend fun myFamily(token: String, name: String?): Family = family

        override suspend fun families(token: String): List<Family> = listOf(family)

        override suspend fun inviteParent(token: String, familyId: String): OneTimeCode = error("not used")

        override suspend fun join(token: String, code: String): Family = error("not used")

        override suspend fun addKid(
            token: String,
            familyId: String,
            nickname: String,
            avatar: String,
            grade: Int?
        ): Kid = error("not used")

        override suspend fun pairKid(token: String, familyId: String, kidId: String): KidPairing = error("not used")

        override suspend fun redeem(code: String): PairedKid = error("not used")
    }
}
