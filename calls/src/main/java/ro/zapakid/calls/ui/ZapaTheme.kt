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

/** This look's tokens (the iOS colours, plus a text-safe error): bubbles, call buttons, speaking ring, card tints. */
val LocalPalette = staticCompositionLocalOf { Palettes.parentLight }

/** This look's corner radius, spacing, minimum tap size, shadows and sparkles. */
val LocalMetrics = staticCompositionLocalOf { Metrics.of(UserRole.Parent) }

fun colorSchemeOf(palette: Palette, dark: Boolean): ColorScheme {
    fun c(v: Long) = Color(v)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(palette.accent),
        onPrimary = c(palette.onAccent),
        primaryContainer = c(palette.primary),
        onPrimaryContainer = c(palette.onPrimary),
        secondary = c(palette.secondary),
        onSecondary = c(palette.onSecondary),
        secondaryContainer = c(palette.secondary),
        onSecondaryContainer = c(palette.onSecondary),
        tertiaryContainer = c(palette.theirBubble),
        onTertiaryContainer = c(palette.theirText),
        background = c(palette.background),
        onBackground = c(palette.text),
        surface = c(palette.surface),
        onSurface = c(palette.text),
        surfaceVariant = c(palette.background),
        onSurfaceVariant = c(palette.secondaryText),
        surfaceContainer = c(palette.surface),
        surfaceContainerLow = c(palette.background),
        surfaceContainerHigh = c(palette.surface),
        outline = c(palette.separator),
        outlineVariant = c(palette.separator),
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

private fun shapesOf(metrics: Metrics): Shapes {
    val corner = metrics.cornerDp.dp
    return Shapes(
        extraSmall = RoundedCornerShape(corner / 2),
        small = RoundedCornerShape(corner),
        medium = RoundedCornerShape(corner),
        large = RoundedCornerShape(corner),
        extraLarge = RoundedCornerShape(corner * EXTRA_LARGE)
    )
}

/** The ZapaKid look for [role], light or dark; text sizes still follow the system font scale. */
@Composable
fun ZapaTheme(role: UserRole, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = Palettes.of(role, dark)
    val metrics = Metrics.of(role)
    CompositionLocalProvider(
        LocalUserRole provides role,
        LocalPalette provides palette,
        LocalMetrics provides metrics
    ) {
        MaterialTheme(
            colorScheme = colorSchemeOf(palette, dark),
            typography = typographyOf(role),
            shapes = shapesOf(metrics),
            content = content
        )
    }
}

/** Sheets and dialogs: one and a half times the card corner. */
private const val EXTRA_LARGE = 1.5f
