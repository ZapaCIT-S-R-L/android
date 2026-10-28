package ro.zapakid.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sealed content format shared with iOS: the fixtures in `src/test/resources/chat-content-v1` are what iOS
 * writes (and must read); Android reads each one and writes the same JSON.
 */
class ChatContentTest {
    private val key = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
    private val digest = "CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk="
    private val sentAt = 1_761_670_000_000L

    private val expected = mapOf(
        "text" to MessageBody("m1", MessageKind.Text, text = "Salut!", sentAt = sentAt),
        "image" to MessageBody(
            "m2",
            MessageKind.Image,
            attachment = AttachmentRef("a_1", key, digest, 10, "image/jpeg"),
            sentAt = sentAt
        ),
        "voice" to MessageBody(
            "m3",
            MessageKind.Voice,
            attachment = AttachmentRef("a_2", key, digest, 128, "audio/mp4", 3.5),
            sentAt = sentAt
        ),
        "call" to MessageBody("m4", MessageKind.Call, callId = "call-1", sentAt = sentAt)
    )

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/chat-content-v1/$name.json")) { "fixture $name" }.readBytes()

    @Test
    fun whatIosWritesIsRead() {
        for ((name, body) in expected) assertEquals(name, body, ChatContent.decode(fixture(name)))
    }

    @Test
    fun whatAndroidWritesIsWhatIosWrites() {
        for ((name, body) in expected) {
            assertEquals(name, canonical(fixture(name)), canonical(ChatContent.encode(body)))
        }
    }

    @Test
    fun unknownVersionsAndJunkAreRefused() {
        assertNull(ChatContent.decode(fixture("unknown-version")))
        val sticker = """{"v":1,"id":"m","sentAt":1,"body":{"sticker":{"_0":"x"}}}"""
        assertNull(ChatContent.decode(sticker.encodeToByteArray()))
        assertNull(ChatContent.decode("not json".encodeToByteArray()))
    }

    @Test
    fun unknownKeysAreIgnoredWithinVersion1() {
        val bytes = """{"v":1,"id":"m1","sentAt":5,"body":{"text":{"_0":"hi","style":"bold"}},"extra":true}"""
        assertEquals("hi", ChatContent.decode(bytes.encodeToByteArray())?.text)
    }

    @Test
    fun textIsTrimmedAndCappedAt4000Characters() {
        assertTrue(ChatContent.isValidText("a".repeat(4000)))
        assertFalse(ChatContent.isValidText("a".repeat(4001)))
        assertFalse(ChatContent.isValidText("   \n "))
    }

    /** Key order and number spelling (`3` vs `3.0`, `…000` vs `…000.5`) differ between encoders, not meaning. */
    private fun canonical(bytes: ByteArray): JsonElement = normalize(Json.parseToJsonElement(bytes.decodeToString()))

    private fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(e.map(::normalize))
        is JsonPrimitive -> if (e.isString) e else e.doubleOrNull?.let { JsonPrimitive(it.toLong()) } ?: e
    }
}
