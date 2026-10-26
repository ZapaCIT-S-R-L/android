package com.zapacit.cuzaai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zapacit.cuzaai.ui.WelcomeScreen
import com.zapacit.cuzaai.ui.theme.ZapaKidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZapaKidTheme {
                WelcomeScreen()
            }
        }
    }
}
