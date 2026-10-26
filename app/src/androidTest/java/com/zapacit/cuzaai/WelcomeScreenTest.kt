package com.zapacit.cuzaai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zapacit.cuzaai.ui.WelcomeScreen
import com.zapacit.cuzaai.ui.theme.ZapaKidTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTheAppNameAsAHeading() {
        compose.setContent { ZapaKidTheme { WelcomeScreen() } }
        compose.onNodeWithText("ZapaKid").assertIsDisplayed()
        assertTrue(isHeading().matches(compose.onNodeWithText("ZapaKid").fetchSemanticsNode()))
    }
}
