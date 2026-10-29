package ro.zapakid.app.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ro.zapakid.calls.InMemoryCallLog
import ro.zapakid.chat.data.InMemoryChatStore

/**
 * The shell's registration of the retained session, on a real composition. The build never completes here, which is
 * the point: a configuration change replaces the composition, and the session that survives it must not be built
 * twice or orphaned.
 */
class ShellRootWiringTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var builds = 0

    @After
    fun tearDown() = scope.cancel()

    private fun deps(): ShellDeps {
        val sessions =
            ShellSessions<Services>(
                scope,
                scope,
                InMemoryCallLog(),
                openStore = { InMemoryChatStore() },
                closeStore = {},
                build = {
                    builds++
                    CompletableDeferred<Unit>().await()
                    null
                }
            )
        return ShellDeps(sessions.session, InMemoryCallLog())
    }

    @Test
    fun replacingTheCompositionReusesTheRetainedSessionInsteadOfBuildingAnother() {
        val deps = deps()

        compose.setContent { MaterialTheme { ShellRoot(deps, kid = false) {} } }
        compose.onNodeWithTag("setting_up").assertIsDisplayed()
        assertEquals("the first composition builds the session", 1, builds)

        // A configuration change: this composition goes away and a new one takes over the same session.
        compose.setContent { MaterialTheme { ShellRoot(deps, kid = false) {} } }
        compose.waitForIdle()

        assertEquals("and the replacement reconnects to it", 1, builds)
        compose.onNodeWithTag("setting_up").assertIsDisplayed()
    }
}
