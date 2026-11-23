package com.openminis.app.imagerepro

import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.ImageCodec

internal class ImageRealJvmCodec : ImageCodec {
    val log = mutableListOf<String>()

    private val imageIO = Class.forName("javax.imageio.ImageIO")
    private val bi = Class.forName("java.awt.image.BufferedImage")
    private val renderedImage = Class.forName("java.awt.image.RenderedImage")

    init {
        System.setProperty("java.awt.headless", "true")
    }

    override fun bounds(input: ByteArray): Pair<Int, Int> {
        val d = ImageRealFixtures.dims(input)
        log += "bounds -> ${d?.width ?: -1}x${d?.height ?: -1}"
        return if (d == null) Pair(-1, -1) else Pair(d.width, d.height)
    }

    override fun decode(input: ByteArray, inSampleSize: Int): Any? {
        log += "decode(sample=$inSampleSize)"
        return read(java.io.ByteArrayInputStream(input), inSampleSize)
    }

    override fun decode(input: ByteArray): Any? {
        log += "decode(full)"
        return read(java.io.ByteArrayInputStream(input), 1)
    }

    override fun decodeFile(path: String): Any? {
        log += "decodeFile(full)"
        return read(java.io.FileInputStream(path), 1)
    }

    private fun read(stream: java.io.InputStream, sample: Int): Any? = stream.use {
        val iis = imageIO.getMethod("createImageInputStream", Any::class.java).invoke(null, it) ?: return null
        val readers = imageIO.getMethod("getImageReaders", Any::class.java).invoke(null, iis) as Iterator<*>
        if (!readers.hasNext()) return null
        val reader = readers.next()!!
        val readerClass = Class.forName("javax.imageio.ImageReader")
        readerClass.getMethod("setInput", Any::class.java).invoke(reader, iis)
        val param = readerClass.getMethod("getDefaultReadParam").invoke(reader)
        Class.forName("javax.imageio.IIOParam").getMethod(
            "setSourceSubsampling", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(param, sample, sample, 0, 0)
        try {
            readerClass.getMethod("read", Int::class.javaPrimitiveType, Class.forName("javax.imageio.ImageReadParam"))
                .invoke(reader, 0, param)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            null
        } finally {
            readerClass.getMethod("dispose").invoke(reader)
        }
    }

    override fun width(bitmap: Any): Int = bi.getMethod("getWidth").invoke(bitmap) as Int

    override fun height(bitmap: Any): Int = bi.getMethod("getHeight").invoke(bitmap) as Int

    override fun scale(bitmap: Any, width: Int, height: Int): Any {
        log += "scale -> ${width}x$height"
        if (width(bitmap) == width && height(bitmap) == height) return bitmap
        val hasAlpha = Class.forName("java.awt.image.ColorModel").getMethod("hasAlpha")
            .invoke(bi.getMethod("getColorModel").invoke(bitmap)) as Boolean
        return draw(bitmap, width, height, if (hasAlpha) 2  else 1 )
    }

    private fun draw(src: Any, w: Int, h: Int, type: Int): Any {
        val dst = bi.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(w, h, type)
        val g = bi.getMethod("createGraphics").invoke(dst)
        val g2 = Class.forName("java.awt.Graphics2D")
        val hints = Class.forName("java.awt.RenderingHints")
        g2.getMethod("setRenderingHint", Class.forName("java.awt.RenderingHints\$Key"), Any::class.java)
            .invoke(g, hints.getField("KEY_INTERPOLATION").get(null), hints.getField("VALUE_INTERPOLATION_BILINEAR").get(null))
        Class.forName("java.awt.Graphics").getMethod(
            "drawImage", Class.forName("java.awt.Image"), Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Class.forName("java.awt.image.ImageObserver"),
        ).invoke(g, src, 0, 0, w, h, null)
        Class.forName("java.awt.Graphics").getMethod("dispose").invoke(g)
        return dst
    }

    override fun compress(bitmap: Any, png: Boolean, quality: Int, out: java.io.OutputStream) {
        log += if (png) "compress(png)" else "compress(jpeg q=$quality)"
        if (png) {
            imageIO.getMethod("write", renderedImage, String::class.java, java.io.OutputStream::class.java).invoke(null, bitmap, "png", out)
            return
        }
        val rgb = draw(bitmap, width(bitmap), height(bitmap), 1 )
        val writer = (imageIO.getMethod("getImageWritersByFormatName", String::class.java).invoke(null, "jpeg") as Iterator<*>).next()!!
        val writerClass = Class.forName("javax.imageio.ImageWriter")
        val ios = imageIO.getMethod("createImageOutputStream", Any::class.java).invoke(null, out)
        writerClass.getMethod("setOutput", Any::class.java).invoke(writer, ios)
        val param = writerClass.getMethod("getDefaultWriteParam").invoke(writer)
        val iwp = Class.forName("javax.imageio.ImageWriteParam")
        iwp.getMethod("setCompressionMode", Int::class.javaPrimitiveType).invoke(param, iwp.getField("MODE_EXPLICIT").get(null))
        iwp.getMethod("setCompressionQuality", Float::class.javaPrimitiveType).invoke(param, quality / 100f)
        val iioImage = Class.forName("javax.imageio.IIOImage")
            .getConstructor(renderedImage, List::class.java, Class.forName("javax.imageio.metadata.IIOMetadata"))
            .newInstance(rgb, null, null)
        writerClass.getMethod("write", Class.forName("javax.imageio.metadata.IIOMetadata"), Class.forName("javax.imageio.IIOImage"), iwp)
            .invoke(writer, null, iioImage, param)
        Class.forName("javax.imageio.stream.ImageOutputStream").getMethod("flush").invoke(ios)
        writerClass.getMethod("dispose").invoke(writer)
    }

    override fun recycle(bitmap: Any) {
        log += "recycle"
    }

    companion object {
        fun <T> with(block: (ImageRealJvmCodec) -> T): T {
            val codec = ImageRealJvmCodec()
            val previous = ImageBudget.codec
            ImageBudget.codec = codec
            try {
                return block(codec)
            } finally {
                ImageBudget.codec = previous
            }
        }
    }
}
