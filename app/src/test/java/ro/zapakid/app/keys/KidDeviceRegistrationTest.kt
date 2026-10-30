package ro.zapakid.app.keys

import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKeyPair
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.family.FamilyRef
import ro.zapakid.app.family.Kid
import ro.zapakid.app.family.KidSession
import ro.zapakid.app.testing.FakeBlockStore
import ro.zapakid.app.testing.TestBlobCipher

/**
 * A kid's phone after pairing (api PR #74): its own libsignal identity (kids have no Block Store sync), POST
 * /v1/devices with the kid session, PUT /v1/keys/identity for a replaced phone, and a revoked phone wiped.
 */
class KidDeviceRegistrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val kidToken = "zkk_" + "k".repeat(43)
    private val mia = KidSession(kidToken, "2027-10-28T00:00:00Z", Kid("kid-mia", "Mia", "robot-3", 2), FamilyRef("f1"))

    private class FakeDeviceApi : DeviceApi {
        val calls = mutableListOf<String>()
        var registerError: AuthApiException? = null
        var changeError: AuthApiException? = null
        val uploaded = mutableListOf<DeviceKeys>()

        override suspend fun register(token: String, keys: DeviceKeys): Int {
            calls += "register $token"
            registerError?.let { throw it }
            uploaded += keys
            return 1
        }

        override suspend fun changeIdentity(token: String, keys: DeviceKeys): Int {
            calls += "identity $token"
            changeError?.let { throw it }
            uploaded += keys
            return 1
        }

        override suspend fun accountHasIdentity(token: String, accountId: String): Boolean = error("kids don't ask")
    }

    private val api = FakeDeviceApi()
    private val accounts by lazy { AccountKeys(folder.root.resolve("keys"), TestBlobCipher(), FakeBlockStore()) }
    private var session: KidSession? = mia
    private var revoked = 0

    private fun registration() = KidDeviceRegistration(accounts, api, {
        session
    }, onRevoked = { revoked++ }, oneTimeKeys = 3)

    @Test
    fun afterPairingThePhoneMakesItsIdentityAndRegistersWithTheKidSession() = runTest {
        assertEquals(DeviceRegistration.Result.Registered(1), registration().register())

        assertEquals(listOf("register $kidToken"), api.calls)
        val keys = api.uploaded.single()
        val records = accounts.records("kid-mia")
        val identity = IdentityKeyPair(records.get("local-identity", "identity-key-pair")!!)
        assertEquals(Base64.getEncoder().encodeToString(identity.publicKey.serialize()), keys.identityKey)
        assertEquals(3, keys.preKeys.size)
    }

    @Test
    fun aRegisteredPhoneDoesNotRegisterAgain() = runTest {
        registration().register()

        assertEquals(DeviceRegistration.Result.Registered(1), registration().register())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aReplacedPhoneChangesTheKidsIdentityRightAfterPairing() = runTest {
        api.registerError = AuthApiException(409, "identity_mismatch", "not the account's key")

        assertEquals(DeviceRegistration.Result.Registered(1), registration().register())
        assertEquals(listOf("register $kidToken", "identity $kidToken"), api.calls)
    }

    @Test
    fun tooLateForAnIdentityChangeAsksForAFreshPairing() = runTest {
        api.registerError = AuthApiException(409, "identity_mismatch", "x")
        api.changeError = AuthApiException(403, "reauthentication_required", "x")

        assertEquals(
            DeviceRegistration.Result.Failed(DeviceRegistration.Error.IDENTITY_MISMATCH),
            registration().register()
        )
        assertEquals(0, revoked)
    }

    @Test
    fun aRevokedPhoneIsReported() = runTest {
        api.registerError = AuthApiException(401, "unauthorized", "x")

        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.SIGNED_OUT), registration().register())
        assertEquals(1, revoked)
    }

    @Test
    fun withoutAKidSessionNothingHappens() = runTest {
        session = null

        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.SIGNED_OUT), registration().register())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun revokingWipesTheKeysTheSessionAndTheChatDatabase() {
        val records = accounts.records("kid-mia")
        records.put("local-identity", "identity-key-pair", IdentityKeyPair.generate().serialize())
        val chatDb = folder.newFile("chat.db")
        val chatKey = folder.newFile("chat-db.key")
        var cleared = false

        val revocation =
            KidRevocation(accounts, kidId = {
                "kid-mia"
            }, clearSession = { cleared = true }, files = listOf(chatDb, chatKey))
        revocation.revoke()

        assertNull(accounts.records("kid-mia").get("local-identity", "identity-key-pair"))
        assertTrue(cleared)
        assertFalse(chatDb.exists())
        assertFalse(chatKey.exists())
        assertEquals("the pairing screen is told", 1, revocation.revocations.value)
    }
}
