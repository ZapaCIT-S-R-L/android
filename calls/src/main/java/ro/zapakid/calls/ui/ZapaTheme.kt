package ro.zapakid.calls.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Who is using the phone: decides the look (parents calm, kids playful). */
val LocalUserRole = staticCompositionLocalOf { UserRole.Parent }

/** Kids get chunky cards, sparkles and bigger targets; parents thin lines and whitespace. */
val UserRole.playful: Boolean get() = this == UserRole.Kid

fun colorSchemeOf(palette: Palette, dark: Boolean): ColorScheme {
    fun c(v: Long) = Color(v)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(palette.primary),
        onPrimary = c(palette.onPrimary),
        primaryContainer = c(palette.primaryContainer),
        onPrimaryContainer = c(palette.onPrimaryContainer),
        secondary = c(palette.secondary),
        onSecondary = c(palette.onSecondary),
        secondaryContainer = c(palette.secondaryContainer),
        onSecondaryContainer = c(palette.onSecondaryContainer),
        tertiaryContainer = c(palette.tertiaryContainer),
        onTertiaryContainer = c(palette.onTertiaryContainer),
        background = c(palette.background),
        onBackground = c(palette.onBackground),
        surface = c(palette.surface),
        onSurface = c(palette.onSurface),
        surfaceVariant = c(palette.surfaceVariant),
        onSurfaceVariant = c(palette.onSurfaceVariant),
        surfaceContainer = c(palette.surface),
        surfaceContainerLow = c(palette.background),
        surfaceContainerHigh = c(palette.surfaceVariant),
        outline = c(palette.outline),
        outlineVariant = c(palette.outline),
        error = c(palette.error),
        onError = c(palette.onError)
    )
}

private fun typographyOf(role: UserRole): Typography {
    val base = Typography()
    // System fonts only (no font downloads): rounded-feeling heavy weights for kids, light geometric for parents.
    val family = FontFamily.SansSerif
    fun TextStyle.kid(boost: Int = 1) = copy(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = (
            fontSize.value +
                boost
            ).sp
    )
    fun TextStyle.parent(weight: FontWeight = FontWeight.Normal) =
        copy(fontFamily = family, fontWeight = weight, letterSpacing = 0.2.sp)
    return when (role) {
        UserRole.Kid ->
            base.copy(
                headlineLarge = base.headlineLarge.kid(2),
                headlineMedium = base.headlineMedium.kid(2),
                headlineSmall = base.headlineSmall.kid(),
                titleLarge = base.titleLarge.kid(),
                titleMedium = base.titleMedium.kid(),
                titleSmall = base.titleSmall.kid(),
                bodyLarge = base.bodyLarge.copy(fontFamily = family, fontSize = 18.sp),
                bodyMedium = base.bodyMedium.copy(fontFamily = family, fontSize = 16.sp),
                labelLarge = base.labelLarge.kid()
            )

        UserRole.Parent ->
            base.copy(
                headlineLarge = base.headlineLarge.parent(FontWeight.Medium),
                headlineMedium = base.headlineMedium.parent(FontWeight.Medium),
                headlineSmall = base.headlineSmall.parent(FontWeight.Medium),
                titleLarge = base.titleLarge.parent(FontWeight.Medium),
                titleMedium = base.titleMedium.parent(FontWeight.Medium),
                bodyLarge = base.bodyLarge.parent(),
                bodyMedium = base.bodyMedium.parent(),
                labelLarge = base.labelLarge.parent(FontWeight.Medium)
            )
    }
}

private fun shapesOf(role: UserRole): Shapes = when (role) {
    UserRole.Kid ->
        Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp)
        )

    UserRole.Parent ->
        Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp)
        )
}

/** The ZapaKid look for [role], light or dark; text sizes still follow the system font scale. */
@Composable
fun ZapaTheme(role: UserRole, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUserRole provides role) {
        MaterialTheme(
            colorScheme = colorSchemeOf(Palettes.of(role, dark), dark),
            typography = typographyOf(role),
            shapes = shapesOf(role),
            content = content
        )
    }
}
