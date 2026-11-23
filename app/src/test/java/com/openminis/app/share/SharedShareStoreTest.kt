package com.openminis.app.share

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SharedShareStoreTest {


    private class FakePrefs : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()

        inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                map.putAll(pending)
            }
        }

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeContext(private val files: File, private val prefs: FakePrefs) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
    }

    private lateinit var filesDir: File
    private lateinit var prefs: FakePrefs
    private lateinit var context: Context

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("share-store-test").toFile()
        prefs = FakePrefs()
        context = FakeContext(filesDir, prefs)
        SharedShareStore.clearPendingShare(context)
        drain()
    }

    @After
    fun tearDown() {
        drain()
        filesDir.deleteRecursively()
    }

    private fun drain(): PendingShare? {
        ShareCoordinator.addressTo("test-session")
        return ShareCoordinator.consumeBuffer(context, "test-session")
    }

    private fun text(v: String) = PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, v)
    private fun attachment(name: String) = PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name)
    private fun share(vararg items: PendingShare.Item, ageMs: Long = 0) =
        PendingShare(items.toList(), System.currentTimeMillis() - ageMs)

    private fun stagedFile(name: String): File =
        File(SharedShareStore.sharedFileDirectory(context), name).apply { writeText("bytes of $name") }


    @Test
    fun `a second share merges into the unconsumed first, in arrival order`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        SharedShareStore.savePendingShare(context, share(attachment("b.png")))
        val loaded = SharedShareStore.loadPendingShare(context)
        assertNotNull(loaded)
        assertEquals(listOf(text("A"), attachment("b.png")), loaded!!.items)
    }

    @Test
    fun `identical content shared twice is de-duplicated`() {
        SharedShareStore.savePendingShare(context, share(text("same"), attachment("x.png")))
        SharedShareStore.savePendingShare(context, share(text("same"), attachment("x.png"), text("new")))
        val items = SharedShareStore.loadPendingShare(context)!!.items
        assertEquals(listOf(text("same"), attachment("x.png"), text("new")), items)
    }

    @Test
    fun `dedupe is keyed by kind and value`() {
        SharedShareStore.savePendingShare(context, share(text("note.txt")))
        SharedShareStore.savePendingShare(context, share(attachment("note.txt")))
        assertEquals(2, SharedShareStore.loadPendingShare(context)!!.items.size)
    }

    @Test
    fun `merging renews the timestamp`() {
        SharedShareStore.savePendingShare(context, share(text("A"), ageMs = 200_000))
        val before = System.currentTimeMillis()
        SharedShareStore.savePendingShare(context, share(text("B")))
        assertTrue(SharedShareStore.loadPendingShare(context)!!.timestampMs >= before)
    }

    @Test
    fun `a stale record is replaced instead of merged`() {
        SharedShareStore.savePendingShare(context, share(text("old"), ageMs = 301_000))
        SharedShareStore.savePendingShare(context, share(text("fresh")))
        assertEquals(listOf(text("fresh")), SharedShareStore.loadPendingShare(context)!!.items)
    }

    @Test
    fun `merging past the cap drops the oldest items`() {
        SharedShareStore.savePendingShare(context, share(*(1..45).map { text("t$it") }.toTypedArray()))
        SharedShareStore.savePendingShare(context, share(*(46..55).map { text("t$it") }.toTypedArray()))
        val items = SharedShareStore.loadPendingShare(context)!!.items
        assertEquals(50, items.size)
        assertEquals("the oldest five are gone", text("t6"), items.first())
        assertEquals("the newest survives", text("t55"), items.last())
        assertFalse(items.contains(text("t1")))
    }

    @Test
    fun `clear removes the record and a missing record loads as null`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        SharedShareStore.clearPendingShare(context)
        assertNull(SharedShareStore.loadPendingShare(context))
    }


    @Test
    fun `cleanup exempts the files named in keep`() {
        val live = stagedFile("live-1.png")
        val live2 = stagedFile("live-2.png")
        val stale = stagedFile("stale.png")
        SharedShareStore.cleanSharedFiles(context, keep = setOf("live-1.png", "live-2.png"))
        assertTrue(live.exists())
        assertTrue(live2.exists())
        assertFalse(stale.exists())
        SharedShareStore.cleanSharedFiles(context)
        assertFalse("an unconditional clean removes the rest", live.exists())
    }

    @Test
    fun `a stale record's cleanup does not delete the live buffer's attachments`() {
        val a = stagedFile("a-live.png")
        SharedShareStore.savePendingShare(context, share(attachment("a-live.png")))
        ShareCoordinator.processPendingShare(context)
        assertNull("the record was consumed into memory", SharedShareStore.loadPendingShare(context))

        val stale = stagedFile("stale.png")
        prefs.edit().putString("pending_share", share(attachment("stale.png"), ageMs = 400_000).toJson().toString()).apply()
        ShareCoordinator.processPendingShare(context)
        assertTrue("live buffer's file must survive", a.exists())
        assertFalse("the stale share's own file is cleaned", stale.exists())

        val consumed = drain()
        assertEquals(listOf(attachment("a-live.png")), consumed!!.items)
    }


    @Test
    fun `the in-memory buffer merges consecutive launches`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        ShareCoordinator.processPendingShare(context)
        val v1 = ShareCoordinator.bufferVersion.value
        SharedShareStore.savePendingShare(context, share(text("B"), text("A")))
        ShareCoordinator.processPendingShare(context)
        assertEquals("a warm session must be told to inject", v1 + 1, ShareCoordinator.bufferVersion.value)
        val consumed = drain()
        assertEquals(listOf(text("A"), text("B")), consumed!!.items)
        assertNull("consume is one-shot", drain())
    }

    @Test
    fun `a stale persisted share is dropped on launch`() {
        SharedShareStore.savePendingShare(context, share(text("old"), ageMs = 400_000))
        ShareCoordinator.processPendingShare(context)
        assertNull(SharedShareStore.loadPendingShare(context))
        assertNull(drain())
    }


    @Test
    fun `an unaddressed share is consumable by nobody`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        ShareCoordinator.processPendingShare(context)
        assertTrue(ShareCoordinator.hasUnaddressedShare())
        assertNull(ShareCoordinator.consumeBuffer(context, "any-chat"))
        assertTrue("still waiting to be routed", ShareCoordinator.hasUnaddressedShare())
    }

    @Test
    fun `only the addressed chat takes the share`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        ShareCoordinator.processPendingShare(context)
        val v = ShareCoordinator.bufferVersion.value
        assertTrue(ShareCoordinator.addressTo("__new__draft"))
        assertEquals("addressing re-notifies the chats", v + 1, ShareCoordinator.bufferVersion.value)
        assertFalse(ShareCoordinator.hasUnaddressedShare())
        assertNull("another chat cannot take it", ShareCoordinator.consumeBuffer(context, "busy-chat"))
        assertEquals(listOf(text("A")), ShareCoordinator.consumeBuffer(context, "__new__draft")!!.items)
        assertNull(ShareCoordinator.consumeBuffer(context, "__new__draft"))
    }

    @Test
    fun `the first router wins and a second cannot re-target`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        ShareCoordinator.processPendingShare(context)
        assertTrue(ShareCoordinator.addressTo("visible-chat"))
        assertFalse(ShareCoordinator.addressTo("__new__other"))
        assertNull(ShareCoordinator.consumeBuffer(context, "__new__other"))
        assertEquals(listOf(text("A")), ShareCoordinator.consumeBuffer(context, "visible-chat")!!.items)
    }

    @Test
    fun `a second share merges and the whole buffer is routed again to what is visible now`() {
        SharedShareStore.savePendingShare(context, share(text("A")))
        ShareCoordinator.processPendingShare(context)
        ShareCoordinator.addressTo("chat-closed-before-taking-it")
        SharedShareStore.savePendingShare(context, share(text("B")))
        ShareCoordinator.processPendingShare(context)
        assertTrue(ShareCoordinator.hasUnaddressedShare())
        assertNull(ShareCoordinator.consumeBuffer(context, "chat-closed-before-taking-it"))
        assertTrue(ShareCoordinator.addressTo("__new__draft"))
        assertEquals(listOf(text("A"), text("B")), ShareCoordinator.consumeBuffer(context, "__new__draft")!!.items)
    }

    @Test
    fun `nothing buffered - nothing to address`() {
        assertFalse(ShareCoordinator.hasUnaddressedShare())
        assertFalse(ShareCoordinator.addressTo("x"))
    }
}
