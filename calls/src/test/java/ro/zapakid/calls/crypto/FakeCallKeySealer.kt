package ro.zapakid.calls.crypto

/**
 * Stand-in for the libsignal sealer: the blob names sender and recipient, so opening it on the wrong device or
 * from the wrong sender fails, as it would with a real Signal session. Not encryption; tests only.
 */
class FakeCallKeySealer(private val self: CallDevice) : CallKeySealer {
    override suspend fun seal(payload: ByteArray, toDevice: CallDevice): ByteArray =
        "${toDevice.account}/${toDevice.device}|${self.account}/${self.device}|".encodeToByteArray() + payload

    override suspend fun open(blob: ByteArray, fromDevice: CallDevice): ByteArray {
        val header = "${self.account}/${self.device}|${fromDevice.account}/${fromDevice.device}|".encodeToByteArray()
        if (blob.size < header.size || !blob.copyOfRange(0, header.size).contentEquals(header)) {
            throw CallKeySealerException("not sealed for this device by that sender")
        }
        return blob.copyOfRange(header.size, blob.size)
    }
}
