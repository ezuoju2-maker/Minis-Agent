package com.openminis.app.ctx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.openminis.app.imagerepro.ImageRealFixtures
import com.openminis.app.provider.ImageBudget
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class RoboGraphicsProbe {
    abstract val mode: String

    private fun line(probe: String, value: Any?) = println("[ROBO] $mode $probe = $value")

    @Test fun probes() {
        val t0 = System.nanoTime()
        val exif6 = ImageRealFixtures.jpegWithExifOrientation(400, 300, 6)
        line("BitmapFactory EXIF=6 400x300", BitmapFactory.decodeByteArray(exif6, 0, exif6.size)?.let { "${it.width}x${it.height}" })
        line("ImageDecoder EXIF=6 400x300", runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(exif6))).let { "${it.width}x${it.height}" }
        }.getOrElse { "throws ${it.javaClass.simpleName}: ${it.message?.take(80)}" })
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(exif6, 0, exif6.size, bounds)
        line("bounds-only EXIF=6", "${bounds.outWidth}x${bounds.outHeight}")

        val alphaPng = CtxKit.png(64, 32, alpha = true)
        val bmp = BitmapFactory.decodeByteArray(alphaPng, 0, alphaPng.size)
        line("alpha PNG decoded", bmp?.let { "${it.width}x${it.height} hasAlpha=${it.hasAlpha()} px(0,0)=%08x".format(it.getPixel(0, 0)) })
        if (bmp != null) {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val back = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            line("alpha → JPEG px(0,0) (transparent area)", back?.let { "%08x".format(it.getPixel(0, 0)) })
            line("alpha → JPEG bytes are a JPEG", ImageRealFixtures.dims(out.toByteArray())?.format)
        }

        val header = CtxKit.heicHeaderOnly()
        line("HEIC header-only decode", BitmapFactory.decodeByteArray(header, 0, header.size)?.let { "${it.width}x${it.height}" })
        val real = java.io.File("build/robo-probe/real.heic").takeIf { it.exists() }?.readBytes()
        line("real HEIC 1200x1200 BitmapFactory", if (real == null) "no sample" else BitmapFactory.decodeByteArray(real, 0, real.size)?.let { b -> "${b.width}x${b.height}" } ?: "null (not decoded)")
        line("real HEIC ImageDecoder", real?.let { r ->
            runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(r))).let { "${it.width}x${it.height}" } }
                .getOrElse { "throws ${it.javaClass.simpleName}" }
        } ?: "no sample")

        val cam = CtxKit.jpeg(CtxKit.Preset.CAMERA_4032x3024)
        line("downscaleForModel 4032x3024", ImageBudget.downscaleForModel(cam)?.let { d -> ImageRealFixtures.dims(d.bytes)?.let { "${it.width}x${it.height} ${d.mimeType}" } })
        line("countImageTokens 4032x3024", com.openminis.app.data.BPETokenizer.countImageTokens(cam))
        val big = CtxKit.jpeg(1080, 32768)
        line("resizeJpegToMaxEdge 1080x32768", ImageBudget.resizeJpegToMaxEdge(big, 2000)?.let { b -> ImageRealFixtures.dims(b)?.let { "${it.width}x${it.height}" } })
        line("elapsedMs", (System.nanoTime() - t0) / 1_000_000)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoboGraphicsProbeNativeTest : RoboGraphicsProbe() {
    override val mode = "NATIVE"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RoboGraphicsProbeLegacyTest : RoboGraphicsProbe() {
    override val mode = "LEGACY"
}
