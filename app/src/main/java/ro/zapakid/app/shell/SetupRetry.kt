package ro.zapakid.app.shell

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Where key setup stands, for the "getting ready" screen. */
sealed interface SetupState {
    data object Working : SetupState

    /** [failures] attempts failed; the next one comes after the backoff, or at once on "Try again". */
    data class Waiting(val failures: Int) : SetupState

    data object Ready : SetupState
}

/** 2 s, doubling, at most 5 minutes. */
class Backoff(private val firstMillis: Long = FIRST, private val maxMillis: Long = MAX) {
    fun delayFor(failure: Int): Long {
        val shift = failure.coerceIn(0, MAX_SHIFT)
        return (firstMillis shl shift).coerceAtMost(maxMillis)
    }

    private companion object {
        const val FIRST = 2_000L
        const val MAX = 300_000L
        const val MAX_SHIFT = 20
    }
}

/**
 * Runs [attempt] until it gives a result: device registration and key setup can fail on the network, a 5xx or
 * attestation. Waits with [backoff] between attempts; [retryNow] (the Try again button, the network coming back,
 * the app coming to the front) skips the wait.
 */
class SetupRetry<T : Any>(private val backoff: Backoff = Backoff(), private val attempt: suspend () -> T?) {
    private val mutableState = MutableStateFlow<SetupState>(SetupState.Working)
    val state: StateFlow<SetupState> = mutableState
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun retryNow() {
        wake.trySend(Unit)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed attempt is retried, never a crash
    suspend fun run(): T {
        var failures = 0
        while (true) {
            mutableState.value = SetupState.Working
            val result =
                try {
                    attempt()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            if (result != null) {
                mutableState.value = SetupState.Ready
                return result
            }
            mutableState.value = SetupState.Waiting(++failures)
            wake.tryReceive() // an old wake-up doesn't skip this wait
            withTimeoutOrNull(backoff.delayFor(failures - 1)) { wake.receive() }
        }
    }
}

/** Calls [onAvailable] whenever a network comes back; returns the unregister action. */
fun watchNetwork(context: Context, onAvailable: () -> Unit): () -> Unit {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
        }
    connectivity.registerDefaultNetworkCallback(callback)
    return { connectivity.unregisterNetworkCallback(callback) }
}
