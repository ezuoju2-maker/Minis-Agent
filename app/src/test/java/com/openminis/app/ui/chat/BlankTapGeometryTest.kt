package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.openminis.app.ProductionSources
import com.openminis.app.ui.chat.BlankTapGeometry.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlankTapGeometryTest {

    private val d = 2.75f
    private fun dp(v: Float) = v * d
    private fun line(i: Int, rightDp: Float, leftDp: Float = 0f) =
        Rect(dp(leftDp), dp(24f * i), dp(rightDp), dp(24f * (i + 1)))

    private val lines = listOf(line(0, 300f), line(1, 120f), line(2, 300f))
    private val link = Rect(dp(40f), dp(2f), dp(90f), dp(22f))

    private fun decide(xDp: Float, yDp: Float, selection: Boolean = false, long: Boolean = false, links: List<Rect> = listOf(link)) =
        BlankTapGeometry.decide(Offset(dp(xDp), dp(yDp)), lines, links, d, selection, long)

    @Test fun `the leading between two full lines is text`() {
        assertEquals(Kind.TEXT, decide(200f, 71.5f))
        assertEquals(Kind.TEXT, decide(100f, 24f, links = emptyList()))
    }

    @Test fun `a gap between words is text`() {
        assertEquals(Kind.TEXT, decide(150f, 12f))
    }

    @Test fun `well past the end of a short line is blank`() {
        assertEquals(Kind.BLANK, decide(120f + 30f, 36f))
    }

    @Test fun `just past the end of a line is not blank`() {
        assertEquals(Kind.TEXT, decide(120f + 10f, 36f))
        assertEquals(Kind.TEXT, decide(120f + 23f, 36f))
    }

    @Test fun `past a short line but close to a long neighbour is not blank`() {
        assertEquals(Kind.TEXT, decide(200f, 29f))
    }

    @Test fun `on a link is text, beside it does nothing`() {
        assertEquals(Kind.TEXT, decide(60f, 12f))
        assertEquals(Kind.NEAR_INTERACTIVE, decide(90f + 10f, 12f))
        assertEquals(Kind.NEAR_INTERACTIVE, decide(60f, 22f + 15f))
        assertEquals(Kind.TEXT, decide(90f + 25f, 12f))
    }

    @Test fun `a near miss on a link at a short line's end never reveals the capsule`() {
        val endLink = Rect(dp(90f), dp(26f), dp(120f), dp(46f))
        assertEquals(Kind.NEAR_INTERACTIVE, decide(120f + 15f, 36f, links = listOf(endLink)))
        assertEquals(Kind.BLANK, decide(120f + 40f, 36f, links = listOf(endLink)))
    }

    @Test fun `a selection or a long press is never blank`() {
        assertEquals(Kind.SUPPRESSED, decide(250f, 36f, selection = true))
        assertEquals(Kind.SUPPRESSED, decide(250f, 36f, long = true))
    }

    @Test fun `an empty line has no text to keep away from`() {
        val withEmpty = listOf(line(0, 100f), Rect(0f, dp(24f), 0f, dp(48f)), line(2, 100f))
        assertEquals(Kind.BLANK,
            BlankTapGeometry.decide(Offset(dp(200f), dp(36f)), withEmpty, emptyList(), d, false, false))
    }

    @Test fun `thresholds are dp and scale with density`() {
        val l = listOf(Rect(0f, 0f, 100f, 24f))
        assertEquals(Kind.BLANK, BlankTapGeometry.decide(Offset(130f, 12f), l, emptyList(), 1f, false, false))
        assertEquals(Kind.TEXT, BlankTapGeometry.decide(Offset(130f, 12f), l, emptyList(), 3f, false, false))
    }

    @Test fun `the tap handler routes through the decision`() {
        val src = ProductionSources.read("ui/chat/StreamingMarkdownText.kt")
        val tap = src.substringAfter("val tapModifier = if (hasUrlAnnotation").substringBefore("    } else Modifier\n    Text(")
        assertTrue(tap.contains("BlankTapGeometry.decide("))
        assertTrue(tap.contains("selectionActive = selectingAtPress || selecting()"))
        assertTrue(tap.contains("selectionController?.selection?.value != null ||"))
        assertTrue(tap.contains("textToolbar.status == androidx.compose.ui.platform.TextToolbarStatus.Shown"))
        assertTrue(tap.contains("selectingAtPress = selecting()"))
        assertTrue(tap.contains("viewConfiguration.longPressTimeoutMillis"))
        assertTrue(tap.contains("spans.firstOrNull { it.second.contains(pos) }"))
        assertFalse(tap.contains("getStringAnnotations(\"url\", offset, offset)"))
        val blank = tap.indexOf("if (kind == BlankTapGeometry.Kind.BLANK)")
        assertTrue(blank in 0 until tap.indexOf("urlClickHandler?.invoke"))
    }

    @Test fun `boxes are measured over visible ink, not a wrap's trailing space`() {
        val src = ProductionSources.read("ui/chat/StreamingMarkdownText.kt")
        assertTrue(src.contains("result.getLineEnd(line, visibleEnd = true)"))
        val ink = src.substringAfter("internal fun lineInkBoxes(").substringBefore("\n}\n")
        assertTrue(ink.contains("visibleRange(result, i,"))
        assertFalse("not the line's own right edge", ink.contains("getLineRight"))
        val spans = src.substringAfter("internal fun tapTargetSpans(").substringBefore("\n}\n")
        assertTrue(spans.contains("visibleRange(result, line, start, end)"))
    }

    @Test fun `the usage row only dismisses, within its own bounds, on both screens`() {
        val geo = ProductionSources.read("ui/chat/BlankTapGeometry.kt")
        val row = geo.substringAfter("internal fun UsageCapsuleRow(")
        assertTrue(row.contains("override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize.Zero"))
        assertTrue(row.contains("if (shown) {"))
        for (screen in listOf("ui/chat/ChatScreen.kt", "ui/chat/HelperUi.kt")) {
            val src = ProductionSources.read(screen)
            assertTrue(screen, src.contains("UsageCapsuleRow("))
            assertFalse(screen, src.contains("detectTapGestures { viewModel.toggleUsageCapsule") ||
                src.contains("detectTapGestures { vm.toggleUsageCapsule"))
        }
    }

    @Test fun `paragraphs and headings span the width, so taps beside short ones reach the rule`() {
        val src = ProductionSources.read("ui/chat/StreamingMarkdownText.kt")
        assertTrue(src.contains("modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),"))
        assertTrue(src.contains("modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),"))
    }
}
