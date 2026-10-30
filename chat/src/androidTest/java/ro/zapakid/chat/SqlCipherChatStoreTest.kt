package ro.zapakid.chat

import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.chat.data.SqlCipherChatStore

/** The encrypted chat database on a real Android runtime. */
class SqlCipherChatStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "chat-test-${System.nanoTime()}.db"
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun storesConversationsAndMessagesAndCountsUnread() = runTest {
        val store = SqlCipherChatStore(context, key, name)
        store.upsertConversation(Conversation("c1", "Ana", 2, false, listOf("me", "ana")))

        assertTrue(
            store.add(
                Message("m1", "c1", "ana", false, MessageKind.Text, "secret hello", null, 10, MessageStatus.Received)
            )
        )
        assertFalse(
            "duplicate",
            store.add(
                Message("m1", "c1", "ana", false, MessageKind.Text, "secret hello", null, 10, MessageStatus.Received)
            )
        )

        assertEquals("secret hello", store.messages("c1").first().single().text)
        assertEquals(1, store.conversations().first().single().unread)
        store.markRead("c1")
        assertEquals(0, store.conversations().first().single().unread)
        store.close()
    }

    @Test
    fun theFileOnDiskIsNotReadableWithoutTheKey() = runTest {
        val store = SqlCipherChatStore(context, key, name)
        store.upsertConversation(Conversation("c1", "Ana", 2, false, listOf("me", "ana")))
        store.add(Message("m1", "c1", "ana", false, MessageKind.Text, "secret hello", null, 10, MessageStatus.Received))
        store.close()

        val raw = context.getDatabasePath(name).readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("secret hello"))
        assertFalse(String(raw, Charsets.ISO_8859_1).startsWith("SQLite format 3"))
    }
}
