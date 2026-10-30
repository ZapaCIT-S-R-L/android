package ro.zapakid.app.keys

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.auth.Session
import ro.zapakid.app.testing.ANA
import ro.zapakid.app.testing.FAR_FUTURE
import ro.zapakid.app.testing.FakeBlockStore
import ro.zapakid.app.testing.RecordingReshare
import ro.zapakid.app.testing.TestBlobCipher

/** Registering this phone: resolve the account identity, make the keys once, upload them, remember the device ID. */
class DeviceRegistrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeDeviceApi(var identityKey: ByteArray? = null, var fail: Exception? = null) : DeviceApi {
        /** Successful uploads, in order. */
        val uploads = mutableListOf<Pair<String, DeviceKeys>>()

        /** Every upload the phone tried, failed ones included. */
        val attempts = mutableListOf<DeviceKeys>()

        /** The ID the next registration gets (the server hands out a new ID per registration). */
        var deviceId = 2

        override suspend fun register(token: String, keys: DeviceKeys): Int {
            attempts += keys
            fail?.let {
                fail = null
                throw it
            }
            uploads += token to keys
            return deviceId
        }

        override suspend fun accountIdentityKey(token: String, accountId: String): ByteArray? = identityKey
    }

    private val blockStore = FakeBlockStore()
    private val api = FakeDeviceApi()
    private val reshare = RecordingReshare()
    private var session: Session? = Session("zks_tok0000000000000000000000000000000000000000", FAR_FUTURE, ANA)
    private val cipher = TestBlobCipher()

    private fun registration(dir: String = "phone") = DeviceRegistration(
        accounts = AccountKeys(folder.root.resolve(dir), cipher, blockStore),
        api = api,
        session = { session },
        reshare = reshare,
        oneTimeKeys = 3
    )

    @Test
    fun aNewAccountRegistersOnceWithKeysSignedByItsIdentity() = runTest {
        assertEquals(DeviceRegistration.Result.Registered(2), registration().register())

        val (token, keys) = api.uploads.single()
        assertEquals("zks_tok0000000000000000000000000000000000000000", token)
        val identity = IdentityKey(Base64.getDecoder().decode(keys.identityKey)).publicKey
        assertTrue(
            identity.verifySignature(
                Base64.getDecoder().decode(keys.signedPreKey.publicKey),
                Base64.getDecoder().decode(keys.signedPreKey.signature)
            )
        )
        assertEquals(3, keys.preKeys.size)

        assertEquals("remembered: no second upload", DeviceRegistration.Result.Registered(2), registration().register())
        assertEquals(1, api.uploads.size)
    }

    @Test
    fun theParentsSecondPhoneRegistersWithTheAccountIdentityFromBlockStore() = runTest {
        registration("phone-1").register()
        api.identityKey = Base64.getDecoder().decode(api.uploads[0].second.identityKey)

        assertEquals(DeviceRegistration.Result.Registered(2), registration("phone-2").register())
        assertEquals(api.uploads[0].second.identityKey, api.uploads[1].second.identityKey)
        assertEquals(0, reshare.requests)
    }

    @Test
    fun aPhoneWithoutTheAccountIdentityWaitsForAReshareAndUploadsNothing() = runTest {
        api.identityKey = IdentityKeyPair.generate().publicKey.serialize()

        assertEquals(DeviceRegistration.Result.WaitingForReshare, registration().register())
        assertTrue(api.uploads.isEmpty())
        assertEquals(1, reshare.requests)
        assertTrue(
            "no keys made",
            AccountKeys(folder.root.resolve("phone"), cipher, blockStore).records(ANA.id).list("prekey").isEmpty()
        )
    }

    // Review of #12 (2026-10-28), item 1: a Block Store copy is used only when it matches the registered key.
    @Test
    fun aBlockStoreCopyThatDoesNotMatchTheRegisteredKeyIsNeverUploaded() = runTest {
        registration("phone-1").register()
        api.identityKey = IdentityKeyPair.generate().publicKey.serialize() // the account's key changed elsewhere

        assertEquals(DeviceRegistration.Result.WaitingForReshare, registration("phone-2").register())
        assertEquals("only phone-1 uploaded", 1, api.uploads.size)
        assertEquals(1, reshare.requests)
    }

    @Test
    fun aFailedUploadIsRetriedWithTheSameKeys() = runTest {
        api.fail = IOException("offline")
        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.NETWORK), registration().register())

        assertEquals(DeviceRegistration.Result.Registered(2), registration().register())
        val records = AccountKeys(folder.root.resolve("phone"), cipher, blockStore).records(ANA.id)
        assertEquals("keys made once", 3, records.list("prekey").size)
        assertTrue(records.get("signed-prekey", api.uploads.single().second.signedPreKey.keyId.toString()) != null)
    }

    @Test
    fun apiErrorsAreReported() = runTest {
        api.fail = AuthApiException(401, "unauthorized", "x")
        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.SIGNED_OUT), registration().register())

        api.fail = AuthApiException(409, "identity_mismatch", "x")
        assertEquals(
            DeviceRegistration.Result.Failed(DeviceRegistration.Error.IDENTITY_MISMATCH),
            registration().register()
        )

        api.fail = AuthApiException(429, "rate_limited", "x", retryAfterSeconds = 60)
        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.SERVER), registration().register())
    }

    @Test
    fun withoutASessionNothingHappens() = runTest {
        session = null

        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.SIGNED_OUT), registration().register())
        assertTrue(api.uploads.isEmpty())
        assertTrue(blockStore.items.isEmpty())
    }

    // Review of #13 (2026-10-28), P1: a signed-out phone reports nothing, not the last parent's device ID.
    @Test
    fun aSignedOutPhoneDoesNotReportTheLastParentsDeviceId() = runTest {
        assertEquals(DeviceRegistration.Result.Registered(2), registration().register())

        session = null
        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.SIGNED_OUT), registration().register())
    }

    // Review of #13 (2026-10-28), P1: records are per account (security review H1).
    @Test
    fun twoAccountsOnOnePhoneNeverShareKeysOrDeviceIds() = runTest {
        val b = ANA.copy(id = "0b8d1e2a-9c3f-4d5e-8f60-112233445566", displayName = "Ben")
        assertEquals(DeviceRegistration.Result.Registered(2), registration().register())

        // A logs out, B signs in on the same phone: B registers on its own, with its own identity.
        session = Session("zks_tok0000000000000000000000000000000000000001", FAR_FUTURE, b)
        api.deviceId = 1
        assertEquals(DeviceRegistration.Result.Registered(1), registration().register())
        assertEquals("B registered on its own", 2, api.uploads.size)
        assertFalse("B never gets A's identity", api.uploads[0].second.identityKey == api.uploads[1].second.identityKey)

        // A again, with a new session: A's own identity (not B's), and a new registration, since the server binds a
        // device to the session that registered it.
        session = Session("zks_tok0000000000000000000000000000000000000002", FAR_FUTURE, ANA)
        api.deviceId = 3
        assertEquals(DeviceRegistration.Result.Registered(3), registration().register())
        assertEquals(3, api.uploads.size)
        assertEquals("A's identity again", api.uploads[0].second.identityKey, api.uploads[2].second.identityKey)
        assertFalse("not B's", api.uploads[1].second.identityKey == api.uploads[2].second.identityKey)

        // Same session: remembered.
        assertEquals(DeviceRegistration.Result.Registered(3), registration().register())
        assertEquals(3, api.uploads.size)
    }

    // Review of #13 (2026-10-28), P1: POST /v1/devices binds the session; a new session has no device on the server.
    @Test
    fun aNewSessionOnTheSameAccountRegistersAgainAsANewDevice() = runTest {
        assertEquals(DeviceRegistration.Result.Registered(2), registration().register())

        session = Session("zks_tok0000000000000000000000000000000000000002", FAR_FUTURE, ANA)
        api.deviceId = 3
        assertEquals(DeviceRegistration.Result.Registered(3), registration().register())

        assertEquals(2, api.uploads.size)
        assertEquals("same account identity", api.uploads[0].second.identityKey, api.uploads[1].second.identityKey)
        assertFalse("fresh prekeys", api.uploads[0].second.signedPreKey == api.uploads[1].second.signedPreKey)
        assertEquals(0, reshare.requests)
    }

    @Test
    fun pendingKeysOfAnotherSessionAreNotReused() = runTest {
        api.fail = IOException("offline")
        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.NETWORK), registration().register())

        session = Session("zks_tok0000000000000000000000000000000000000002", FAR_FUTURE, ANA)
        assertEquals(DeviceRegistration.Result.Registered(2), registration().register())

        assertEquals(2, api.attempts.size)
        assertFalse("not the old session's keys", api.attempts[0].signedPreKey == api.attempts[1].signedPreKey)
    }

    @Test
    fun keyFilesDoNotNameTheAccount() = runTest {
        registration().register()
        val paths = folder.root.resolve("phone").walkTopDown().map { it.path }.toList()
        assertTrue(paths.size > 1)
        assertTrue(paths.none { it.contains(ANA.id) })
    }
}
