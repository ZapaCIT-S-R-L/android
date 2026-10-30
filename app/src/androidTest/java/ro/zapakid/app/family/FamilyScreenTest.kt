package ro.zapakid.app.family

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The parent's family screen and the kid's pairing screen. English UI. */
class FamilyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val family =
        Family("f1", "Pop", parents = listOf(FamilyParent("p1", "Ana")), kids = listOf(Kid("k1", "Mia", "robot-3", -1)))

    @Test
    fun showsKidsWithGradeAndAddsAKid() {
        var added: Triple<String, Int, Int?>? = null
        compose.setContent {
            MaterialTheme {
                FamilyScreen(FamilyUiState(loading = false, family = family), {
                }, { n, a, g -> added = Triple(n, a, g) }, {}, {}, {})
            }
        }

        compose.onNodeWithText("Mia").assertIsDisplayed()
        compose.onNodeWithText("Kindergarten").assertIsDisplayed()
        compose.onNodeWithTag(FamilyTags.ADD_KID).performClick()
        compose.onNodeWithTag(FamilyTags.SAVE_KID).assertIsNotEnabled()
        compose.onNodeWithTag(FamilyTags.NICKNAME).performTextInput("Ion")
        compose.onNodeWithContentDescription("Robot 5").performClick()
        compose.onNodeWithText("Grade 3").performClick()
        compose.onNodeWithTag(FamilyTags.SAVE_KID).performClick()

        assertEquals(Triple("Ion", 4, 3), added)
    }

    @Test
    fun thePairingQrIsShownWithInstructions() {
        val qr = QrCode("zapakid://pair?code=zkp_" + "a".repeat(43), "x", family.kids.single())
        compose.setContent {
            MaterialTheme {
                FamilyScreen(FamilyUiState(loading = false, family = family, qr = qr), {}, { _, _, _ -> }, {}, {}, {})
            }
        }

        compose.onNodeWithContentDescription("Pair Mia’s phone").assertIsDisplayed()
        compose.onNodeWithText("It works once, for 5 minutes", substring = true).assertIsDisplayed()
    }

    @Test
    fun theKidPhoneAsksToScan() {
        var scanned = false
        compose.setContent {
            MaterialTheme {
                KidPairScreen(KidPairingState.Waiting, onScan = { scanned = true }, onTryAgain = {}, onBack = {})
            }
        }

        compose.onNodeWithTag(KidPairTags.SCAN).performClick()

        assertEquals(true, scanned)
    }
}
