package ro.zapakid.calls.engine

import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import ro.zapakid.calls.crypto.CallKey
import ro.zapakid.calls.crypto.KeyRing

/** The native FrameCryptor on a real Android runtime: keys go into the ring and a sender gets a cryptor. */
class FrameCryptorOnDeviceTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadWebRtc() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
        }
    }

    @Test
    fun epochKeysGoIntoTheNativeRing() {
        val keys = WebRtcCallEngine.keyProvider()
        val key = CallKey.random(17, SecureRandom())

        assertTrue(keys.setSharedKey(KeyRing.indexOf(key.epoch), key.bytes()))
        assertNotNull(keys.exportSharedKey(KeyRing.indexOf(key.epoch)))
        keys.dispose()
    }

    @Test
    fun theMicrophoneSenderIsEncryptedWithAesGcm() {
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val peer = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), NoopObserver)!!
        val track = factory.createAudioTrack("mic", factory.createAudioSource(MediaConstraints()))
        val sender =
            peer.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            ).sender
        val keys = WebRtcCallEngine.keyProvider()

        val cryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
            factory,
            sender,
            "me",
            FrameCryptorAlgorithm.AES_GCM,
            keys
        )
        cryptor.setEnabled(true)
        cryptor.keyIndex = 3

        assertTrue(cryptor.isEnabled)
        assertEquals(3, cryptor.keyIndex)
        cryptor.dispose()
        keys.dispose()
        peer.dispose()
        factory.dispose()
    }

    private object NoopObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: org.webrtc.IceCandidate) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit

        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit

        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit
    }
}
