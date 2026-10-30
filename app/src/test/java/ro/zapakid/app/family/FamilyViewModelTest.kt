package ro.zapakid.app.family

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The parent's family flow and the kid's pairing flow, with a fake account API. */
@OptIn(ExperimentalCoroutinesApi::class)
class FamilyViewModelTest {
    private val pairingCode = "zkp_" + "p".repeat(43)
    private val inviteCode = "zki_" + "i".repeat(43)

    private class FakeApi(val pairingCode: String, val inviteCode: String) : FamilyApi {
        var family = Family("f1", "Pop", parents = listOf(FamilyParent("p1", "Ana")))
        var redeemError: FamilyApiException? = null
        val calls = mutableListOf<String>()

        override suspend fun myFamily(token: String, name: String?) = family.also { calls += "my $token" }

        override suspend fun families(token: String) = listOf(family)

        override suspend fun inviteParent(token: String, familyId: String) = OneTimeCode(inviteCode, "x").also {
            calls +=
                "invite"
        }

        override suspend fun join(token: String, code: String) = family.also { calls += "join $code" }

        override suspend fun addKid(token: String, familyId: String, nickname: String, avatar: String, grade: Int?) =
            Kid("k1", nickname, avatar, grade).also { calls += "kid $nickname $avatar $grade" }

        override suspend fun pairKid(token: String, familyId: String, kidId: String) =
            KidPairing(pairingCode, "x", Kid(kidId, "Mia", "robot-3", 2)).also { calls += "pair $kidId" }

        override suspend fun redeem(code: String): PairedKid {
            redeemError?.let { throw it }
            calls += "redeem $code"
            return PairedKid(
                KidSessionToken("zkk_tok", "2027-10-27T00:00:00Z"),
                Kid("k1", "Mia", "robot-3", 2),
                FamilyRef("f1", "Pop")
            )
        }
    }

    private class MemoryKidStore : KidSessionStore {
        var session: KidSession? = null

        override fun load() = session

