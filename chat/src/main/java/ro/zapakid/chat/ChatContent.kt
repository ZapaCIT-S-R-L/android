package ro.zapakid.chat

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The plaintext inside a sealed message: version 1, JSON, the same layout as iOS (`ZapaChat/Crypto/ChatContent.swift`,
 * Swift `Codable` defaults), so phones on both platforms read each other's messages:
 *
 * `{"v":1,"id":"<message id>","sentAt":<ms since the epoch>,"body":<body>}` where the body is one of
 * `{"text":{"_0":"Salut!"}}`, `{"image":{"_0":<pointer>}}`, `{"voice":{"_0":<pointer>}}` or `{"call":{"callId":"…"}}`
 * and a pointer is `{"id","key","digest","size","mimeType","duration"?}` ([AttachmentRef]: key and digest base64,
 * size of the ciphertext, duration in seconds). Unknown keys are ignored; any other version is refused.
 *
 * Shared fixtures: `chat/src/test/resources/chat-content-v1/` (the same files are checked on iOS).
 */
object ChatContent {
    const val VERSION = 1
    const val MAX_TEXT = 4000

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(body: MessageBody): ByteArray = json.encodeToString(Wire.serializer(), Wire.of(body)).encodeToByteArray()

    /** Null when the bytes aren't version 1 or don't parse: the caller keeps a placeholder. */
    fun decode(plaintext: ByteArray): MessageBody? =
        runCatching { json.decodeFromString(Wire.serializer(), plaintext.decodeToString()) }
            .getOrNull()
            ?.takeIf { it.v == VERSION }
            ?.toBody()

    fun isValidText(text: String): Boolean = text.trim().let { it.isNotEmpty() && it.length <= MAX_TEXT }

    @Serializable
    private class Wire(
        val v: Int,
        val id: String,
        @Serializable(with = Millis::class)
        val sentAt: Long,
        val body: Body
    ) {
        fun toBody(): MessageBody? = when {
            body.text != null -> MessageBody(id, MessageKind.Text, text = body.text.value, sentAt = sentAt)
            body.image != null -> MessageBody(id, MessageKind.Image, attachment = body.image.pointer, sentAt = sentAt)
            body.voice != null -> MessageBody(id, MessageKind.Voice, attachment = body.voice.pointer, sentAt = sentAt)
            body.call != null -> MessageBody(id, MessageKind.Call, callId = body.call.callId, sentAt = sentAt)
            else -> null
        }

        companion object {
            fun of(body: MessageBody): Wire = Wire(
                VERSION,
                body.id,
                body.sentAt,
                when (body.kind) {
                    MessageKind.Text -> Body(text = Text(checkNotNull(body.text)))
                    MessageKind.Image -> Body(image = Attachment(checkNotNull(body.attachment)))
                    MessageKind.Voice -> Body(voice = Attachment(checkNotNull(body.attachment)))
                    MessageKind.Call -> Body(call = Call(checkNotNull(body.callId)))
                    MessageKind.Unsupported -> error("an unreadable message is never sent")
                }
            )
        }
    }

    /** Swift encodes an enum with associated values as one key per case; the unlabelled value is `_0`. */
    @Serializable
    private class Body(
        val text: Text? = null,
        val image: Attachment? = null,
        val voice: Attachment? = null,
        val call: Call? = null
    )

    @Serializable
    private class Text(
        @SerialName("_0")
        val value: String
    )

    @Serializable
    private class Attachment(
        @SerialName("_0")
        val pointer: AttachmentRef
    )

    @Serializable
    private class Call(val callId: String)

    /** iOS writes `Date` as milliseconds since the epoch, a JSON number that may carry a fraction. */
    private object Millis : KSerializer<Long> {
        override val descriptor = PrimitiveSerialDescriptor("ro.zapakid.chat.Millis", PrimitiveKind.DOUBLE)

        override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

        override fun deserialize(decoder: Decoder): Long = decoder.decodeDouble().toLong()
    }
}
