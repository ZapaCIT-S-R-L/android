package ro.zapakid.app.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The calm "getting ready" state, with a Try again button once a setup attempt failed. English UI. */
class SettingUpScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun afterAFailureItSaysItKeepsTryingAndOffersTryAgain() {
        var tapped = 0
        compose.setContent { MaterialTheme { SettingUpScreen(SetupState.Waiting(2)) { tapped++ } } }

        compose.onNodeWithText("Couldn't connect yet. We'll keep trying.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(1, tapped)
    }

    @Test
    fun whileWorkingThereIsNoButton() {
        compose.setContent { MaterialTheme { SettingUpScreen(SetupState.Working) {} } }

        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}
