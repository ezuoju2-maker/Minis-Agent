package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatViewModelStoreDualPoolTest {

    private val src: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt")
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        f.readText()
    }


    @Test
    fun `the store declares two pools with separate caps`() {
        assertTrue("PoolKind must exist", src.contains("enum class PoolKind"))
        assertTrue("NORMAL/CHILD kinds", src.contains("NORMAL, CHILD"))
        assertTrue("a normal cap", src.contains("MAX_CACHED_NORMAL"))
        assertTrue("a child cap", src.contains("MAX_CACHED_CHILD"))
        assertTrue(
            "trimToCapacity must operate on ONE pool, not the whole map",
            src.contains("private fun trimToCapacity(kind: PoolKind)"),
        )
    }

    @Test
    fun `pool membership is sticky`() {
        assertTrue(
            "putIfAbsent, not put — a later call with a different kind must not " +
                "reclassify a live session",
            src.contains("poolKinds.putIfAbsent(key, kind)"),
        )
        assertFalse(
            "a plain assignment would let any caller overwrite membership",
            src.contains("poolKinds[key] = kind"),
        )
    }

    @Test
    fun `pool membership is cleaned up everywhere a store is dropped`() {
        assertTrue(
            "eviction keeps CHILD tags and drops only non-CHILD ones",
            src.contains("if (poolKinds[key] != PoolKind.CHILD) poolKinds.remove(key)"),
        )
        assertEquals(
            "LRU trim + memory pressure must both evict through the tag-keeping helper",
            2, Regex("dropStoreKeepingChildTag\\(key\\)").findAll(src).count(),
        )
        assertEquals(
            "release + the eviction helper are the only places a tag is dropped",
            2, Regex("poolKinds\\.remove\\(key\\)").findAll(src).count(),
        )
        assertTrue(
            "rename must CARRY the tag to the new key, not drop it — otherwise a " +
                "promoted draft silently falls back to NORMAL",
            src.contains("poolKinds.remove(fromSessionId)?.let { poolKinds[toSessionId] = it }"),
        )
    }

    @Test
    fun `the shared headless entry point defaults to NORMAL`() {
        val runner = File("src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt").readText()
        assertTrue(
            "viewModelFor must take the pool kind as a parameter",
            runner.contains("kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL"),
        )
        assertFalse(
            "it must NOT hardcode CHILD — that would tag every headless session",
            runner.contains("ownerFor(sessionId, ChatViewModelStore.PoolKind.CHILD)"),
        )
    }

    @Test
    fun `every sub agent creation site claims the CHILD pool`() {
        val vm = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        val sched = File("src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt").readText()
        val tag = "ChatViewModelStore.PoolKind.CHILD"
        assertEquals(
            "both createChildViewModel and the delegate_task site must tag CHILD",
            2, Regex(Regex.escape(tag)).findAll(vm).count(),
        )
        assertTrue("the scheduled child must tag CHILD", sched.contains(tag))
        assertTrue(
            "ScheduledAgentRunner's parent VM stays NORMAL",
            sched.contains("viewModelFor(app, parentSid)"),
        )
    }

    @Test
    fun `ownerFor still defaults to NORMAL for existing callers`() {
        assertTrue(
            "the default keeps ChatScreen and the debug RPCs correct unchanged",
            src.contains("kind: PoolKind = PoolKind.NORMAL"),
        )
    }


    private enum class Kind { NORMAL, CHILD }

    private class Pools(val normalCap: Int = 4, val childCap: Int = 6) {
        val stores = LinkedHashMap<String, Kind>()
        val kinds = mutableMapOf<String, Kind>()
        var busy: Set<String> = emptySet()
        var onScreen: String? = null
        val evicted = mutableListOf<String>()

        fun open(id: String, kind: Kind) {
            val existing = stores.remove(id)
            if (existing == null) kinds.putIfAbsent(id, kind)
            stores[id] = kinds[id]!!
            trim(kinds[id]!!)
        }

        private fun trim(kind: Kind) {
            val cap = if (kind == Kind.NORMAL) normalCap else childCap
            val inPool = stores.keys.filter { kinds[it] == kind }
            if (inPool.size <= cap) return
            var overflow = inPool.size - cap
            for (key in inPool.filter { it != onScreen && it !in busy }) {
                if (overflow <= 0) break
                stores.remove(key); kinds.remove(key); evicted += key
                overflow--
            }
        }

        fun kindsOfEvicted() = evicted.map { it.substringBefore('-') }
    }

    @Test
    fun `single pool lets children evict normals and each other`() {
        val single = Pools(normalCap = 4, childCap = 4)
        listOf("normal-1", "normal-2", "normal-3", "normal-4")
            .forEach { single.open(it, Kind.NORMAL) }
        listOf("child-1", "child-2", "child-3")
            .forEach { single.open(it, Kind.NORMAL) }

        assertEquals(
            "three normals get pushed out by the three children",
            listOf("normal", "normal", "normal"), single.kindsOfEvicted(),
        )
    }

    @Test
    fun `dual pool keeps three concurrent children and four normals alive together`() {
        val p = Pools()
        listOf("normal-1", "normal-2", "normal-3", "normal-4")
            .forEach { p.open(it, Kind.NORMAL) }
        listOf("child-1", "child-2", "child-3")
            .forEach { p.open(it, Kind.CHILD) }

        assertTrue("no eviction at all: ${p.evicted}", p.evicted.isEmpty())
        assertEquals("all seven stay cached", 7, p.stores.size)
    }

    @Test
    fun `a child burst never evicts a normal session`() {
        val p = Pools()
        listOf("normal-1", "normal-2", "normal-3", "normal-4")
            .forEach { p.open(it, Kind.NORMAL) }
        (1..9).forEach { p.open("child-$it", Kind.CHILD) }

        assertTrue(
            "every eviction must be a child: ${p.evicted}",
            p.kindsOfEvicted().all { it == "child" },
        )
        assertTrue(
            "all four normals survive",
            (1..4).all { "normal-$it" in p.stores },
        )
    }

    @Test
    fun `normal churn never evicts a child`() {
        val p = Pools()
        (1..3).forEach { p.open("child-$it", Kind.CHILD) }
        (1..10).forEach { p.open("normal-$it", Kind.NORMAL) }

        assertTrue(
            "every eviction must be a normal: ${p.evicted}",
            p.kindsOfEvicted().all { it == "normal" },
        )
        assertTrue("all three children survive", (1..3).all { "child-$it" in p.stores })
    }

    @Test
    fun `viewing a child transcript does not move it into the normal pool`() {
        val p = Pools()
        p.open("child-1", Kind.CHILD)
        p.open("child-1", Kind.NORMAL)
        assertEquals(Kind.CHILD, p.kinds["child-1"])
    }

    @Test
    fun `a normal session cannot be pulled into the child pool`() {
        val p = Pools()
        p.open("normal-1", Kind.NORMAL)
        p.open("normal-1", Kind.CHILD)
        assertEquals(Kind.NORMAL, p.kinds["normal-1"])
    }

    @Test
    fun `both carve-outs still apply inside a pool`() {
        val p = Pools()
        (1..4).forEach { p.open("normal-$it", Kind.NORMAL) }
        p.busy = setOf("normal-1")
        p.onScreen = "normal-2"
        (5..7).forEach { p.open("normal-$it", Kind.NORMAL) }

        assertTrue("a running session is never evicted", "normal-1" in p.stores)
        assertTrue("the on-screen session is never evicted", "normal-2" in p.stores)
    }

    @Test
    fun `a pool of entirely busy sessions runs over rather than killing a run`() {
        val p = Pools()
        p.busy = (1..6).map { "normal-$it" }.toSet()
        (1..6).forEach { p.open("normal-$it", Kind.NORMAL) }

        assertTrue("nothing evictable ⇒ nothing evicted: ${p.evicted}", p.evicted.isEmpty())
        assertEquals("pool allowed over its cap of 4", 6, p.stores.size)
    }
}
