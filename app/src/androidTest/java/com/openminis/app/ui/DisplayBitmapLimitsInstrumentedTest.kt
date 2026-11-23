package com.openminis.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.drawable.toBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DisplayBitmapLimitsInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var hugeImage: File

    private val hugeWidth = 3000
    private val hugeHeight = 17920

    @Before
    fun setUp() {
        hugeImage = File(context.cacheDir, "canvas_limit_repro.png")
        if (hugeImage.exists()) return
        val src = Bitmap.createBitmap(hugeWidth, hugeHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(src)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(30, 110, 220)
        }
        var y = 0
        while (y < hugeHeight) {
            canvas.drawRect(0f, y.toFloat(), hugeWidth.toFloat(), (y + 200).toFloat(), paint)
            y += 400
        }
        hugeImage.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
        src.recycle()
    }

    @After
    fun tearDown() {
        hugeImage.delete()
    }

    private fun decode(file: File, capped: Boolean): Bitmap = runBlocking {
        val loader = ImageLoader.Builder(context).build()
        val request = ImageRequest.Builder(context)
            .data(file)
            .allowHardware(false)
            .apply {
                if (capped) {
                    limitDisplaySize()
                } else {
                    size(coil.size.Size.ORIGINAL)
                }
            }
            .build()
        val result = loader.execute(request)
        assertTrue("Coil failed to decode the repro image", result is SuccessResult)
        (result as SuccessResult).drawable.toBitmap()
    }

    private fun drawsWithoutThrowing(bitmap: Bitmap): Boolean = try {
        val node = android.graphics.RenderNode("displayCapTest")
        val canvas = node.beginRecording(1080, 2400)
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            node.endRecording()
        }
        true
    } catch (t: Throwable) {
        false
    }

    @Test
    fun uncappedDecodeProducesABitmapTooLargeToDraw() {
        val bitmap = decode(hugeImage, capped = false)
        assertTrue(
            "uncapped decode was only ${bitmap.width}x${bitmap.height} " +
                "(${bitmap.byteCount} bytes) — expected it to exceed the display cap",
            maxOf(bitmap.width, bitmap.height) > DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX,
        )
        assertTrue(
            "uncapped bitmap (${bitmap.byteCount} bytes) unexpectedly drew fine; " +
                "the repro no longer reproduces the reported crash",
            !drawsWithoutThrowing(bitmap),
        )
    }

    @Test
    fun cappedDecodeStaysWithinTheDisplayCeilingAndDraws() {
        val bitmap = decode(hugeImage, capped = true)
        assertTrue(
            "capped decode was ${bitmap.width}x${bitmap.height}, expected longest edge " +
                "<= ${DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX}",
            maxOf(bitmap.width, bitmap.height) <= DisplayBitmapLimits.MAX_DISPLAY_EDGE_PX,
        )
        assertTrue(
            "capped bitmap (${bitmap.byteCount} bytes) still could not be drawn",
            drawsWithoutThrowing(bitmap),
        )
    }
}
