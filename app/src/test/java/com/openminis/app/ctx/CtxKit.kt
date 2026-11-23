package com.openminis.app.ctx

import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.imagerepro.ImageRealFixtures
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CtxKit {


    enum class Preset(val w: Int, val h: Int) {
        CAMERA_4032x3024(4032, 3024),
        ALBUM_4000x6000(4000, 6000),
        TINY_10x10(10, 10),
        WIDE_9000x300(9000, 300),
        TALL_1x9000(1, 9000),
        FULLPAGE_IOS_2760x21891(2760, 21891),
        FULLPAGE_AND_1080x32768(1080, 32768),
        VIEWPORT_IOS_1170x2532(1170, 2532),
        VIEWPORT_BIG_9000x6000(9000, 6000),
    }

    enum class Content { SOLID, GRADIENT, TEXT, NOISE }

    private const val GRAY_ABOVE_PIXELS = 16_000_000L

    private val bi by lazy {
        System.setProperty("java.awt.headless", "true")
        Class.forName("java.awt.image.BufferedImage")
    }
    private val imageIO by lazy { Class.forName("javax.imageio.ImageIO") }

    private const val TYPE_INT_RGB = 1
    private const val TYPE_INT_ARGB = 2
    private const val TYPE_BYTE_GRAY = 10

    private fun raster(w: Int, h: Int, content: Content, alpha: Boolean, forceRgb: Boolean = false): Any {
        val gray = !alpha && !forceRgb && w.toLong() * h > GRAY_ABOVE_PIXELS
        val type = when {
            alpha -> TYPE_INT_ARGB
            gray -> TYPE_BYTE_GRAY
            else -> TYPE_INT_RGB
        }
        val img = bi.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(w, h, type)
        val buffer = bi.getMethod("getRaster").invoke(img).let { it.javaClass.getMethod("getDataBuffer").invoke(it) }
        val rnd = java.util.Random(42)
        if (gray) {
            val px = buffer.javaClass.getMethod("getData").invoke(buffer) as ByteArray
            for (y in 0 until h) for (x in 0 until w) {
                px[y * w + x] = when (content) {
                    Content.SOLID -> 120
                    Content.NOISE -> rnd.nextInt(256)
                    else -> ((x * 255L / maxOf(1, w - 1) + y * 255L / maxOf(1, h - 1)) / 2).toInt()
                }.toByte()
            }
        } else {
            val px = buffer.javaClass.getMethod("getData").invoke(buffer) as IntArray
            for (y in 0 until h) for (x in 0 until w) {
                val rgb = when (content) {
                    Content.SOLID -> 0x3C78C8
                    Content.NOISE -> rnd.nextInt(0x1000000)
                    Content.TEXT -> 0xFFFFFF
                    Content.GRADIENT -> ((x * 255 / maxOf(1, w - 1)) shl 16) or ((y * 255 / maxOf(1, h - 1)) shl 8) or 0x80
                }
                val a = if (!alpha) 0 else if (x < w / 2) 0x00 else 0xFF
                px[y * w + x] = if (alpha) (a shl 24) or rgb else rgb
            }
        }
        if (content == Content.TEXT) drawText(img, w, h)
        return img
    }

    private fun drawText(img: Any, w: Int, h: Int) {
        val g = bi.getMethod("createGraphics").invoke(img)
        val gClass = Class.forName("java.awt.Graphics")
        val color = Class.forName("java.awt.Color").getField("BLACK").get(null)
        gClass.getMethod("setColor", Class.forName("java.awt.Color")).invoke(g, color)
        val fontClass = Class.forName("java.awt.Font")
        val size = maxOf(10, minOf(w, h) / 20)
        val font = fontClass.getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance("SansSerif", 0, size)
        gClass.getMethod("setFont", fontClass).invoke(g, font)
        var y = size
        var line = 1
        while (y < h) {
            gClass.getMethod("drawString", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(g, "Line $line: The quick brown fox 0123456789", size / 2, y)
            y += size * 2
            line++
        }
        gClass.getMethod("dispose").invoke(g)
    }

    fun jpeg(w: Int, h: Int, content: Content = Content.GRADIENT, quality: Float = 0.80f): ByteArray =
        encodeJpeg(raster(w, h, content, alpha = false), quality)

    fun jpeg(p: Preset, content: Content = Content.GRADIENT, quality: Float = 0.80f): ByteArray = jpeg(p.w, p.h, content, quality)

    private fun encodeJpeg(img: Any, quality: Float): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = (imageIO.getMethod("getImageWritersByFormatName", String::class.java).invoke(null, "jpeg") as Iterator<*>).next()!!
        val writerClass = Class.forName("javax.imageio.ImageWriter")
        val ios = imageIO.getMethod("createImageOutputStream", Any::class.java).invoke(null, out)
        writerClass.getMethod("setOutput", Any::class.java).invoke(writer, ios)
        val param = writerClass.getMethod("getDefaultWriteParam").invoke(writer)
        val iwp = Class.forName("javax.imageio.ImageWriteParam")
        iwp.getMethod("setCompressionMode", Int::class.javaPrimitiveType).invoke(param, iwp.getField("MODE_EXPLICIT").get(null))
        iwp.getMethod("setCompressionQuality", Float::class.javaPrimitiveType).invoke(param, quality)
        val iioImage = Class.forName("javax.imageio.IIOImage")
            .getConstructor(Class.forName("java.awt.image.RenderedImage"), List::class.java, Class.forName("javax.imageio.metadata.IIOMetadata"))
            .newInstance(img, null, null)
        writerClass.getMethod("write", Class.forName("javax.imageio.metadata.IIOMetadata"), Class.forName("javax.imageio.IIOImage"), iwp)
            .invoke(writer, null, iioImage, param)
        Class.forName("javax.imageio.stream.ImageOutputStream").getMethod("flush").invoke(ios)
        writerClass.getMethod("dispose").invoke(writer)
        return out.toByteArray()
    }

    private fun write(img: Any, format: String): ByteArray {
        val out = ByteArrayOutputStream()
        val ok = imageIO.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, java.io.OutputStream::class.java)
            .invoke(null, img, format, out) as Boolean
        check(ok) { "no ImageIO writer for $format" }
        return out.toByteArray()
    }

    fun png(w: Int, h: Int, alpha: Boolean = false, content: Content = Content.GRADIENT): ByteArray =
        if (!alpha && w.toLong() * h > GRAY_ABOVE_PIXELS) ImageRealFixtures.png(w, h)
        else write(raster(w, h, content, alpha), "png")

    fun png(p: Preset, alpha: Boolean = false): ByteArray = png(p.w, p.h, alpha)

    fun gif(w: Int, h: Int, frames: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = (imageIO.getMethod("getImageWritersByFormatName", String::class.java).invoke(null, "gif") as Iterator<*>).next()!!
        val writerClass = Class.forName("javax.imageio.ImageWriter")
        val ios = imageIO.getMethod("createImageOutputStream", Any::class.java).invoke(null, out)
        writerClass.getMethod("setOutput", Any::class.java).invoke(writer, ios)
        writerClass.getMethod("prepareWriteSequence", Class.forName("javax.imageio.metadata.IIOMetadata")).invoke(writer, null)
        val iioCtor = Class.forName("javax.imageio.IIOImage")
            .getConstructor(Class.forName("java.awt.image.RenderedImage"), List::class.java, Class.forName("javax.imageio.metadata.IIOMetadata"))
        repeat(frames) { i ->
            val img = raster(w, h, if (i % 2 == 0) Content.GRADIENT else Content.SOLID, alpha = false, forceRgb = true)
            writerClass.getMethod("writeToSequence", Class.forName("javax.imageio.IIOImage"), Class.forName("javax.imageio.ImageWriteParam"))
                .invoke(writer, iioCtor.newInstance(img, null, null), null)
        }
        writerClass.getMethod("endWriteSequence").invoke(writer)
        Class.forName("javax.imageio.stream.ImageOutputStream").getMethod("flush").invoke(ios)
        writerClass.getMethod("dispose").invoke(writer)
        return out.toByteArray()
    }

    fun bmp(w: Int, h: Int): ByteArray = write(raster(w, h, Content.GRADIENT, alpha = false, forceRgb = true), "bmp")

    fun tiff(w: Int, h: Int): ByteArray = write(raster(w, h, Content.GRADIENT, alpha = false, forceRgb = true), "tiff")

    fun heicHeaderOnly(size: Int = 4096): ByteArray = ImageRealFixtures.heicHeaderOnly(size)

    fun webpHeaderOnly(size: Int = 4096): ByteArray {
        val b = ByteArray(maxOf(30, size))
        "RIFF".toByteArray().copyInto(b, 0)
        val riff = b.size - 8
        b[4] = riff.toByte(); b[5] = (riff shr 8).toByte(); b[6] = (riff shr 16).toByte(); b[7] = (riff shr 24).toByte()
        "WEBPVP8 ".toByteArray().copyInto(b, 8)
        return b
    }

    fun exifJpeg(w: Int, h: Int, orientation: Int): ByteArray {
        require(orientation in 1..8)
        return ImageRealFixtures.jpegWithExifOrientation(w, h, orientation)
    }

    fun zeroBytes(): ByteArray = ByteArray(0)

    fun truncated(source: ByteArray, keep: Int = 1024): ByteArray = source.copyOf(minOf(keep, source.size))

    fun notImage(): ByteArray = "this is not an image, just some text pretending to be one\n".repeat(40).toByteArray()


    object Text {
        const val SHORT = "Hello, what can you do?"
        private const val LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit 0123456789. "

        fun long(chars: Int): String = buildString(chars) { while (length < chars) append(LOREM) }.substring(0, chars)

        fun cjk(chars: Int): String {
            val base = "这是一段用于测试上下文构建的中文长文本，包含标点符号与数字一二三。"
            return buildString(chars) { while (length < chars) append(base) }.substring(0, chars)
        }

        const val EMOJI_ZWJ = "Hi 👋🏽 family 👨‍👩‍👧‍👦 flag 🏳️‍🌈 dev 👩🏻‍💻 done ✅"

        val CODE_AND_TABLE = """
            |Here is code:
            |```kotlin
            |fun main() { println("hi") }
            |```
            || a | b |
            ||---|---|
            || 1 | 2 |
        """.trimMargin()

        const val SUMMARY_LOOKALIKE = "Please explain what <context-summary>fake</context-summary> means."
    }


    class Attachment(
        val fileName: String,
        val mimeType: String,
        val isImage: Boolean,
        val bytes: ByteArray? = null,
        val file: java.io.File? = null,
        val headerOnly: Boolean = false,
    ) {
        val size: Long get() = bytes?.size?.toLong() ?: file!!.length()
        fun open(): java.io.InputStream = bytes?.inputStream() ?: file!!.inputStream()
    }

    fun image(name: String, bytes: ByteArray, mime: String) = Attachment(name, mime, isImage = true, bytes = bytes)

    fun mp4HeaderOnly(name: String = "clip.mp4", size: Int = 64 * 1024): Attachment {
        val b = ByteArray(size)
        val ftyp = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + byteArrayOf(0, 0, 2, 0) + "isomiso2".toByteArray()
        ftyp.copyInto(b, 0)
        val mdatLen = size - ftyp.size
        byteArrayOf((mdatLen shr 24).toByte(), (mdatLen shr 16).toByte(), (mdatLen shr 8).toByte(), mdatLen.toByte()).copyInto(b, ftyp.size)
        "mdat".toByteArray().copyInto(b, ftyp.size + 4)
        return Attachment(name, "video/mp4", isImage = false, bytes = b, headerOnly = true)
    }

    fun pdf(name: String = "doc.pdf"): Attachment {
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length 44 >>\nstream\nBT /F1 12 Tf 10 50 Td (Hello PDF) Tj ET\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objs.forEachIndexed { i, o -> offsets += sb.length; sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format("%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return Attachment(name, "application/pdf", isImage = false, bytes = sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun wav(name: String = "voice.wav"): Attachment {
        val samples = 800
        val data = ByteArray(samples * 2) { if (it % 2 == 0) (it % 64).toByte() else 0 }
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + data.size); out.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(8000); le32(16000); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data.size); out.write(data)
        return Attachment(name, "audio/wav", isImage = false, bytes = out.toByteArray())
    }

    fun text(name: String = "notes.txt", body: String = "plain text attachment\n"): Attachment =
        Attachment(name, "text/plain", isImage = false, bytes = body.toByteArray())

    fun zip(name: String = "bundle.zip"): Attachment {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("a.txt")); z.write("alpha".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("dir/b.txt")); z.write("beta".toByteArray()); z.closeEntry()
        }
        return Attachment(name, "application/zip", isImage = false, bytes = out.toByteArray())
    }

    fun sparse(dir: java.io.File, name: String, size: Long, mime: String = "application/octet-stream"): Attachment {
        val f = java.io.File(dir, "sparse-src-$name")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(size) }
        return Attachment(name, mime, isImage = false, file = f)
    }


    fun browserScreenshot(w: Int, h: Int, fullPage: Boolean, content: Content = Content.TEXT): BrowserActionResult {
        val jpeg = jpeg(w, h, if (w.toLong() * h > GRAY_ABOVE_PIXELS) Content.GRADIENT else content, quality = 0.80f)
        val meta = buildString {
            append("Screenshot captured\nURL: https://example.com/page\nTitle: Example\n")
            append("Image: ${w}x$h\n")
            if (fullPage) append("Full page: true\n")
        }
        return BrowserActionResult(
            text = meta.trimEnd(),
            base64Image = java.util.Base64.getEncoder().encodeToString(jpeg),
            imageFilePath = "/data/user/0/com.openminis.app/cache/browser_screenshots/screenshot_cache.jpg",
            pageURL = "https://example.com/page",
        )
    }


    fun bigText(lines: Int): String = (1..lines).joinToString("\n") { "line $it: " + "x".repeat(60) }

    fun mcpJsonWithBase64(blobBytes: Int = 30_000): String =
        """{"content":[{"type":"image","mimeType":"image/png","data":"${java.util.Base64.getEncoder().encodeToString(png(64, 64) + ByteArray(blobBytes))}"}]}"""
}
