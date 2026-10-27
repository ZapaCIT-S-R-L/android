package ro.zapakid.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.zapakid.app.family.FamilyError
import ro.zapakid.app.family.FamilyRef
import ro.zapakid.app.family.Kid
import ro.zapakid.app.family.KidPairingState
import ro.zapakid.app.family.KidSession
import ro.zapakid.calls.ui.UserRole

/** The look follows the session: a paired kid phone is playful, everything else calm. */
class ThemeRoleTest {
    @Test
    fun aPairedKidPhoneGetsTheKidsLook() {
        val paired = KidPairingState.Paired(KidSession("zkk", "x", Kid("k1", "Mia", "robot-3", 2), FamilyRef("f1")))

        assertEquals(UserRole.Kid, roleFor(paired))
    }

    @Test
    fun parentsSignInAndUnpairedPhonesGetTheParentsLook() {
        assertEquals(UserRole.Parent, roleFor(null))
        assertEquals(UserRole.Parent, roleFor(KidPairingState.Waiting))
        assertEquals(UserRole.Parent, roleFor(KidPairingState.Failed(FamilyError.NETWORK)))
    }
}
