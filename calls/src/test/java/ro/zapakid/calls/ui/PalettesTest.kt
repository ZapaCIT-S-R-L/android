package ro.zapakid.calls.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both looks, light and dark, use the iOS colour tokens and stay readable (WCAG AA). */
class PalettesTest {
    @Test
    fun theContrastFormulaMatchesWcag() {
        assertEquals(21.0, Palettes.contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Palettes.contrast(0xFF777777, 0xFF777777), 0.01)
    }

    @Test
    fun everyTextPairMeetsAa() {
        for (role in UserRole.entries) {
            for (dark in listOf(false, true)) {
                for ((name, text, background) in Palettes.of(role, dark).readablePairs()) {
                    val ratio = Palettes.contrast(text, background)
                    assertTrue("$role ${if (dark) "dark" else "light"} $name: $ratio", ratio >= TEXT_AA)
                }
            }
        }
    }

    @Test
    fun iconOnlyButtonsMeetTheNonTextMinimum() {
        for (role in UserRole.entries) {
            for (dark in listOf(false, true)) {
                for ((name, icon, background) in Palettes.of(role, dark).iconPairs()) {
                    val ratio = Palettes.contrast(icon, background)
                    assertTrue("$role $dark $name: $ratio", ratio >= NON_TEXT_AA)
                }
            }
        }
    }

    /** Material `error` is small text on bubbles and surfaces: it must not be the hang-up button's red. */
    @Test
    fun theErrorTextIsReadableOnEverySurfaceAndIsNotTheHangUpRed() {
        for (role in UserRole.entries) {
            for (dark in listOf(false, true)) {
                val palette = Palettes.of(role, dark)
                val look = "$role ${if (dark) "dark" else "light"}"
                val surfaces =
                    listOf(palette.background, palette.surface, palette.myBubble, palette.theirBubble) +
                        palette.cardTints
                for (surface in surfaces) {
                    val ratio = Palettes.contrast(palette.error, surface)
                    assertTrue("$look error on ${surface.toString(16)}: $ratio", ratio >= TEXT_AA)
                }
                assertTrue("$look onError on error", Palettes.contrast(palette.onError, palette.error) >= TEXT_AA)
                assertTrue("$look error is the hang-up red", palette.error != palette.hangUp)
                assertEquals(if (dark) 0xFFFFB4AB else 0xFFB3261E, palette.error)
            }
        }
    }

    @Test
    fun theTokensAreTheIosOnes() {
        val parentLight = Palettes.of(UserRole.Parent, dark = false)
        assertEquals(0xFFF7F6F2, parentLight.background)
        assertEquals(0xFF1F7A62, parentLight.accent)
        assertEquals(0xFF9EE3C9, parentLight.primary)
        assertEquals(0xFFCDEFE2, parentLight.myBubble)
        assertEquals(0xFF3FB58F, parentLight.speakingRing)
        val parentDark = Palettes.of(UserRole.Parent, dark = true)
        assertEquals(0xFF121416, parentDark.background)
        assertEquals(0xFF2A2540, parentDark.theirBubble)
        val kidLight = Palettes.of(UserRole.Kid, dark = false)
        assertEquals(0xFFFFFBEA, kidLight.background)
        assertEquals(0xFFFFD54A, kidLight.primary)
        assertEquals(0xFF1A6FAA, kidLight.callButton)
        assertEquals(listOf(0xFFFFF4CF, 0xFFE1F2FF, 0xFFE3F6E3, 0xFFFFFFFF), kidLight.cardTints)
        val kidDark = Palettes.of(UserRole.Kid, dark = true)
        assertEquals(0xFF1A1A2E, kidDark.background)
        assertEquals(listOf(0xFF33304A, 0xFF23344D, 0xFF24402F, 0xFF24243A), kidDark.cardTints)
        for (role in UserRole.entries) {
            for (dark in listOf(false, true)) assertEquals(0xFF218C4A, Palettes.of(role, dark).accept)
        }
    }

    @Test
    fun parentsAreCalmAndKidsPlayful() {
        val parent = Metrics.of(UserRole.Parent)
        val kid = Metrics.of(UserRole.Kid)
        assertEquals(listOf(14, 16, 48), listOf(parent.cornerDp, parent.spacingDp, parent.minTapDp))
        assertEquals(listOf(24, 14, 64), listOf(kid.cornerDp, kid.spacingDp, kid.minTapDp))
        assertEquals(listOf(false, false), listOf(parent.shadows, parent.sparkles))
        assertEquals(listOf(true, true), listOf(kid.shadows, kid.sparkles))
    }

    private companion object {
        const val TEXT_AA = 4.5
        const val NON_TEXT_AA = 3.0
    }
}
