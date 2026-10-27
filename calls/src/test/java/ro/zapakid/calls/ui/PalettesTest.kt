package ro.zapakid.calls.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both looks, light and dark, are readable: WCAG AA (4.5:1) for every text/background pair. */
class PalettesTest {
    @Test
    fun theContrastFormulaMatchesWcag() {
        assertEquals(21.0, Palettes.contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Palettes.contrast(0xFF777777, 0xFF777777), 0.01)
    }

    @Test
    fun everyPaletteMeetsAa() {
        for (role in UserRole.entries) {
            for (dark in listOf(false, true)) {
                for ((name, text, background) in Palettes.of(role, dark).readablePairs()) {
                    val ratio = Palettes.contrast(text, background)
                    assertTrue("$role ${if (dark) "dark" else "light"} $name: $ratio", ratio >= AA)
                }
            }
        }
    }

    @Test
    fun parentsAreMintAndLavenderAndKidsYellowAndSky() {
        assertEquals(UserRole.Parent, UserRole.entries.first { !it.playful })
        val kid = Palettes.of(UserRole.Kid, dark = false)
        val parent = Palettes.of(UserRole.Parent, dark = false)
        assertEquals(0xFFFFD166, kid.primaryContainer)
        assertEquals(0xFFBEEBD8, parent.primaryContainer)
        assertEquals(0xFFE4DBF7, parent.secondaryContainer)
    }

    private companion object {
        const val AA = 4.5
    }
}
