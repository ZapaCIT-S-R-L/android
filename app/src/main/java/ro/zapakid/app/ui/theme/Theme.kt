package ro.zapakid.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Blue = Color(0xFF1E6FD9)
private val BlueLight = Color(0xFF9CC3FF)

private val LightColors = lightColorScheme(primary = Blue)
private val DarkColors = darkColorScheme(primary = BlueLight)

/** App theme: Material 3, dynamic colour on Android 12+, light and dark. Text sizes follow the system font scale. */
@Composable
fun ZapaKidTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColors

            else -> LightColors
        }
    MaterialTheme(colorScheme = colors, content = content)
}
