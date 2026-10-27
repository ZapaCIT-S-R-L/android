package ro.zapakid.calls.crypto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Key rotation: the first device in the roster (join order) is the key leader. It makes a new random key on every
 * join and leave and seals it to each other device; a device that left never receives the next key, and one that
 * joins never receives an earlier one.
 */
class CallKeyManagerTest {
    private val a = CallDevice("acc-a", "dev-a")
    private val b = CallDevice("acc-b", "dev-b")
    private val c = CallDevice("acc-c", "dev-c")

    private class Phone(val device: CallDevice, callId: String = "call-1") {
        val ring = KeyRing()
        val keys = CallKeyManager(callId, device, FakeCallKeySealer(device), ring)
    }

    private suspend fun deliver(envelopes: List<KeyEnvelope>, vararg phones: Phone) {
        for (envelope in envelopes) phones.firstOrNull { it.device == envelope.toDevice }?.keys?.onEnvelope(envelope)
    }

    @Test
    fun aDeviceAloneInTheCallMakesTheFirstKey() = runTest {
        val phoneA = Phone(a)

        assertEquals(emptyList<KeyEnvelope>(), phoneA.keys.onRoster(listOf(a)))
        assertEquals("epochs start at 0, as on iOS", 0, phoneA.ring.current?.epoch)
    }

    @Test
    fun aJoinRotatesTheKeyAndTheNewcomerGetsOnlyTheNewOne() = runTest {
        val phoneA = Phone(a)
        val phoneB = Phone(b)
        val phoneC = Phone(c)
        phoneA.keys.onRoster(listOf(a))
        phoneB.keys.onRoster(listOf(a, b))
        deliver(phoneA.keys.onRoster(listOf(a, b)), phoneB)
        assertEquals(1, phoneB.ring.current?.epoch)
        assertArrayEquals(phoneA.ring.current!!.bytes(), phoneB.ring.current!!.bytes())

        phoneB.keys.onRoster(listOf(a, b, c))
        phoneC.keys.onRoster(listOf(a, b, c))
        val envelopes = phoneA.keys.onRoster(listOf(a, b, c))
        assertEquals(setOf(b, c), envelopes.map { it.toDevice }.toSet())
        assertTrue(envelopes.all { it.epoch == 2 && it.fromDevice == a })
        deliver(envelopes, phoneB, phoneC)

        assertEquals(2, phoneC.ring.current?.epoch)
        assertNull("the newcomer never gets the earlier key", phoneC.ring.get(1))
        assertArrayEquals(phoneA.ring.current!!.bytes(), phoneC.ring.current!!.bytes())
    }

    @Test
    fun aLeaveRotatesTheKeyAndTheLeaverGetsNothing() = runTest {
        val phoneA = Phone(a)
        val phoneB = Phone(b)
        val phoneC = Phone(c)
        listOf(phoneB, phoneC).forEach { it.keys.onRoster(listOf(a, b, c)) }
        deliver(phoneA.keys.onRoster(listOf(a, b, c)), phoneB, phoneC)

        phoneC.keys.onRoster(listOf(a, c))
        val envelopes = phoneA.keys.onRoster(listOf(a, c))

        assertEquals(listOf(c), envelopes.map { it.toDevice })
        deliver(envelopes, phoneC)
        assertEquals(phoneA.ring.current?.epoch, phoneC.ring.current?.epoch)
        assertTrue(phoneB.ring.current!!.epoch < phoneA.ring.current!!.epoch)
    }

    @Test
    fun whenTheLeaderLeavesTheNextDeviceRotates() = runTest {
        val phoneA = Phone(a)
        val phoneB = Phone(b)
        val phoneC = Phone(c)
        listOf(phoneB, phoneC).forEach { it.keys.onRoster(listOf(a, b, c)) }
        deliver(phoneA.keys.onRoster(listOf(a, b, c)), phoneB, phoneC)
        val before = phoneB.ring.current!!.epoch

        phoneC.keys.onRoster(listOf(b, c))
        val envelopes = phoneB.keys.onRoster(listOf(b, c))

        assertEquals(listOf(c), envelopes.map { it.toDevice })
        assertEquals(before + 1, envelopes.single().epoch)
        deliver(envelopes, phoneC)
        assertArrayEquals(phoneB.ring.current!!.bytes(), phoneC.ring.current!!.bytes())
    }

    @Test
    fun aDeviceThatIsNotTheLeaderNeverMakesKeys() = runTest {
        val phoneB = Phone(b)

        assertEquals(emptyList<KeyEnvelope>(), phoneB.keys.onRoster(listOf(a, b)))
        assertNull(phoneB.ring.current)
    }

