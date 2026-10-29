package ro.zapakid.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.zapakid.chat.data.ChatStore
import ro.zapakid.chat.data.InMemoryChatStore

/** The account-isolation regression: A, a switch or a sign-out, then B — and A again. */
class PerAccountChatStoreTest {
    private val opened = mutableListOf<String>()
    private val closed = mutableListOf<ChatStore>()
    private val byName = mutableMapOf<String, ChatStore>()
    private var switches = 0

    private fun newStores() = PerAccountChatStore(
        openStore = { name ->
            opened += name
            byName.getOrPut(name) { InMemoryChatStore() }
        },
        closeStore = { closed += it },
        onSwitch = { switches++ }
    )

    @Test
    fun aSecondAccountClosesTheFirstAndOpensItsOwnDatabase() {
        val stores = newStores()

        val a = stores.forAccount("acc-a")
        val b = stores.forAccount("acc-b")

        assertEquals(listOf("acc-a", "acc-b"), opened)
        assertEquals("A's store is closed before B's opens", listOf(a), closed)
        assertNotSame(a, b)
        assertEquals(1, switches)
    }

    @Test
    fun theSameAccountKeepsTheStoreItAlreadyHas() {
        val stores = newStores()

        val a = stores.forAccount("acc-a")

        assertSame(a, stores.forAccount("acc-a"))
        assertEquals(listOf("acc-a"), opened)
        assertTrue(closed.isEmpty())
        assertEquals(0, switches)
    }

    @Test
    fun signingOutAndInAsSomeoneElseClosesTheOldStoreAndCountsAsASwitch() {
        val stores = newStores()
        val a = stores.forAccount("acc-a")

        stores.close()

        assertEquals(listOf(a), closed)
        assertEquals("nothing has switched yet", 0, switches)

        val b = stores.forAccount("acc-b")

        assertNotSame(a, b)
        assertEquals(listOf("acc-a", "acc-b"), opened)
        assertEquals("B is told it is not A, so A's call log is dropped", 1, switches)
    }

    @Test
    fun reopeningTheSameAccountAfterASignOutIsNotASwitch() {
        val stores = newStores()
        val a = stores.forAccount("acc-a")

        stores.close()
        val again = stores.forAccount("acc-a")

        assertSame("its own store, nobody else's", a, again)
        assertEquals(listOf("acc-a", "acc-a"), opened)
        assertEquals(0, switches)
    }

    @Test
    fun everyAccountGetsItsOwnFileNameAndAnUnsafeIdStillGetsOne() {
        assertEquals("acc-safe_1", PerAccountChatStore.nameOf("acc-safe_1"))

        val unsafe = PerAccountChatStore.nameOf("../../shared")

        assertEquals(unsafe, PerAccountChatStore.nameOf("../../shared"))
        assertNotEquals("a different id is a different name", unsafe, PerAccountChatStore.nameOf("../../shared2"))
        assertTrue("no separators, so no path can escape", unsafe.all { it.isLetterOrDigit() })
        assertTrue(unsafe.isNotEmpty() && unsafe.length <= 64)
    }
}