        override fun save(session: KidSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    private val api = FakeApi(pairingCode, inviteCode)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingCreatesOrLoadsTheFamily() = runTest {
        val vm = FamilyViewModel(api, { "zks_tok" })

        vm.load()

        assertEquals("Pop", vm.state.value.family?.name)
        assertEquals(listOf("my zks_tok"), api.calls)
    }

    @Test
    fun addingAKidShowsItsPairingQrWithTheParentFingerprint() = runTest {
        val vm = FamilyViewModel(api, { "zks_tok" }, fingerprint = { "ab12cd34ef567890" })
        vm.load()

        vm.addKid("Mia", 3, 2)

        assertEquals(listOf("my zks_tok", "kid Mia robot-3 2", "pair k1"), api.calls)
        assertEquals("Mia", vm.state.value.family?.kids?.single()?.nickname)
        val qr = vm.state.value.qr!!
        assertEquals("zapakid://pair?code=$pairingCode&fp=ab12cd34ef567890", qr.payload)
        vm.closeQr()
        assertNull(vm.state.value.qr)
    }

    @Test
    fun invitingTheSecondParentShowsTheJoinLink() = runTest {
        val vm = FamilyViewModel(api, { "zks_tok" })
        vm.load()

        vm.inviteParent()

        assertEquals("https://zapakid.ro/join#$inviteCode", vm.state.value.qr?.payload)
    }

    @Test
    fun theKidPhoneRedeemsTheQrAndKeepsTheSession() = runTest {
        val store = MemoryKidStore()
        val vm = KidPairingViewModel(api, store) { null }

        vm.redeem("zapakid://pair?code=$pairingCode&fp=ab12cd34ef567890")

        val paired = vm.state.value as KidPairingState.Paired
        assertEquals("Mia", paired.session.kid.nickname)
        assertEquals("ab12cd34ef567890", store.session?.parentFingerprint)
        assertEquals("zkk_tok", store.session?.token)
    }

    @Test
    fun aWrongOrUsedQrIsAFriendlyError() = runTest {
        val vm = KidPairingViewModel(api, MemoryKidStore()) { null }

        vm.redeem("https://example.com")
        assertEquals(KidPairingState.Failed(FamilyError.INVALID_CODE), vm.state.value)

        api.redeemError = FamilyApiException(410, "pairing_code_invalid", "used")
        vm.redeem("zapakid://pair?code=$pairingCode")
        assertEquals(KidPairingState.Failed(FamilyError.INVALID_CODE), vm.state.value)
        assertTrue(api.calls.none { it.startsWith("redeem") })
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T = try {
        block()
        throw AssertionError("expected ${T::class.simpleName}")
    } catch (e: Throwable) {
        if (e is T) e else throw e
    }

    private val clock = { Instant.parse("2026-10-30T12:00:00Z") }

    private fun session(expiresAt: String) =
        KidSession("zkk", expiresAt, Kid("k1", "Mia", "robot-3", 2), FamilyRef("f1"))

    private fun pairedVm(store: MemoryKidStore) = KidPairingViewModel(api, store, now = clock) { null }

    @Test
    fun aPairedPhoneStartsPaired() {
        val store = MemoryKidStore().apply { save(session("2026-10-31T00:00:00Z")) }

        assertTrue(pairedVm(store).state.value is KidPairingState.Paired)
        assertTrue(store.session != null)
    }

    @Test
    fun anExpiredSessionIsDroppedOnRestoreAndThePhonePairsAgain() {
        val store = MemoryKidStore().apply { save(session("2026-10-30T11:59:59Z")) }

        assertEquals(KidPairingState.Waiting, pairedVm(store).state.value)
        assertNull(store.session)
    }

    @Test
    fun anExpiryExactlyNowCountsAsExpired() {
        val store = MemoryKidStore().apply { save(session("2026-10-30T12:00:00Z")) }

        assertEquals(KidPairingState.Waiting, pairedVm(store).state.value)
        assertNull(store.session)
    }

    @Test
    fun anUnreadableExpiryFailsClosed() {
        val store = MemoryKidStore().apply { save(session("x")) }

        assertEquals(KidPairingState.Waiting, pairedVm(store).state.value)
        assertNull(store.session)
    }

    @Test
    fun aRevokedSessionIs401ClearsTheStoreAndReturnsToPairing() = runTest {
        val store = MemoryKidStore().apply { save(session("2026-10-31T00:00:00Z")) }
        val vm = pairedVm(store)

        val error = assertFailsWith<FamilyApiException> {
            vm.withSession { throw FamilyApiException(401, "unauthorized", "revoked") }
        }

        assertEquals(401, error.status)
        assertEquals(KidPairingState.Waiting, vm.state.value)
        assertNull(store.session)
    }

    @Test
    fun transientFailuresKeepThePairing() = runTest {
        val store = MemoryKidStore().apply { save(session("2026-10-31T00:00:00Z")) }
        val vm = pairedVm(store)

        assertFailsWith<IOException> { vm.withSession { throw IOException("offline") } }
        assertFailsWith<FamilyApiException> { vm.withSession { throw FamilyApiException(503, "down", "5xx") } }
        assertFailsWith<FamilyApiException> { vm.withSession { throw FamilyApiException(429, "slow", "429") } }

        assertTrue(vm.state.value is KidPairingState.Paired)
        assertTrue(store.session != null)
    }

    @Test
    fun aSessionThatExpiredWhileRunningIsDroppedBeforeTheCall() = runTest {
        val store = MemoryKidStore().apply { save(session("2026-10-30T12:30:00Z")) }
        var now = Instant.parse("2026-10-30T12:00:00Z")
        val vm = KidPairingViewModel(api, store, now = { now }) { null }
        now = Instant.parse("2026-10-30T13:00:00Z")
        var called = false

        assertFailsWith<FamilyApiException> { vm.withSession { called = true } }

        assertTrue(!called)
        assertEquals(KidPairingState.Waiting, vm.state.value)
        assertNull(store.session)
    }

    @Test
    fun withSessionHandsTheStoredSessionToTheCall() = runTest {
        val store = MemoryKidStore().apply { save(session("2026-10-31T00:00:00Z")) }

        assertEquals("zkk", pairedVm(store).withSession { it.token })
    }

    @Test
    fun tryAgainNeverDropsAWorkingSession() {
        val store = MemoryKidStore().apply { save(session("2026-10-31T00:00:00Z")) }
        val vm = pairedVm(store)

        vm.tryAgain()

        assertTrue(vm.state.value is KidPairingState.Paired)
        assertTrue(store.session != null)
    }
}
