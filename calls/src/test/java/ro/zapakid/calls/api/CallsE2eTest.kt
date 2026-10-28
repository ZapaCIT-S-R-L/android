package ro.zapakid.calls.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ro.zapakid.calls.crypto.CallDevice
import ro.zapakid.calls.crypto.CallKeyManager
import ro.zapakid.calls.crypto.FakeCallKeySealer
import ro.zapakid.calls.crypto.KeyRing

/**
 * End to end against a local calls service (`wrangler dev` in zapakid-api/services/calls with its fake Realtime
 * and TURN on loopback). Skipped unless CALLS_E2E_URL is set, for example:
 *
 *     CALLS_E2E_URL=http://127.0.0.1:8787/v1/ CALLS_E2E_CONVERSATION=conv-e2e \
 *     CALLS_E2E_TOKEN_A=… CALLS_E2E_TOKEN_B=… ./gradlew :calls:testDebugUnitTest --tests '*CallsE2eTest*'
 *
 * The tokens are test chat tokens for two devices in that conversation, minted by the service's test issuer;
 * CALLS_E2E_DEVICE_A/B are their libsignal device ids (1-127). Device A must sort first (`<account>.<device>`).
 */
class CallsE2eTest {
    private val url = System.getenv("CALLS_E2E_URL")

    private class Token(private val value: String) : ChatTokenProvider {
        override suspend fun token() = value

        override suspend fun refresh() = value
    }

    private fun env(name: String): String = requireNotNull(System.getenv(name)) { "$name is not set" }

    private suspend fun Flow<CallSignal>.rosterOf(size: Int) = withTimeout(TIMEOUT) {
        filterIsInstance<CallSignal.Roster>().first { it.participants.size == size }
    }

    @Test
    fun signallingRosterAndKeyRelayBetweenTwoPhones() = runBlocking {
        assumeTrue("set CALLS_E2E_URL to run against a local calls service", url != null)
        val base = url!!.toHttpUrl()
        val http = OkHttpClient()
        val a = CallDevice(env("CALLS_E2E_ACCOUNT_A"), env("CALLS_E2E_DEVICE_A"))
        val b = CallDevice(env("CALLS_E2E_ACCOUNT_B"), env("CALLS_E2E_DEVICE_B"))
        val tokenA = Token(env("CALLS_E2E_TOKEN_A"))
        val tokenB = Token(env("CALLS_E2E_TOKEN_B"))
        val apiA = HttpCallsApi(base, http, tokenA)
        val apiB = HttpCallsApi(base, http, tokenB)

        val ticket = apiA.create(env("CALLS_E2E_CONVERSATION"))
        val socketA = CallEventsClient(base, http, tokenA, a).connect(ticket.callId)
        assertEquals(listOf(a), socketA.signals.rosterOf(1).participants.map { it.device })

        apiB.join(ticket.callId)
        val socketB = CallEventsClient(base, http, tokenB, b).connect(ticket.callId)
        val both = socketA.signals.rosterOf(2)
        val roster = both.participants.map { it.device }
        assertEquals(listOf(a, b), roster)

        val ringA = KeyRing()
        val ringB = KeyRing()
        val keysA = CallKeyManager(ticket.callId, a, FakeCallKeySealer(a), ringA)
        val keysB = CallKeyManager(ticket.callId, b, FakeCallKeySealer(b), ringB)
        keysA.onRoster(listOf(a))
        keysB.onRoster(roster)
        assertTrue(socketA.sendKeys(both.epoch, keysA.onRoster(roster, both.epoch)))

        val relayed = withTimeout(TIMEOUT) { socketB.signals.filterIsInstance<CallSignal.Key>().first() }
        assertTrue("B opens the relayed key", keysB.onEnvelope(relayed.envelope))
        assertTrue(ringA.current!!.bytes().contentEquals(ringB.current!!.bytes()))

        apiB.leave(ticket.callId)
        assertEquals(listOf(a), socketA.signals.rosterOf(1).participants.map { it.device })
        apiA.leave(ticket.callId)
        socketA.close()
        socketB.close()
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
