package ro.zapakid.app.shell

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Shuts a signed-in session down in the order that keeps one account's media and data away from the next: the call
 * first (its socket, keys, microphone and peer connection), then the jobs that read or write the chat store, and
 * only then that account's store — the account the session belongs to, not whichever store happens to be open by
 * the time the teardown gets there.
 *
 * It runs on [scope], which the app graph owns rather than the composition: leaving the shell cancels the
 * composition's scope, and cleanup launched into an already cancelling scope never starts.
 *
 * [signedOut] returns the teardown and [awaitPending] waits for it, so a replacement sign-in is serialized behind
 * the outgoing one instead of racing it, and a duplicate logout can neither close a store the first teardown is
 * still using nor close the next account's.
 */
class SessionShutdown(private val scope: CoroutineScope, private val closeStore: (String) -> Unit) {
    private val live = AtomicReference<OwnedSession?>(null)

    @Volatile
    private var pending: Job? = null

    /** The session the shell is rendering now. */
    fun attach(session: OwnedSession) {
        live.set(session)
    }

    /**
     * Gives up a session that finished building after its account signed out: it was never published, so none of it
     * may stay open. Closes only that account's store, which is a no-op once another account has taken over.
     */
    fun releaseOrphan(session: OwnedSession): Job = teardown(session)

    /** Signs the live session out and returns the teardown, so a caller can wait for it. */
    fun signedOut(): Job {
        val session = live.getAndSet(null) ?: return Job().also { it.complete() }
        return teardown(session)
    }

    /** Waits for the teardown in flight, so a replacement cannot open a store the outgoing session still uses. */
    suspend fun awaitPending() {
        pending?.join()
    }

    private fun teardown(session: OwnedSession): Job {
        val job =
            scope.launch {
                session.releaseCall(scope).join()
                val workers = session.workers()
                workers.forEach { it.cancel() }
                workers.joinAll()
                closeStore(session.account)
            }
        pending = job
        return job
    }
}
