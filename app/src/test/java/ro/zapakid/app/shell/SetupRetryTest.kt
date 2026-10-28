package ro.zapakid.app.shell

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key setup that failed (network, 5xx, attestation) is retried with backoff, and at once when asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupRetryTest {
    @Test
    fun theBackoffDoublesFromTwoSecondsUpToFiveMinutes() {
        val backoff = Backoff()

        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), (0..3).map(backoff::delayFor))
        assertEquals(300_000L, backoff.delayFor(10))
        assertEquals(300_000L, backoff.delayFor(1_000))
    }

    @Test
    fun failuresAreRetriedAfterTheBackoffUntilItWorks() = runTest {
        var attempts = 0
        val retry = SetupRetry { if (++attempts < 3) null else "sealers" }

        val result = async { retry.run() }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(SetupState.Waiting(failures = 1), retry.state.value)

        advanceTimeBy(1_999)
        assertEquals(1, attempts)
        advanceTimeBy(2)
        assertEquals(2, attempts)
        advanceTimeBy(4_001)

        assertEquals("sealers", result.await())
        assertEquals(3, attempts)
        assertEquals(SetupState.Ready, retry.state.value)
    }

    @Test
    fun tryAgainNetworkBackOrForegroundRetriesAtOnce() = runTest {
        var attempts = 0
        val retry = SetupRetry { if (++attempts < 2) null else 42 }

        val result = async { retry.run() }
        runCurrent()
        retry.retryNow()
        runCurrent()

        assertTrue(result.isCompleted)
        assertEquals(42, result.await())
    }

    @Test
    fun anExceptionCountsAsAFailureNotACrash() = runTest {
        var attempts = 0
        val retry = SetupRetry { if (++attempts == 1) throw IOException("offline") else "ok" }

        val result = async { retry.run() }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(SetupState.Waiting(1), retry.state.value)
        advanceTimeBy(2_001)

        assertEquals("ok", result.await())
    }
}
