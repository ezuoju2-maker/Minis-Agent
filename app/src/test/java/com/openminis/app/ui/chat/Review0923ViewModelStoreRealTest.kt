package com.openminis.app.ui.chat

import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class Review0923ViewModelStoreRealTest {

    private val created = mutableListOf<String>()

    private fun id(prefix: String): String =
        "$prefix-${UUID.randomUUID()}".also { created += it }

    private fun open(
        sid: String,
        kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL,
    ): ViewModelStore = ChatViewModelStore.ownerFor(sid, kind).viewModelStore

    private fun live(sid: String, store: ViewModelStore) = ChatViewModelStore.isLiveStore(sid, store)

    @After
    fun tearDown() {
        ChatViewModelStore.setActiveSession(null)
        created.forEach { ChatViewModelStore.release(it) }
    }

    @Test
    fun `the normal pool trims to its cap, oldest idle first`() {
        val cap = ChatViewModelStore.MAX_CACHED_NORMAL
        val ids = (1..cap + 1).map { id("n") }
        val stores = ids.map { open(it) }
        assertFalse("oldest idle session evicted", live(ids[0], stores[0]))
        (1..cap).forEach { assertTrue("session $it kept", live(ids[it], stores[it])) }
    }

    @Test
    fun `a re-opened session moves to the most-recently-used end`() {
        val ids = (1..ChatViewModelStore.MAX_CACHED_NORMAL).map { id("n") }
        val stores = ids.map { open(it) }.toMutableList()
        assertSame(stores[0], open(ids[0]))
        open(id("n"))
        assertTrue("touched session survives", live(ids[0], stores[0]))
        assertFalse("untouched second-oldest evicted instead", live(ids[1], stores[1]))
    }

    @Test
    fun `the store being inserted is never the one evicted, even when everything else is exempt`() {
        val ids = (1..ChatViewModelStore.MAX_CACHED_NORMAL).map { id("n") }
        val stores = ids.map { open(it) }
        ChatViewModelStore.setActiveSession(ids[0])
        val fresh = id("n")
        val freshStore = open(fresh)
        assertTrue("just-inserted store must stay live (aa3aab6bb)", live(fresh, freshStore))
        assertTrue("on-screen session kept", live(ids[0], stores[0]))
        assertFalse("next-oldest idle session paid instead", live(ids[1], stores[1]))
    }

    @Test
    fun `a child burst never evicts a normal session (real store)`() {
        val normals = (1..4).map { id("n") }
        val normalStores = normals.map { open(it) }
        repeat(9) { open(id("c"), ChatViewModelStore.PoolKind.CHILD) }
        normals.forEachIndexed { i, sid ->
            assertTrue("normal $i survives a child burst", live(sid, normalStores[i]))
        }
    }

    @Test
    fun `a promoted draft keeps its store and its pool`() {
        val draft = "__new__" + UUID.randomUUID()
        created += draft
        val real = id("c")
        val store = open(draft, ChatViewModelStore.PoolKind.CHILD)
        ChatViewModelStore.rename(draft, real)
        assertTrue(live(real, store))
        assertTrue("the draft key resolves through the alias", live(draft, store))
        assertEquals(real, ChatViewModelStore.resolvePersistedId(draft))
        val normals = (1..5).map { id("n") }
        normals.forEach { open(it) }
        assertTrue("renamed child stays out of the normal pool", live(real, store))
    }

    @Test
    fun `memory pressure releases idle stores and keeps the on-screen one`() {
        val a = id("n")
        val b = id("n")
        val sa = open(a)
        val sb = open(b)
        ChatViewModelStore.setActiveSession(b)
        val released = ChatViewModelStore.handleMemoryPressure()
        assertTrue("released at least the idle store", released >= 1)
        assertFalse(live(a, sa))
        assertTrue(live(b, sb))
    }

    @Test
    fun `re-opening an evicted child transcript must not evict a normal chat`() {
        val child = id("c")
        open(child, ChatViewModelStore.PoolKind.CHILD)
        repeat(6) { open(id("c"), ChatViewModelStore.PoolKind.CHILD) }
        val normals = (1..4).map { id("n") }
        val normalStores = normals.map { open(it) }

        open(child)

        normals.forEachIndexed { i, sid ->
            assertTrue(
                "normal chat $i was evicted by re-opening a finished child's transcript",
                live(sid, normalStores[i]),
            )
        }
    }


    private fun read(path: String): String =
        File(path).also { assertTrue("missing ${it.absolutePath}", it.exists()) }.readText()

    @Test
    fun `sub agent creation sites still claim the CHILD pool`() {
        val vm = read("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        val claims = Regex("""viewModelFor\(\s*context,\s*[\w.]+,\s*com\.openminis\.app\.ui\.chat\.ChatViewModelStore\.PoolKind\.CHILD""")
            .findAll(vm).count()
        assertTrue("createChildViewModel + delegate_task tag CHILD (found $claims)", claims >= 2)
    }

    @Test
    fun `ChatScreen clears the on-screen id only if it still owns it`() {
        val src = read("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        val dispose = src.substringAfter("ChatViewModelStore.setActiveSession(sessionId)")
            .substringAfter("onDispose {").take(600)
        assertFalse(
            "onDispose must not blindly null the active session; compare with sessionId first",
            dispose.contains("ChatViewModelStore.setActiveSession(null)") &&
                !dispose.contains("activeSessionId") &&
                !dispose.contains("clearActiveSession"),
        )
    }
}
