package ro.zapakid.app.keys

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKey
import ro.zapakid.app.auth.AuthApiException
import ro.zapakid.app.auth.Session
import ro.zapakid.app.testing.ANA
import ro.zapakid.app.testing.FAR_FUTURE
import ro.zapakid.app.testing.FakeBlockStore
import ro.zapakid.app.testing.RecordingReshare
import ro.zapakid.app.testing.TestBlobCipher
import java.io.IOException
import java.util.Base64

/** Registering this phone: resolve the account identity, make the keys once, upload them, remember the device ID. */
class DeviceRegistrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeDeviceApi(
        var hasIdentity: Boolean = false,
        var fail: Exception? = null,
    ) : DeviceApi {
        val uploads = mutableListOf<Pair<String, DeviceKeys>>()

        override suspend fun register(
            token: String,
            keys: DeviceKeys,
        ): Int {
            fail?.let {
                fail = null
                throw it
            }
            uploads += token to keys
            return 2
        }

        override suspend fun accountHasIdentity(
            token: String,
            accountId: String,
        ): Boolean = hasIdentity
    }

    private val blockStore = FakeBlockStore()
    private val api = FakeDeviceApi()
    private val reshare = RecordingReshare()
    private var session: Session? = Session("zks_tok", FAR_FUTURE, ANA)
    private val cipher = TestBlobCipher()

    private fun registration(dir: String = "phone") =
        DeviceRegistration(
            records = EncryptedRecordStore(folder.root.resolve(dir), cipher),
            backup = BlockStoreIdentityBackup(blockStore),
            api = api,
            session = { session },
            reshare = reshare,
            oneTimeKeys = 3,
        )

    @Test
    fun aNewAccountRegistersOnceWithKeysSignedByItsIdentity() = runTest {
        assertEquals(DeviceRegistration.Result.Registered(2), registration().register())

        val (token, keys) = api.uploads.single()
        assertEquals("zks_tok", token)
        val identity = IdentityKey(Base64.getDecoder().decode(keys.identityKey)).publicKey
        assertTrue(identity.verifySignature(Base64.getDecoder().decode(keys.signedPreKey.publicKey), Base64.getDecoder().decode(keys.signedPreKey.signature)))
        assertEquals(3, keys.preKeys.size)

        assertEquals("remembered: no second upload", DeviceRegistration.Result.Registered(2), registration().register())
        assertEquals(1, api.uploads.size)
    }

    @Test
    fun theParentsSecondPhoneRegistersWithTheAccountIdentityFromBlockStore() = runTest {
        registration("phone-1").register()
        api.hasIdentity = true

        assertEquals(DeviceRegistration.Result.Registered(2), registration("phone-2").register())
        assertEquals(api.uploads[0].second.identityKey, api.uploads[1].second.identityKey)
        assertEquals(0, reshare.requests)
    }

    @Test
    fun aPhoneWithoutTheAccountIdentityWaitsForAReshareAndUploadsNothing() = runTest {
        api.hasIdentity = true

        assertEquals(DeviceRegistration.Result.WaitingForReshare, registration().register())
        assertTrue(api.uploads.isEmpty())
        assertEquals(1, reshare.requests)
        assertTrue("no keys made", EncryptedRecordStore(folder.root.resolve("phone"), cipher).list("prekey").isEmpty())
    }

    @Test
    fun aFailedUploadIsRetriedWithTheSameKeys() = runTest {
        api.fail = IOException("offline")
        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.NETWORK), registration().register())

        assertEquals(DeviceRegistration.Result.Registered(2), registration().register())
        val records = EncryptedRecordStore(folder.root.resolve("phone"), cipher)
        assertEquals("keys made once", 3, records.list("prekey").size)
        assertTrue(records.get("signed-prekey", api.uploads.single().second.signedPreKey.keyId.toString()) != null)
    }

    @Test
    fun apiErrorsAreReported() = runTest {
        api.fail = AuthApiException(401, "unauthorized", "x")
        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.SIGNED_OUT), registration().register())

        api.fail = AuthApiException(409, "identity_mismatch", "x")
        assertEquals(DeviceRegistration.Result.Failed(DeviceRegistration.Error.IDENTITY_MISMATCH), registration().register())

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
}