    @Test
    fun keysFromAnyoneButTheLeaderAreRejected() = runTest {
        val phoneB = Phone(b)
        val phoneC = Phone(c)
        listOf(phoneB, phoneC).forEach { it.keys.onRoster(listOf(a, b, c)) }
        // C pretends to be the leader and seals its own key to B.
        val forgedKey = CallKey.random(9, java.security.SecureRandom())
        val blob = FakeCallKeySealer(c).seal(SealedKeyPayload.encode("call-1", forgedKey), b)
        val envelope = KeyEnvelope(9, c, b, blob)

        assertFalse(phoneB.keys.onEnvelope(envelope))
        assertNull(phoneB.ring.current)
    }

    @Test
    fun anOldOrReplayedKeyIsRejected() = runTest {
        val phoneA = Phone(a)
        val phoneB = Phone(b)
        phoneB.keys.onRoster(listOf(a, b))
        val first = phoneA.keys.onRoster(listOf(a, b))
        deliver(first, phoneB)
        phoneB.keys.onRoster(listOf(a, b, c))
        deliver(phoneA.keys.onRoster(listOf(a, b, c)), phoneB)
        val current = phoneB.ring.current!!.epoch

        assertFalse(phoneB.keys.onEnvelope(first.single()))
        assertEquals(current, phoneB.ring.current!!.epoch)
    }

    @Test
    fun aKeyForAnotherCallOrEpochIsRejected() = runTest {
        val phoneB = Phone(b)
        phoneB.keys.onRoster(listOf(a, b))
        val otherCall = CallKeyManager("call-2", a, FakeCallKeySealer(a), KeyRing())
        otherCall.onRoster(listOf(a))
        val envelope = otherCall.onRoster(listOf(a, b)).single()

        assertFalse(phoneB.keys.onEnvelope(envelope))
        // The server relabels the epoch: the sealed payload still says 2.
        assertFalse(phoneB.keys.onEnvelope(envelope.copy(epoch = 9)))
        assertNull(phoneB.ring.current)
    }

    @Test
    fun anEnvelopeForAnotherDeviceIsIgnored() = runTest {
        val phoneA = Phone(a)
        val phoneB = Phone(b)
        val phoneC = Phone(c)
        phoneC.keys.onRoster(listOf(a, b, c))
        val toB = phoneA.keys.onRoster(listOf(a, b, c)).single { it.toDevice == b }

        assertFalse(phoneC.keys.onEnvelope(toB))
        assertNull(phoneC.ring.current)
        assertNull(phoneB.ring.current)
    }

    @Test
    fun theSealedPayloadBindsCallAndEpoch() {
        val key = CallKey.random(5, java.security.SecureRandom())
        val payload = SealedKeyPayload.encode("call-1", key)

        assertArrayEquals(key.bytes(), SealedKeyPayload.decode(payload, "call-1", 5).bytes())
        assertNull(runCatching { SealedKeyPayload.decode(payload, "call-2", 5) }.getOrNull())
        assertNull(runCatching { SealedKeyPayload.decode(payload, "call-1", 6) }.getOrNull())
    }

    @Test
    fun theSealedPayloadIsTheSameBytesAsOnIos() {
        val key = CallKey.of(0x01020304, ByteArray(32) { it.toByte() })

        val payload = SealedKeyPayload.encode("c1", key)

        val expected =
            "ZKCK".encodeToByteArray() + byteArrayOf(1, 1, 2, 3, 4) + ByteArray(32) { it.toByte() } +
                "c1".encodeToByteArray()
        assertArrayEquals(expected, payload)
    }

    @Test
    fun theLowestDeviceIdLeadsWhateverTheJoinOrder() = runTest {
        val phoneB = Phone(b)

        assertTrue("acc-b.dev-b sorts before acc-c.dev-c", phoneB.keys.onRoster(listOf(c, b)).isNotEmpty())
        assertEquals(emptyList<KeyEnvelope>(), Phone(c).keys.onRoster(listOf(c, b)))
    }

    @Test
    fun theServersEpochIsUsedWhenKnown() = runTest {
        val phoneA = Phone(a)

        val envelopes = phoneA.keys.onRoster(listOf(a, b), serverEpoch = 7)

        assertEquals(7, envelopes.single().epoch)
        assertEquals(7, phoneA.ring.current?.epoch)
    }

    @Test
    fun endingTheCallWipesEveryKey() = runTest {
        val phoneA = Phone(a)
        phoneA.keys.onRoster(listOf(a))
        val key = phoneA.ring.current!!

        phoneA.keys.close()

        assertNull(phoneA.ring.current)
        assertArrayEquals(ByteArray(32), key.bytes())
    }
}
