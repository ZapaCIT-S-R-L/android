package ro.zapakid.calls.ui

/**
 * The two ZapaKid looks, with the iOS app's colour tokens (so both apps look the same), as plain ARGB values that
 * can be checked without Android. Android-only: [Palette.accept], [Palette.error] (text-safe, the iOS button red is
 * not) and the parents' 48 dp minimum tap size (44 pt on iOS).
 * - parents, "Calm & modern": mint and lavender on off-white, thin lines, 14 dp corners, no shadows;
 * - kids, "Playful & bright": warm yellow and sky blue, chunky 24 dp cards, soft shadows, sparkles.
 */
enum class UserRole { Parent, Kid }

data class Palette(
    val background: Long,
    val surface: Long,
    val separator: Long,
    val text: Long,
    val secondaryText: Long,
    /** Links, toggles, text buttons. */
    val accent: Long,
    val onAccent: Long,
    val primary: Long,
    val onPrimary: Long,
    val secondary: Long,
    val onSecondary: Long,
    val myBubble: Long,
    val myText: Long,
    val theirBubble: Long,
    val theirText: Long,
    val callButton: Long,
    val callIcon: Long,
    /** The hang-up button only: an icon-only red, not readable as text on light bubbles or in the dark looks. */
    val hangUp: Long,
    val hangUpIcon: Long,
    /** Error text (failed message, missed call, sign-in error) on any surface, bubble or card: Material 3's. */
    val error: Long,
    /** Icons on an [error] fill (the recording button). */
    val onError: Long,
    val speakingRing: Long,
    /** Kids' chat cards (parents don't use them). */
    val cardTints: List<Long>,
    val accept: Long = 0xFF218C4A,
    val onAccept: Long = 0xFFFFFFFF
) {
    /** Text on background pairs: WCAG AA 4.5:1. */
    fun readablePairs(): List<Triple<String, Long, Long>> = listOf(
        Triple("text on background", text, background),
        Triple("text on surface", text, surface),
        Triple("secondary text on background", secondaryText, background),
        Triple("secondary text on surface", secondaryText, surface),
        Triple("accent on surface", accent, surface),
        Triple("accent on background", accent, background),
        Triple("on accent", onAccent, accent),
        Triple("on primary", onPrimary, primary),
        Triple("on secondary", onSecondary, secondary),
        Triple("my bubble", myText, myBubble),
        Triple("their bubble", theirText, theirBubble),
        Triple("hang-up", hangUpIcon, hangUp),
        Triple("error on background", error, background),
        Triple("error on surface", error, surface),
        Triple("error on my bubble", error, myBubble),
        Triple("error on their bubble", error, theirBubble),
        Triple("on error", onError, error)
    ) +
        cardTints.flatMap {
            listOf(
                Triple("text on card", text, it),
                Triple("secondary text on card", secondaryText, it),
                Triple("error on card", error, it)
            )
        }

    /** Icon-only buttons: WCAG non-text contrast 3:1 (their labels sit under them, on the background). */
    fun iconPairs(): List<Triple<String, Long, Long>> = listOf(
        Triple("call button", callIcon, callButton),
        Triple("accept call", onAccept, accept),
        Triple("hang-up", hangUpIcon, hangUp)
    )
}

/** Corner radius, spacing, minimum tap size and decorations per look. */
data class Metrics(
    val cornerDp: Int,
    val spacingDp: Int,
    val minTapDp: Int,
    val shadows: Boolean,
    val sparkles: Boolean
) {
    companion object {
        private val parent = Metrics(cornerDp = 14, spacingDp = 16, minTapDp = 48, shadows = false, sparkles = false)
        private val kid = Metrics(cornerDp = 24, spacingDp = 14, minTapDp = 64, shadows = true, sparkles = true)

        fun of(role: UserRole): Metrics = if (role == UserRole.Kid) kid else parent
    }
}

