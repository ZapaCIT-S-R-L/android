package ro.zapakid.app.family

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A paired kid phone reads its family with the kid session (GET /v1/families); it can't change it. */
@OptIn(ExperimentalCoroutinesApi::class)
class KidFamilyTest {
    private val family = Family("f1", "Pop", parents = listOf(FamilyParent("p1", "Ana")))
    private val tokens = mutableListOf<String>()

    /** What the API answers next; api main (before #74) answers 401 to a kid token. */
    private var failWith: Exception? = null

    private val api =
        object : FamilyApi {
            override suspend fun myFamily(token: String, name: String?) = error("parents only")

            override suspend fun families(token: String): List<Family> {
                tokens += token
                failWith?.let { throw it }
                return listOf(family)
            }

            override suspend fun inviteParent(token: String, familyId: String) = error("parents only")

            override suspend fun join(token: String, code: String) = error("parents only")

            override suspend fun addKid(
                token: String,
                familyId: String,
                nickname: String,
                avatar: String,
                grade: Int?
            ) = error("parents only")

            override suspend fun pairKid(token: String, familyId: String, kidId: String) = error("parents only")

            override suspend fun redeem(code: String) = error("not here")
        }

    private class Store(var session: KidSession?) : KidSessionStore {
        override fun load() = session

        override fun save(session: KidSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun thePairedKidLoadsItsFamilyWithTheKidSession() = runTest {
        val session = KidSession("zkk_mia", "x", Kid("k1", "Mia", "robot-3", 2), FamilyRef("f1"))
        val vm = KidPairingViewModel(api, Store(session)) { null }

        vm.loadFamily()

        assertEquals(KidFamilyState.Loaded(family), vm.family.value)
        assertEquals(listOf("zkk_mia"), tokens)
    }

    @Test
    fun aRejectedKidTokenShowsTheErrorAndRetryReads() = runTest {
        val session = KidSession("zkk_mia", "x", Kid("k1", "Mia", "robot-3", 2), FamilyRef("f1"))
        val vm = KidPairingViewModel(api, Store(session)) { null }
        failWith = FamilyApiException(401, "unauthorized", "kid tokens not accepted")

        vm.loadFamily()
        assertEquals(KidFamilyState.Failed(FamilyError.SERVER), vm.family.value)

        failWith = java.io.IOException("offline")
        vm.loadFamily()
        assertEquals(KidFamilyState.Failed(FamilyError.NETWORK), vm.family.value)

        failWith = null
        vm.loadFamily()
        assertEquals(KidFamilyState.Loaded(family), vm.family.value)
        assertEquals(3, tokens.size)
    }

    @Test
    fun anUnpairedPhoneStaysLoadingWithoutCallingTheApi() = runTest {
        val vm = KidPairingViewModel(api, Store(null)) { null }

        vm.loadFamily()

        assertEquals(KidFamilyState.Loading, vm.family.value)
        assertEquals(emptyList<String>(), tokens)
    }
}
