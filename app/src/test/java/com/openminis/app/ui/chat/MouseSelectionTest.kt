package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseSelectionTest {

    private fun shard(message: String, index: Int) =
        TextShardId(message, "mdblock:$message:$index")

    private fun controllerWith(start: TextPosition, end: TextPosition) =
        SelectionController().apply {
            selection.value = TextSelection(start, end)
        }


    @Test
    fun `a point inside a single-shard selection is contained`() {
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 5), TextPosition(s, 20))
        assertTrue(c.selectionContains(TextPosition(s, 5)))
        assertTrue(c.selectionContains(TextPosition(s, 12)))
        assertTrue(c.selectionContains(TextPosition(s, 20)))
    }

    @Test
    fun `a point outside a single-shard selection is not contained`() {
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 5), TextPosition(s, 20))
        assertFalse(c.selectionContains(TextPosition(s, 4)))
        assertFalse(c.selectionContains(TextPosition(s, 21)))
    }

    @Test
    fun `a point in a DIFFERENT shard is not contained by a single-shard selection`() {
        val c = controllerWith(TextPosition(shard("m1", 0), 5), TextPosition(shard("m1", 0), 20))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 1), 10)))
    }

    @Test
    fun `a backwards selection still contains its interior`() {
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 20), TextPosition(s, 5))
        assertTrue(c.selectionContains(TextPosition(s, 12)))
        assertFalse(c.selectionContains(TextPosition(s, 25)))
    }


    @Test
    fun `a multi-shard selection contains the tail of its first shard`() {
        val c = controllerWith(TextPosition(shard("m1", 0), 10), TextPosition(shard("m1", 2), 4))
        assertTrue(c.selectionContains(TextPosition(shard("m1", 0), 30)))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 0), 3)))
    }

    @Test
    fun `a multi-shard selection contains the head of its last shard`() {
        val c = controllerWith(TextPosition(shard("m1", 0), 10), TextPosition(shard("m1", 2), 4))
        assertTrue(c.selectionContains(TextPosition(shard("m1", 2), 1)))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 2), 9)))
    }

    @Test
    fun `a multi-shard selection contains any offset of a shard in between`() {
        val c = controllerWith(TextPosition(shard("m1", 0), 10), TextPosition(shard("m1", 2), 4))
        assertTrue(c.selectionContains(TextPosition(shard("m1", 1), 0)))
        assertTrue(c.selectionContains(TextPosition(shard("m1", 1), 999)))
    }

    @Test
    fun `a shard outside the span is not contained`() {
        val c = controllerWith(TextPosition(shard("m1", 1), 0), TextPosition(shard("m1", 2), 4))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 5), 0)))
    }

    @Test
    fun `no selection contains nothing`() {
        assertFalse(SelectionController().selectionContains(TextPosition(shard("m1", 0), 0)))
    }

    @Test
    fun `an unorderable cross-message selection reports not-contained rather than throwing`() {
        val c = controllerWith(TextPosition(shard("m1", 0), 0), TextPosition(shard("m2", 0), 5))
        assertFalse(c.selectionContains(TextPosition(shard("m1", 0), 2)))
    }


    @Test
    fun `requesting the context menu records the cursor point`() {
        val c = SelectionController()
        c.requestContextMenu(Offset(120f, 340f))
        assertEquals(Offset(120f, 340f), c.contextMenuRequest.value)
    }

    @Test
    fun `clearing the selection also retires the context menu`() {
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 0), TextPosition(s, 5))
        c.requestContextMenu(Offset(10f, 10f))
        c.clearSelection()
        assertNull(c.contextMenuRequest.value)
        assertNull(c.selection.value)
    }

    @Test
    fun `dismissing the menu leaves the selection intact`() {
        val s = shard("m1", 0)
        val c = controllerWith(TextPosition(s, 0), TextPosition(s, 5))
        c.requestContextMenu(Offset(10f, 10f))
        c.dismissContextMenu()
        assertNull(c.contextMenuRequest.value)
        assertEquals(TextSelection(TextPosition(s, 0), TextPosition(s, 5)), c.selection.value)
    }

    @Test
    fun `mouse-origin defaults to false so touch selections keep their handles`() {
        assertFalse(SelectionController().selectionFromMouse.value)
    }


    private fun isDoubleClick(
        downUptimeMs: Long,
        downPoint: Offset,
        lastClickUptimeMs: Long,
        lastClickPoint: Offset,
        doubleTapTimeoutMs: Long = 300,
        touchSlopPx: Float = 8f,
    ): Boolean = lastClickUptimeMs != 0L &&
        (downUptimeMs - lastClickUptimeMs) <= doubleTapTimeoutMs &&
        (downPoint - lastClickPoint).getDistance() <= touchSlopPx * 2

    @Test
    fun `a prompt second click at the same spot is a double-click`() {
        assertTrue(isDoubleClick(1100, Offset(50f, 50f), 1000, Offset(50f, 50f)))
    }

    @Test
    fun `a slow second click is not a double-click`() {
        assertFalse(isDoubleClick(1400, Offset(50f, 50f), 1000, Offset(50f, 50f)))
    }

    @Test
    fun `a prompt second click far away is not a double-click`() {
        assertFalse(isDoubleClick(1100, Offset(300f, 50f), 1000, Offset(50f, 50f)))
    }

    @Test
    fun `a small wobble between clicks still counts as a double-click`() {
        assertTrue(isDoubleClick(1100, Offset(56f, 54f), 1000, Offset(50f, 50f)))
    }

    @Test
    fun `there is no previous click to pair with at startup`() {
        assertFalse(isDoubleClick(100, Offset(50f, 50f), 0L, Offset.Zero))
    }
}
