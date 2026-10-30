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
 * [generation] guards the build: a sign-out that lands mid-build releases what was built instead of publishing it.
 * A sign-in that arrives while that stale build is still suspended is queued rather than dropped, and the loop
 * builds it once the stale session has been released — one build at a time, because two would race for the
 * account's store.
 */
class SignedInSession<S : OwnedSession>(
    private val scope: CoroutineScope,
    private val shutdown: SessionShutdown,
    private val build: suspend () -> S?
) {
    private val state = MutableStateFlow<S?>(null)
    val services: StateFlow<S?> = state

    private var building: Job? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var buildingGeneration = -1

    @Volatile
    private var queued = false

    /** Builds the session once; every later call — a rotation, a recomposition — reuses what is there. */
    fun start() {
        if (state.value != null) return
        if (building?.isActive == true) {
            // A build of the current generation is already on its way. One belonging to an account that has since
            // signed out is stale: queue this sign-in instead of starting a second build beside it.
            if (buildingGeneration != generation) queued = true
            return
        }
        begin(generation)
    }

    private fun begin(started: Int) {
        buildingGeneration = started
        queued = false
        building =
            scope.launch {
                var attempt = started
                while (true) {
                    shutdown.awaitPending()
                    val built = runCatching { build() }.getOrNull()
                    if (attempt == generation) {
                        // The signed-in account's own attempt: publish it, or stop if it failed — a later
                        // composition calls start() again and retries.
                        if (built == null) return@launch
                        shutdown.attach(built)
                        state.value = built
                        return@launch
                    }
                    // Stale: that account signed out mid-build. Give up whatever it produced, then honour a sign-in
                    // that was queued while this attempt was in flight — including one queued behind a failure,
                    // which would otherwise leave the next account on "setting up" for good.
                    if (built != null) shutdown.releaseOrphan(built).join()
                    if (!queued) return@launch
                    attempt = generation
                    buildingGeneration = attempt
                    queued = false
                }
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
