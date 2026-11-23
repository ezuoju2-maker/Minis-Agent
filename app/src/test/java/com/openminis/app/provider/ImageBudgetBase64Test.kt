package com.openminis.app.provider

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBudgetBase64Test {

    @Test
    fun `estimate matches the real encoder for every length mod 3`() {
        val encoder = Base64.getEncoder()
        for (n in 0..64) {
            val raw = ByteArray(n) { it.toByte() }
            val actual = encoder.encodeToString(raw).length.toLong()
            val estimated = ImageBudget.estimatedBase64Length(n)
            assertEquals("n=$n", actual, estimated)
        }
    }

    @Test
    fun `estimate is the classic 4-over-3 expansion`() {
        assertEquals(4L, ImageBudget.estimatedBase64Length(3))
        assertEquals(4L, ImageBudget.estimatedBase64Length(1))
        assertEquals(8L, ImageBudget.estimatedBase64Length(4))
        assertEquals(4L * 1024 * 1024, ImageBudget.estimatedBase64Length(3L * 1024 * 1024))
    }

    @Test
    fun `estimate does not overflow past Int range`() {
        val huge = 3L * 1024 * 1024 * 1024
        assertEquals(4L * 1024 * 1024 * 1024, ImageBudget.estimatedBase64Length(huge))
        assertTrue(ImageBudget.estimatedBase64Length(huge) > Int.MAX_VALUE)
    }

    @Test
    fun `raw per-image target encodes within the per-image cap`() {
        val encoded = ImageBudget.estimatedBase64Length(ImageBudget.MAX_PER_IMAGE_RAW_BYTES)
        assertTrue(
            "raw target $encoded must fit ${ImageBudget.MAX_PER_IMAGE_BYTES}",
            encoded <= ImageBudget.MAX_PER_IMAGE_BYTES,
        )
    }

    @Test
    fun `planRequestBudget drops images that only fit before base64`() {
        val mb3 = 3 * 1024 * 1024
        val images = (1..7).map {
            ImageBudget.BudgetImage(ByteArray(mb3) { it.toByte() }, "/img$it.png", "image/png")
        }
        val plan = ImageBudget.planRequestBudget(images)
        assertTrue("expected at least one elision, got ${plan.droppedCount}", plan.droppedCount > 0)
        assertTrue(
            "kept ${plan.keptBytes} must stay within ${ImageBudget.MAX_REQUEST_BYTES}",
            plan.keptBytes <= ImageBudget.MAX_REQUEST_BYTES,
        )
        val keptRaw = (images.size - plan.droppedCount).toLong() * mb3
        assertTrue(
            "raw tally $keptRaw would still look like it fits — that was the bug",
            keptRaw < ImageBudget.MAX_REQUEST_BYTES,
        )
    }

    @Test
    fun `planRequestBudget still protects the most recent images`() {
        val mb3 = 3 * 1024 * 1024
        val eldest = ByteArray(mb3) { 1 }
        val latest = ByteArray(mb3) { 9 }
        val middle = (2..6).map {
            ImageBudget.BudgetImage(ByteArray(mb3) { it.toByte() }, "/mid$it.png", "image/png")
        }
        val plan = ImageBudget.planRequestBudget(
            listOf(ImageBudget.BudgetImage(eldest, "/eldest.png", "image/png")) +
                middle +
                listOf(ImageBudget.BudgetImage(latest, "/latest.png", "image/png")),
        )
        assertTrue(
            "the newest image must survive",
            !plan.droppedIds.contains(ImageBudget.ImagePartId.of(latest)),
        )
        assertTrue(
            "the eldest is the one that goes",
            plan.droppedIds.contains(ImageBudget.ImagePartId.of(eldest)),
        )
    }

    @Test
    fun `planRequestBudget keeps everything when comfortably under the cap`() {
        val small = ByteArray(64 * 1024) { 7 }
        val plan = ImageBudget.planRequestBudget(
            listOf(
                ImageBudget.BudgetImage(small, "/a.png", "image/png"),
                ImageBudget.BudgetImage(ByteArray(64 * 1024) { 8 }, "/b.png", "image/png"),
            ),
        )
        assertEquals(0, plan.droppedCount)
        assertTrue(plan.droppedIds.isEmpty())
    }
}
