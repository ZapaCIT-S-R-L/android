package ro.zapakid.app.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shutdown on its own: the order it releases in, a duplicate logout, and a session that never published. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionShutdownTest {
    private val events = mutableListOf<String>()
    private val closed = mutableListOf<String>()
    private var gate = CompletableDeferred<Unit>().also { it.complete(Unit) }

    private fun TestScope.worker(name: String): Job = launch {
        try {
            awaitCancellation()
        } finally {
            events += name
        }
    }

    private fun owned(account: String, worker: Job) = object : OwnedSession {
        override val account: String = account

        override fun releaseCall(cleanup: CoroutineScope): Job = cleanup.launch {
            gate.await()
            events += "call released $account"
        }

        override fun workers(): List<Job> = listOf(worker)
    }

    @Test
    fun signingOutReleasesTheCallAwaitsTheWorkerAndOnlyThenClosesTheStore() = runTest {
        val shutdown = SessionShutdown(this) { closed += it }
        shutdown.attach(owned("acc-a", worker("sync stopped")))

        shutdown.signedOut()
        advanceUntilIdle()

        assertEquals(listOf("call released acc-a", "sync stopped"), events)
        assertEquals(listOf("acc-a"), closed)
    }

    @Test
    fun aDuplicateLogoutClosesNothingWhileTheFirstTeardownIsStillRunning() = runTest {
        gate = CompletableDeferred()
        val shutdown = SessionShutdown(this) { closed += it }
        shutdown.attach(owned("acc-a", worker("sync stopped")))

        shutdown.signedOut()
        val second = shutdown.signedOut()
        advanceUntilIdle()

        assertTrue("the second call has no session of its own", second.isCompleted)
        assertEquals("so it cannot close the store the first is still using", emptyList<String>(), closed)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("acc-a"), closed)
        assertEquals(listOf("call released acc-a", "sync stopped"), events)
    }

    @Test
    fun withNoLiveSessionThereIsNothingToReleaseAndNothingToClose() = runTest {
        val shutdown = SessionShutdown(this) { closed += it }

        val teardown = shutdown.signedOut()
        advanceUntilIdle()

        assertTrue(teardown.isCompleted)
        assertEquals(emptyList<String>(), closed)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aReplacementWaitsForTheTeardownInFlight() = runTest {
        gate = CompletableDeferred()
        val shutdown = SessionShutdown(this) { closed += it }
        shutdown.attach(owned("acc-a", worker("sync stopped")))
        shutdown.signedOut()

        var proceeded = false
        launch {
            shutdown.awaitPending()
            proceeded = true
        }
        advanceUntilIdle()
        assertFalse("the next sign-in is held until the outgoing one is gone", proceeded)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(proceeded)
        assertEquals(listOf("acc-a"), closed)
    }

    @Test
    fun anOrphanedSessionIsReleasedAndItsOwnStoreClosed() = runTest {
        val shutdown = SessionShutdown(this) { closed += it }

        shutdown.releaseOrphan(owned("acc-a", worker("sync stopped")))
        advanceUntilIdle()

        assertEquals(listOf("call released acc-a", "sync stopped"), events)
        assertEquals(listOf("acc-a"), closed)
    }
}