object Palettes {
    val parentLight =
        Palette(
            background = 0xFFF7F6F2,
            surface = 0xFFFFFFFF,
            separator = 0xFFE4E2DC,
            text = 0xFF1F2A2E,
            secondaryText = 0xFF5C6670,
            accent = 0xFF1F7A62,
            onAccent = 0xFFFFFFFF,
            primary = 0xFF9EE3C9,
            onPrimary = 0xFF14302A,
            secondary = 0xFFDCD2F5,
            onSecondary = 0xFF3A2A6B,
            myBubble = 0xFFCDEFE2,
            myText = 0xFF1F2A2E,
            theirBubble = 0xFFECE6FA,
            theirText = 0xFF1F2A2E,
            callButton = 0xFF9EE3C9,
            callIcon = 0xFF14302A,
            hangUp = 0xFFC93A3A,
            hangUpIcon = 0xFFFFFFFF,
            error = 0xFFB3261E,
            onError = 0xFFFFFFFF,
            speakingRing = 0xFF3FB58F,
            cardTints = emptyList()
        )

    val parentDark =
        Palette(
            background = 0xFF121416,
            surface = 0xFF1C1F22,
            separator = 0xFF2C3034,
            text = 0xFFECEFF1,
            secondaryText = 0xFFA7B0B8,
            accent = 0xFF7FD8B8,
            onAccent = 0xFF0E1F19,
            primary = 0xFF7FD8B8,
            onPrimary = 0xFF0E1F19,
            secondary = 0xFF3B3360,
            onSecondary = 0xFFE6DEFF,
            myBubble = 0xFF1F3D33,
            myText = 0xFFECEFF1,
            theirBubble = 0xFF2A2540,
            theirText = 0xFFECEFF1,
            callButton = 0xFF7FD8B8,
            callIcon = 0xFF0E1F19,
            hangUp = 0xFFB83232,
            hangUpIcon = 0xFFFFFFFF,
            error = 0xFFFFB4AB,
            onError = 0xFF601410,
            speakingRing = 0xFF7FD8B8,
            cardTints = emptyList()
        )

    val kidLight =
        Palette(
            background = 0xFFFFFBEA,
            surface = 0xFFFFFFFF,
            separator = 0xFFF2E3A6,
            text = 0xFF2B2B2B,
            secondaryText = 0xFF5A5A5A,
            accent = 0xFF1A6FAA,
            onAccent = 0xFFFFFFFF,
            primary = 0xFFFFD54A,
            onPrimary = 0xFF3A2E00,
            secondary = 0xFF4FB3F0,
            onSecondary = 0xFF0B2A40,
            myBubble = 0xFFCDEBFF,
            myText = 0xFF13324A,
            theirBubble = 0xFFFFF1B8,
            theirText = 0xFF3A2E00,
            callButton = 0xFF1A6FAA,
            callIcon = 0xFFFFFFFF,
            hangUp = 0xFFC93A3A,
            hangUpIcon = 0xFFFFFFFF,
            error = 0xFFB3261E,
            onError = 0xFFFFFFFF,
            speakingRing = 0xFFF5B700,
            cardTints = listOf(0xFFFFF4CF, 0xFFE1F2FF, 0xFFE3F6E3, 0xFFFFFFFF)
        )

    val kidDark =
        Palette(
            background = 0xFF1A1A2E,
            surface = 0xFF24243A,
            separator = 0xFF3A3A55,
            text = 0xFFF5F5F5,
            secondaryText = 0xFFB9B9C9,
            accent = 0xFF8CCBFF,
            onAccent = 0xFF0B2A40,
            primary = 0xFFFFD54A,
            onPrimary = 0xFF3A2E00,
            secondary = 0xFF4FB3F0,
            onSecondary = 0xFF0B2A40,
            myBubble = 0xFF1D3B57,
            myText = 0xFFF5F5F5,
            theirBubble = 0xFF4A3F12,
            theirText = 0xFFF5F5F5,
            callButton = 0xFF1A6FAA,
            callIcon = 0xFFFFFFFF,
            hangUp = 0xFFB83232,
            hangUpIcon = 0xFFFFFFFF,
            error = 0xFFFFB4AB,
            onError = 0xFF601410,
            speakingRing = 0xFFFFD54A,
            cardTints = listOf(0xFF33304A, 0xFF23344D, 0xFF24402F, 0xFF24243A)
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
