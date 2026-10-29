package ro.zapakid.app.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.calls.CallDirection
import ro.zapakid.calls.CallLogEntry
import ro.zapakid.calls.EndReason
import ro.zapakid.calls.InMemoryCallLog
import ro.zapakid.chat.data.ChatStore
import ro.zapakid.chat.data.InMemoryChatStore

/**
 * The sign-out wiring composed the way [ro.zapakid.app.AppGraph] composes it: a [SessionShutdown] whose store
 * closure closes a [PerAccountChatStore], and whose switch hook clears the call log. AppGraph itself needs a
 * Context, BuildConfig and the Keystore, so this covers the composition it builds rather than the graph object.
 */
class SignOutWiringTest {
    private val opened = mutableListOf<String>()
    private val closed = mutableListOf<String>()
    private val names = mutableMapOf<ChatStore, String>()
    private val log = InMemoryCallLog()

    private val stores =
        PerAccountChatStore(
            openStore = { name ->
                opened += name
                InMemoryChatStore().also { names[it] = name }
            },
            closeStore = { closed += names.getValue(it) },
            onSwitch = { log.clear() }
        )

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
    fun signingOutReleasesTheCallAwaitsTheSyncAndClosesThatAccountsStore() = runTest {
        val shutdown = SessionShutdown(this) { stores.close() }
        stores.forAccount("acc-a")
        log.add(entry("Ana"))
        var released = false
        val sync = launch { delay(60_000) }
        shutdown.attach(
            SessionShutdown.Session(releaseCall = { scope -> scope.launch { released = true } }) { listOf(sync) }
        )

        shutdown.signedOut()
        advanceUntilIdle()

        assertTrue("the call was released before the store closed", released)
        assertTrue("the sync was cancelled rather than left writing", sync.isCancelled)
        assertEquals("the signed-in account's store, and only it", listOf("acc-a"), closed)
    }

    @Test
    fun theNextAccountOpensItsOwnStoreAndSeesNoneOfThePreviousAccountsData() = runTest {
        val shutdown = SessionShutdown(this) { stores.close() }
        stores.forAccount("acc-a")
        log.add(entry("Ana"))
        shutdown.attach(SessionShutdown.Session({ it.launch {} }) { emptyList() })

        shutdown.signedOut()
        advanceUntilIdle()
        stores.forAccount("acc-b")

        assertEquals(listOf("acc-a", "acc-b"), opened)
        assertEquals("A's call log did not survive into B", emptyList<CallLogEntry>(), log.entries.value)
    }

    @Test
    fun signingOutBeforeTheShellWasEverBuiltStillClosesTheStore() = runTest {
        val shutdown = SessionShutdown(this) { stores.close() }
        stores.forAccount("acc-a")

        shutdown.signedOut()
        advanceUntilIdle()

        assertEquals(listOf("acc-a"), closed)
    }
}
