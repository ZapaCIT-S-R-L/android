package ro.zapakid.app.shell

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Shuts the signed-in session down when the phone signs out, in the order that keeps one account's media and data
 * away from the next: the call first (its socket, keys, microphone and peer connection), then the work that reads
 * or writes the chat store, and only then the store itself.
 *
 * The shutdown runs on [scope], which the app graph owns rather than the composition. Leaving the shell cancels the
 * composition's scope, and cleanup launched into an already cancelling scope never starts — which is how a signed
 * out call used to keep its microphone open.
 */
class SessionShutdown(private val scope: CoroutineScope, private val closeStore: () -> Unit) {
    /**
     * What one signed-in session holds: how to release its call, and how to find the jobs that use the store (the
     * chat sync). The jobs are read at shutdown time, not when the session registers.
     */
    class Session(val releaseCall: (CoroutineScope) -> Job, val workers: () -> List<Job>)

    private val live = AtomicReference<Session?>(null)

    /** The shell registers the session it just built. */
    fun attach(session: Session) {
        live.set(session)
    }

    /** The shell left: forget the session, but only if no newer one has taken its place. */
    fun detach(session: Session) {
        live.compareAndSet(session, null)
    }

    /**
     * Signs out. Safe with no session at all (a sign-out before the shell was ever built), and safe to call twice:
     * the second call finds nothing live and only closes the store again.
     */
    fun signedOut() {
        val session = live.getAndSet(null)
        scope.launch {
            if (session != null) {
                session.releaseCall(scope).join()
                val workers = session.workers()
                workers.forEach { it.cancel() }
                workers.joinAll()
            }
            closeStore()
        }
    }
}
