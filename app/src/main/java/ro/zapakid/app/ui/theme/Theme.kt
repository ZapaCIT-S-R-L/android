package ro.zapakid.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import ro.zapakid.app.family.KidPairingState
import ro.zapakid.calls.ui.UserRole
import ro.zapakid.calls.ui.ZapaTheme

/** A paired kid phone gets the playful look; everyone else (parents, sign-in) the calm one. */
fun roleFor(kidState: KidPairingState?): UserRole =
    if (kidState is KidPairingState.Paired) UserRole.Kid else UserRole.Parent

/** App theme: the role's colours, type and shapes, light and dark. Text sizes follow the system font scale. */
@Composable
fun ZapaKidTheme(
    role: UserRole = UserRole.Parent,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) = ZapaTheme(role, darkTheme, content)
