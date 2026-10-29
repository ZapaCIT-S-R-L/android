package ro.zapakid.app.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.zapakid.calls.InMemoryCallLog
import ro.zapakid.chat.data.ChatStore

/**
 * The signed-in session, retained by the app graph instead of the composition. A configuration change replaces the
 * composition but not the session, so a rotation neither hangs up nor orphans the call's media, and a later sign-out
 * still finds the resources it has to release.
 *
 * [generation] guards the build: a sign-out that lands mid-build releases what was built instead of publishing it,
 * and a new sign-in waits for the outgoing teardown, so a replacement can never race it.
 */
class SignedInSession<S : OwnedSession>(
    private val scope: CoroutineScope,
    private val shutdown: SessionShutdown,
    private val build: suspend () -> S?
) {
    private val state = MutableStateFlow<S?>(null)
    val services: StateFlow<S?> = state

    private var building: Job? = null
    private var generation = 0

    /** Builds the session once; every later call — a rotation, a recomposition — reuses what is there. */
    fun start() {
        if (state.value != null || building?.isActive == true) return
        val started = generation
        building =
            scope.launch {
                shutdown.awaitPending()
                val built = runCatching { build() }.getOrNull() ?: return@launch
                if (started != generation) {
                    // Signed out while this was building: give it up rather than publish it to a screen that is gone.
                    shutdown.releaseOrphan(built)
                    return@launch
                }
                shutdown.attach(built)
                state.value = built
            }
    }

    /** The phone signed out: forget the session and hand back the teardown that releases it. */
    fun signedOut(): Job {
        generation++
        state.value = null
        return shutdown.signedOut()
    }
}

/**
 * The retained sign-in machinery, wired the way the app graph wires it: one chat store per account, a shutdown that
 * closes only the outgoing account's store, and the session that ties them together. One class, so a test builds
 * exactly what production builds.
 *
 * [scope] carries the session and its work; [cleanup] carries the teardown, which waits on the network and runs
 * while the composition is already going away.
 */
class ShellSessions<S : OwnedSession>(
    scope: CoroutineScope,
    cleanup: CoroutineScope,
    callLog: InMemoryCallLog,
    openStore: (name: String) -> ChatStore,
    closeStore: (ChatStore) -> Unit,
    build: suspend () -> S?
) {
    /** One store per account; a switch drops the previous account's call log. */
    val stores = PerAccountChatStore(openStore, closeStore) { callLog.clear() }

    private val shutdown = SessionShutdown(cleanup, stores::close)

    val session = SignedInSession(scope, shutdown, build)

    /** The phone signed out. Returns the teardown, so a caller can wait for it. */
    fun signedOut(): Job = session.signedOut()
}
