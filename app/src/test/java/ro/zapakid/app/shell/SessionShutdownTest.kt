package ro.zapakid.app.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sign-out order: the call first, then the work that uses the store, then the store itself. */
class SessionShutdownTest {
    private val events = mutableListOf<String>()

    @Test
    fun signingOutReleasesTheCallWaitsForTheSyncAndOnlyThenClosesTheStore() = runTest {
        val shutdown = SessionShutdown(this) { events += "store closed" }
        val sync = launch {
            try {
                delay(60_000)
                events += "sync finished by itself"
            } finally {
                events += "sync stopped"
            }
        }
        shutdown.attach(
            SessionShutdown.Session(releaseCall = { scope -> scope.launch { events += "call released" } }) {
                listOf(sync)
            }
        )

        shutdown.signedOut()
        advanceUntilIdle()

        assertEquals(listOf("call released", "sync stopped", "store closed"), events)
    }

    @Test
    fun signingOutWithNoShellStillClosesTheStore() = runTest {
        val shutdown = SessionShutdown(this) { events += "store closed" }

        shutdown.signedOut()
        advanceUntilIdle()

        assertEquals("a sign-out before the shell was ever built", listOf("store closed"), events)
    }

    @Test
    fun aShellThatLeftTakesItsSessionWithIt() = runTest {
        val shutdown = SessionShutdown(this) { events += "store closed" }
        val session = SessionShutdown.Session({ it.launch { events += "call released" } }) { emptyList() }
        shutdown.attach(session)

        shutdown.detach(session)
        shutdown.signedOut()
        advanceUntilIdle()

        assertEquals(listOf("store closed"), events)
    }

    @Test
    fun aNewerSessionSurvivesTheOlderShellBeingDisposed() = runTest {
        val shutdown = SessionShutdown(this) { events += "store closed" }
        val first = SessionShutdown.Session({ it.launch { events += "first released" } }) { emptyList() }
        val second = SessionShutdown.Session({ it.launch { events += "second released" } }) { emptyList() }
        shutdown.attach(first)
        shutdown.attach(second)

        // A configuration change disposes the old composition after the new one has attached.
        shutdown.detach(first)
        shutdown.signedOut()
        advanceUntilIdle()

        assertEquals(listOf("second released", "store closed"), events)
    }
}
