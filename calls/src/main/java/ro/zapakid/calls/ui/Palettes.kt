package ro.zapakid.calls.ui

/**
 * The two ZapaKid looks, as plain ARGB values so they can be checked without Android:
 * - parents, "Calm & modern": soft mint and lavender on off-white, thin lines, small corners;
 * - kids, "Playful & bright": warm yellow and sky blue, chunky rounded cards, big targets.
 * Every text/background pair used by the Material roles meets WCAG AA (4.5:1), in light and dark.
 */
enum class UserRole { Parent, Kid }

data class Palette(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val error: Long,
    val onError: Long
) {
    /** Text on background pairs that must be readable. */
    fun readablePairs(): List<Triple<String, Long, Long>> = listOf(
        Triple("primary", onPrimary, primary),
        Triple("primaryContainer", onPrimaryContainer, primaryContainer),
        Triple("secondary", onSecondary, secondary),
        Triple("secondaryContainer", onSecondaryContainer, secondaryContainer),
        Triple("tertiaryContainer", onTertiaryContainer, tertiaryContainer),
        Triple("background", onBackground, background),
        Triple("surface", onSurface, surface),
        Triple("surfaceVariant", onSurfaceVariant, surfaceVariant),
        Triple("primary on surface (text buttons)", primary, surface),
        Triple("error", onError, error),
        Triple("error on surface", error, surface)
    )
}

object Palettes {
    val parentLight =
        Palette(
            primary = 0xFF1F7A5C, // deep mint
            onPrimary = 0xFFFFFFFF,
            primaryContainer = 0xFFBEEBD8, // soft mint
            onPrimaryContainer = 0xFF0E3B2C,
            secondary = 0xFF6A55A8, // lavender
            onSecondary = 0xFFFFFFFF,
            secondaryContainer = 0xFFE4DBF7, // soft lavender
            onSecondaryContainer = 0xFF2A1F4D,
            tertiaryContainer = 0xFFEDEBE5,
            onTertiaryContainer = 0xFF2B2D33,
            background = 0xFFF7F6F2, // off-white
            onBackground = 0xFF2B2D33,
            surface = 0xFFFBFAF7,
            onSurface = 0xFF2B2D33,
            surfaceVariant = 0xFFEDEBE5,
            onSurfaceVariant = 0xFF55585F,
            outline = 0xFFD9D7D0,
            error = 0xFFB3261E,
            onError = 0xFFFFFFFF
        )

    val parentDark =
        Palette(
            primary = 0xFF7FD9B6,
            onPrimary = 0xFF003826,
            primaryContainer = 0xFF1E5A45,
            onPrimaryContainer = 0xFFC4F2DE,
            secondary = 0xFFC7B7F2,
            onSecondary = 0xFF2E2259,
            secondaryContainer = 0xFF463A70,
            onSecondaryContainer = 0xFFE9DFFF,
            tertiaryContainer = 0xFF2C2F2D,
            onTertiaryContainer = 0xFFE4E4DF,
            background = 0xFF151716,
            onBackground = 0xFFE4E4DF,
            surface = 0xFF1B1D1C,
            onSurface = 0xFFE4E4DF,
            surfaceVariant = 0xFF2C2F2D,
            onSurfaceVariant = 0xFFC3C6C0,
            outline = 0xFF4A4E4B,
            error = 0xFFFFB4AB,
            onError = 0xFF690005
        )

    val kidLight =
        Palette(
            primary = 0xFF0B6FA4, // sky blue, deep enough for text
            onPrimary = 0xFFFFFFFF,
            primaryContainer = 0xFFFFD166, // warm yellow
            onPrimaryContainer = 0xFF3D2B00,
            secondary = 0xFF8A5A00, // honey
            onSecondary = 0xFFFFFFFF,
            secondaryContainer = 0xFFBDE6FB, // light sky
            onSecondaryContainer = 0xFF06324A,
            tertiaryContainer = 0xFFD9F2D9, // soft green card
            onTertiaryContainer = 0xFF143D14,
            background = 0xFFFFFBF0,
            onBackground = 0xFF1F2933,
            surface = 0xFFFFFFFF,
            onSurface = 0xFF1F2933,
            surfaceVariant = 0xFFFFF1CC, // yellow card
            onSurfaceVariant = 0xFF4A3B12,
            outline = 0xFFF2C14E,
            error = 0xFFB3261E,
            onError = 0xFFFFFFFF
        )

    val kidDark =
        Palette(
            primary = 0xFF8FD3F7,
            onPrimary = 0xFF00344F,
            primaryContainer = 0xFF6B4E00,
            onPrimaryContainer = 0xFFFFE3A3,
            secondary = 0xFFFFD166,
            onSecondary = 0xFF3D2B00,
            secondaryContainer = 0xFF0E4A6B,
            onSecondaryContainer = 0xFFCDEBFC,
            tertiaryContainer = 0xFF1F4A1F,
            onTertiaryContainer = 0xFFD2F2D2,
            background = 0xFF1B1A17,
            onBackground = 0xFFF2EEE3,
            surface = 0xFF23211D,
            onSurface = 0xFFF2EEE3,
            surfaceVariant = 0xFF3A3424,
            onSurfaceVariant = 0xFFE8DDBF,
            outline = 0xFF8C7A45,
            error = 0xFFFFB4AB,
            onError = 0xFF690005
        )

    fun of(role: UserRole, dark: Boolean): Palette = when (role) {
        UserRole.Parent -> if (dark) parentDark else parentLight
        UserRole.Kid -> if (dark) kidDark else kidLight
    }

    /** WCAG 2.x contrast ratio of two opaque ARGB colours. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + OFFSET) / (minOf(la, lb) + OFFSET)
    }

    @Suppress("MagicNumber") // the red, green and blue bytes of ARGB
    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and MASK) / MAX
            return if (c <= LINEAR_LIMIT) c / LINEAR_DIVISOR else Math.pow((c + GAMMA_OFFSET) / GAMMA_DIVISOR, GAMMA)
        }
        return RED * channel(16) + GREEN * channel(8) + BLUE * channel(0)
    }

    private const val OFFSET = 0.05
    private const val MASK = 0xFFL
    private const val MAX = 255.0
    private const val LINEAR_LIMIT = 0.03928
    private const val LINEAR_DIVISOR = 12.92
    private const val GAMMA_OFFSET = 0.055
    private const val GAMMA_DIVISOR = 1.055
    private const val GAMMA = 2.4
    private const val RED = 0.2126
    private const val GREEN = 0.7152
    private const val BLUE = 0.0722
}
