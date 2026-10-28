package ro.zapakid.app.keys

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import ro.zapakid.app.testing.Phone

/** This phone's libsignal keys, published as the openapi.yaml DeviceKeys shape (public keys + signatures only). */
class DeviceKeyGeneratorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val b64 = Base64.getDecoder()

    @Test
    fun theIdentityIsCreatedOnceAndKept() {
        val phone = Phone(folder.newFolder("bob"))
        val again = phone.reopen()

        assertArrayEquals(phone.identity.keyPair.serialize(), again.identity.keyPair.serialize())
        assertEquals(phone.identity.registrationId, again.identity.registrationId)
        assertTrue(phone.identity.registrationId in 1..16380)
    }

    @Test
    fun everyPrekeyIsSignedByTheIdentity() {
        val phone = Phone(folder.newFolder("bob"))
        val keys = DeviceKeyGenerator(phone.identity, phone.store).generate(oneTimeCount = 5)
        val identity = IdentityKey(b64.decode(keys.identityKey)).publicKey

        assertTrue(
            identity.verifySignature(b64.decode(keys.signedPreKey.publicKey), b64.decode(keys.signedPreKey.signature))
        )
        for (kyber in keys.pqPreKeys + keys.pqLastResortPreKey) {
            assertTrue(identity.verifySignature(b64.decode(kyber.publicKey), b64.decode(kyber.signature)))
            KEMPublicKey(b64.decode(kyber.publicKey)) // parses as a Kyber key
        }
        for (pre in keys.preKeys) ECPublicKey(b64.decode(pre.publicKey))
        assertEquals(5, keys.preKeys.size)
        assertEquals(5, keys.pqPreKeys.size)
    }

    @Test
    fun theDefaultIsTheContractMaximumOf100AndMoreIsRefused() {
        val phone = Phone(folder.newFolder("bob"))
        val keys = DeviceKeyGenerator(phone.identity, phone.store).generate()
        assertEquals(100, keys.preKeys.size)
        assertEquals(100, keys.pqPreKeys.size)
        assertTrue(
            runCatching {
                DeviceKeyGenerator(phone.identity, phone.store).generate(oneTimeCount = 101)
            }.isFailure
        )
    }

    @Test
    fun keyIdsAreUnique24BitValues() {
        val phone = Phone(folder.newFolder("bob"))
        val keys = DeviceKeyGenerator(phone.identity, phone.store).generate(oneTimeCount = 20)
        val ids = keys.preKeys.map { it.keyId } + keys.pqPreKeys.map { it.keyId } + keys.pqLastResortPreKey.keyId
        assertTrue(ids.all { it in 0..0xFFFFFF })
        assertEquals("EC ids unique", 20, keys.preKeys.map { it.keyId }.toSet().size)
        assertEquals(
            "Kyber ids unique",
            21,
            (
                keys.pqPreKeys.map {
                    it.keyId
                } + keys.pqLastResortPreKey.keyId
                ).toSet().size
        )
    }

    @Test
    fun thePayloadHasExactlyTheContractFieldsAndNoPrivateKey() {
        val phone = Phone(folder.newFolder("bob"))
        val keys = DeviceKeyGenerator(phone.identity, phone.store).generate(oneTimeCount = 2)
        val json = DeviceKeys.json.encodeToString(DeviceKeys.serializer(), keys)
        val body = Json.parseToJsonElement(json).jsonObject

        assertEquals(
            setOf("registrationId", "identityKey", "signedPreKey", "pqLastResortPreKey", "preKeys", "pqPreKeys"),
            body.keys
        )
        assertEquals(setOf("keyId", "publicKey", "signature"), (body["signedPreKey"] as JsonObject).keys)
        assertEquals(setOf("keyId", "publicKey", "signature"), (body["pqLastResortPreKey"] as JsonObject).keys)
        assertEquals(setOf("keyId", "publicKey"), body["preKeys"]!!.jsonArray[0].jsonObject.keys)
        assertEquals(setOf("keyId", "publicKey", "signature"), body["pqPreKeys"]!!.jsonArray[0].jsonObject.keys)

        val secrets =
            listOf(phone.identity.keyPair.privateKey.serialize()) +
                phone.store.loadSignedPreKeys().map { it.keyPair.privateKey.serialize() } +
                phone.store.loadKyberPreKeys().map { it.keyPair.secretKey.serialize() }
        for (secret in secrets) {
            assertFalse(json.contains(Base64.getEncoder().encodeToString(secret)))
        }
    }

    @Test
    fun theStoredRecordsMatchThePublishedKeysAfterAReopen() {
        val phone = Phone(folder.newFolder("bob"))
        val keys = DeviceKeyGenerator(phone.identity, phone.store).generate(oneTimeCount = 3)
        val store = phone.reopen().store

        assertArrayEquals(
            b64.decode(keys.signedPreKey.publicKey),
            store.loadSignedPreKey(keys.signedPreKey.keyId).keyPair.publicKey.serialize()
        )
        assertArrayEquals(
            b64.decode(keys.pqLastResortPreKey.publicKey),
            store.loadKyberPreKey(keys.pqLastResortPreKey.keyId).keyPair.publicKey.serialize()
        )
        for (pre in keys.preKeys) {
            assertArrayEquals(b64.decode(pre.publicKey), store.loadPreKey(pre.keyId).keyPair.publicKey.serialize())
        }
        for (pq in keys.pqPreKeys) {
            assertArrayEquals(b64.decode(pq.publicKey), store.loadKyberPreKey(pq.keyId).keyPair.publicKey.serialize())
        }
    }
}
