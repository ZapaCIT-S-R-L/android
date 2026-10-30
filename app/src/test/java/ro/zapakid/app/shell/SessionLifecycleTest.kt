package ro.zapakid.app.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.calls.CallDirection
import ro.zapakid.calls.CallLogEntry
import ro.zapakid.calls.EndReason
import ro.zapakid.calls.InMemoryCallLog
import ro.zapakid.chat.data.ChatStore
import ro.zapakid.chat.data.InMemoryChatStore

/**
 * The retained session through the wiring the app graph builds — [ShellSessions], [PerAccountChatStore],
 * [SessionShutdown] and the call log together — with only the call, the sync and the store faked. These are the
 * paths a sign-out has to get right: a configuration change must not orphan the call, and a teardown that finishes
 * late must not touch the account that signed in after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleTest {
    private val events = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val closed = mutableListOf<String>()
    private val names = mutableMapOf<ChatStore, String>()
    private val log = InMemoryCallLog()
    private val workers = mutableMapOf<String, Job>()
    private var account = "acc-a"
    private var builds = 0
    private var gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
    private var buildGate: CompletableDeferred<Unit>? = null
    private lateinit var sessions: ShellSessions<FakeOwned>
    private lateinit var scope: CoroutineScope

    private fun harness(scope: CoroutineScope) {
        this.scope = scope
        sessions =
            ShellSessions(
                scope,
                scope,
                log,
                openStore = { name ->
                    opened += name
                    InMemoryChatStore().also { names[it] = name }
                },
                closeStore = {
                    val name = names.getValue(it)
                    closed += name
                    events += "store closed $name"
                },
                build = {
                    builds++
                    // The account is fixed when the build starts, not when a suspended build resumes.
                    val mine = account
                    buildGate?.await()
                    sessions.stores.forAccount(mine)
                    workers[mine] =
                        this.scope.launch {
                            try {
                                awaitCancellation()
                            } finally {
                                events += "sync stopped $mine"
                            }
                        }
                    FakeOwned(mine, events, gate) { listOfNotNull(workers[mine]) }
                }
            )
    }

    private fun entry(title: String) = CallLogEntry(
        callId = "call-1",
        conversationId = "conv-1",
        title = title,
        direction = CallDirection.Outgoing,
        reason = EndReason.HungUp,
        startedAtMillis = 0L,
        durationSeconds = 1,
        group = false
    )

    @Test
    fun aConfigurationChangeKeepsTheSessionAndLogoutStillReleasesIt() = runTest {
        harness(this)
        sessions.session.start()
        advanceUntilIdle()
        val first = sessions.session.services.value

        // The composition was replaced, as a rotation replaces it: the session is retained, not rebuilt.
        sessions.session.start()
        advanceUntilIdle()

        assertEquals("one session for two compositions", 1, builds)
        assertSame(first, sessions.session.services.value)

        sessions.signedOut()
        advanceUntilIdle()

        assertEquals(listOf("call released acc-a", "sync stopped acc-a", "store closed acc-a"), events)
        assertNull(sessions.session.services.value)
    }

    @Test
    fun aDelayedTeardownCannotCloseTheStoreTheNextAccountOpened() = runTest {
        gate = CompletableDeferred()
        harness(this)
        sessions.session.start()
        advanceUntilIdle()
        log.add(entry("Ana"))

        sessions.signedOut()
        advanceUntilIdle()
        assertEquals("A's release is still in flight", emptyList<String>(), closed)

        account = "acc-b"
        sessions.session.start()
        advanceUntilIdle()
        assertNull("B is not built on top of A's teardown", sessions.session.services.value)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("only A's store ever closed", listOf("acc-a"), closed)
        assertEquals(listOf("acc-a", "acc-b"), opened)
        assertNotNull(sessions.session.services.value)
        assertEquals("A's call log did not reach B", emptyList<CallLogEntry>(), log.entries.value)

        // B's sync is still running; the test scope must not be left with unfinished work.
        sessions.signedOut()
        advanceUntilIdle()
    }

    @Test
    fun aSecondLogoutDuringADelayedTeardownClosesNothingEarly() = runTest {
        gate = CompletableDeferred()
        harness(this)
        sessions.session.start()
        advanceUntilIdle()

        sessions.signedOut()
        val second = sessions.signedOut()
        advanceUntilIdle()

        assertTrue("the second logout has nothing of its own", second.isCompleted)
        assertEquals("and it does not close the store the first is still using", emptyList<String>(), closed)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("call released acc-a", "sync stopped acc-a", "store closed acc-a"), events)
    }

    @Test
    fun signingOutWithNoFurtherSignInReleasesEverything() = runTest {
        harness(this)
        sessions.session.start()
        advanceUntilIdle()

        val teardown = sessions.signedOut()
        advanceUntilIdle()
        teardown.join()

        assertEquals(listOf("call released acc-a", "sync stopped acc-a", "store closed acc-a"), events)
        assertNull(sessions.session.services.value)
    }

    @Test
    fun aSessionBuiltAfterItsAccountSignedOutIsReleasedInsteadOfPublished() = runTest {
        buildGate = CompletableDeferred()
        harness(this)
        sessions.session.start()
        advanceUntilIdle()

        sessions.signedOut()
        advanceUntilIdle()
        buildGate?.complete(Unit)
        advanceUntilIdle()

        assertNull("nothing is published for an account that is gone", sessions.session.services.value)
        assertEquals(listOf("call released acc-a", "sync stopped acc-a", "store closed acc-a"), events)
    }

    @Test
    fun aSignInDuringASuspendedBuildIsQueuedAndPublishedExactlyOnce() = runTest {
        buildGate = CompletableDeferred()
        harness(this)
        sessions.session.start()
        advanceUntilIdle()
        assertEquals("A's build is suspended on the gate", 1, builds)

        sessions.signedOut()
        advanceUntilIdle()
        account = "acc-b"
        sessions.session.start()
        advanceUntilIdle()

        assertEquals("one build at a time, so B is queued rather than started beside A", 1, builds)
        assertNull(sessions.session.services.value)

        buildGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals("A's stale build was given up and B's built in its place", 2, builds)
        assertEquals("acc-b", sessions.session.services.value?.account)
        assertEquals(
            "A's media, sync and store all stopped before B was built",
            listOf("call released acc-a", "sync stopped acc-a", "store closed acc-a"),
            events
        )
        assertEquals(listOf("acc-a", "acc-b"), opened)
        assertEquals("B's store was left open", listOf("acc-a"), closed)

        sessions.session.start()
        advanceUntilIdle()
        assertEquals("and B is published once, not rebuilt", 2, builds)

        // B's sync is still running; the test scope must not be left with unfinished work.
        sessions.signedOut()
        advanceUntilIdle()
    }
}

/** A signed-in session with a fake call and a fake sync: the store, the log and the shutdown are the real ones. */
private class FakeOwned(
    override val account: String,
    private val events: MutableList<String>,
    private val gate: CompletableDeferred<Unit>,
    private val workerSource: () -> List<Job>
) : OwnedSession {
    override fun releaseCall(cleanup: CoroutineScope): Job = cleanup.launch {
        gate.await()
        events += "call released $account"
    }

    override fun workers(): List<Job> = workerSource()
}
