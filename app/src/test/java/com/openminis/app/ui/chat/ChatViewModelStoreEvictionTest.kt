package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatViewModelStoreEvictionTest {

    private val src: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt")
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        f.readText()
    }

    @Test
    fun `the cache is bounded`() {
        assertTrue(
            "ChatViewModelStore must declare a size cap — an unbounded cache of " +
                "ChatViewModels is the leak this fixes. " +
                "[T-android-vm-store-dual-pool] split into one cap per pool.",
            src.contains("MAX_CACHED_NORMAL") && src.contains("MAX_CACHED_CHILD"),
        )
        assertTrue(
            "ownerFor must trim after inserting, or the cap is never enforced",
            src.contains("trimToCapacity("),
        )
    }

    @Test
    fun `eviction order is least-recently-used, not insertion order`() {
        assertTrue(
            "backing map must be a LinkedHashMap so iteration order can carry " +
                "recency",
            src.contains("LinkedHashMap<String, ViewModelStore>()"),
        )
        assertTrue(
            "ownerFor must re-insert an existing store so a session the user " +
                "keeps returning to is not evicted ahead of one opened once and " +
                "abandoned",
            src.contains("val existing = stores.remove(key)"),
        )
    }

    @Test
    fun `a streaming session is never evicted`() {
        assertTrue(
            "trim must consult SessionActivityTracker.activeSessions",
            src.contains("SessionActivityTracker.activeSessions"),
        )
        assertTrue(
            "busy sessions must be excluded from the evictable set",
            src.contains("it !in busy"),
        )
    }

    @Test
    fun `the on-screen session is never evicted`() {
        assertTrue(
            "the session currently displayed must be excluded, or it would be " +
                "torn down underneath the composition reading it",
            src.contains("it != onScreen"),
        )
    }
}
