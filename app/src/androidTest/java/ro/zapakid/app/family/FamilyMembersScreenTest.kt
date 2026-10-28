package ro.zapakid.app.family

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The kids' Family tab: who is in the family, as cards, with nothing to manage. English UI. */
class FamilyMembersScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsEveryMemberAndNoManageButtons() {
        val family =
            Family(
                "f1",
                "Pop",
                parents = listOf(FamilyParent("p1", "Ana")),
                kids = listOf(Kid("k1", "Mia", "robot-3", 2), Kid("k2", "Ion", "robot-1", -1))
            )
        compose.setContent { MaterialTheme { FamilyMembersScreen(KidFamilyState.Loaded(family)) {} } }

        compose.onNodeWithText("Ana").assertIsDisplayed()
        compose.onNodeWithText("Mia").assertIsDisplayed()
        compose.onNodeWithText("Ion").assertIsDisplayed()
        compose.onNodeWithTag(FamilyTags.ADD_KID).assertDoesNotExist()
        compose.onNodeWithTag(FamilyTags.INVITE).assertDoesNotExist()
        compose.onNodeWithText("Pair phone").assertDoesNotExist()
        compose.onNodeWithTag(FamilyMembersTags.RETRY).assertDoesNotExist()
    }

    @Test
    fun aFailedReadShowsTheReasonAndRetries() {
        var state by mutableStateOf<KidFamilyState>(KidFamilyState.Failed(FamilyError.NETWORK))
        var retries = 0
        compose.setContent {
            MaterialTheme {
                FamilyMembersScreen(state) {
                    retries++
                    state = KidFamilyState.Loaded(Family("f1", "Pop", parents = listOf(FamilyParent("p1", "Ana"))))
                }
            }
        }

        compose.onNodeWithTag(FamilyMembersTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("No connection. Check the internet and try again.").assertIsDisplayed()
        compose.onNodeWithTag(FamilyMembersTags.RETRY).assertHasClickAction().performClick()

        compose.onNodeWithText("Ana").assertIsDisplayed()
        compose.onNodeWithTag(FamilyMembersTags.RETRY).assertDoesNotExist()
        compose.onNodeWithTag(FamilyMembersTags.ERROR).assertDoesNotExist()
        compose.onNodeWithTag(FamilyTags.ADD_KID).assertDoesNotExist()
        assertEquals(1, retries)
    }
}
