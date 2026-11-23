package com.openminis.app.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream

object ImageBudget {
    fun estimatedBase64Length(rawBytes: Int): Long = estimatedBase64Length(rawBytes.toLong())

    fun estimatedBase64Length(rawBytes: Long): Long = ((rawBytes + 2) / 3) * 4

    const val MAX_PER_IMAGE_BYTES = 5L * 1024 * 1024

    const val MAX_PER_IMAGE_RAW_BYTES = MAX_PER_IMAGE_BYTES / 4 * 3

    const val MAX_TOTAL_BYTES = 25L * 1024 * 1024

    const val MAX_REQUEST_BYTES = 25L * 1024 * 1024

    const val MAX_EDGE_PX = 2000

    const val JPEG_QUALITY = 80

    private const val TAG = "ImageBudget"

    @Volatile
    internal var codec: ImageCodec = AndroidImageCodec

    fun resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
        val codec = codec
        val bmp = codec.decode(data) ?: return null
        val longest = maxOf(codec.width(bmp), codec.height(bmp))
        if (longest <= maxEdge) { codec.recycle(bmp); return null }
        val scale = maxEdge.toFloat() / longest
        val w = (codec.width(bmp) * scale).toInt()
        val h = (codec.height(bmp) * scale).toInt()
        val resized = codec.scale(bmp, w, h)
        codec.recycle(bmp)
        val out = java.io.ByteArrayOutputStream()
        codec.compress(resized, png = false, quality = 85, out = out)
        codec.recycle(resized)
        return out.toByteArray()
    }

    private val LADDER: List<Pair<Int, Int>> = listOf(
        2000 to 80,
        1600 to 75,
        1280 to 70,
        1024 to 65,
        896 to 55,
        768 to 50,
        640 to 45,
    )

    fun compressBytes(input: ByteArray, maxEdge: Int = MAX_EDGE_PX, q: Int = JPEG_QUALITY): ByteArray {
        if (input.isEmpty()) return input
        return try {
            val codec = codec
            val (w0, h0) = codec.bounds(input)
            if (w0 <= 0 || h0 <= 0) return input
            var sample = 1
            while (w0 / sample > maxEdge * 2 || h0 / sample > maxEdge * 2) sample *= 2
            val decoded = codec.decode(input, sample)
                ?: return input
            val scale = minOf(
                maxEdge.toFloat() / codec.width(decoded),
                maxEdge.toFloat() / codec.height(decoded),
                1f,
            )
            val out = if (scale < 1f) {
                codec.scale(
                    decoded,
                    (codec.width(decoded) * scale).toInt().coerceAtLeast(1),
                    (codec.height(decoded) * scale).toInt().coerceAtLeast(1),
                )
            } else decoded
            val baos = ByteArrayOutputStream()
            codec.compress(out, png = false, quality = q.coerceIn(1, 100), out = baos)
            if (out !== decoded) codec.recycle(out)
            codec.recycle(decoded)
            baos.toByteArray()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "compressBytes failed (${input.size}B → keeping original): ${t.message}")
            input
        }
    }


    class Downscaled(
        val bytes: ByteArray,
        val mimeType: String,
        val originalWidth: Int,
        val originalHeight: Int,
        val width: Int,
        val height: Int,
    )

    internal fun fitWithin(w: Int, h: Int, maxEdge: Int): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return null
        val scale = maxEdge.toDouble() / longest
        return Pair(
            maxOf(1, Math.round(w * scale).toInt()),
            maxOf(1, Math.round(h * scale).toInt()),
        )
    }

    fun downscaleForModel(input: ByteArray, maxEdge: Int = MAX_EDGE_PX): Downscaled? {
        if (input.isEmpty()) return null
        return try {
            val codec = codec
            val (w0, h0) = codec.bounds(input)
            val (tw, th) = fitWithin(w0, h0, maxEdge) ?: return null
            var sample = 1
            while (w0 / (sample * 2) >= tw && h0 / (sample * 2) >= th) sample *= 2
            val decoded = codec.decode(input, sample) ?: return null
            val scaled = codec.scale(decoded, tw, th)
            val png = sniffFormat(input) == ImageFormat.PNG
            val baos = ByteArrayOutputStream()
            codec.compress(scaled, png = png, quality = 85, out = baos)
            if (scaled !== decoded) codec.recycle(scaled)
            codec.recycle(decoded)
            Downscaled(
                baos.toByteArray(),
                if (png) ImageFormat.PNG.mimeType!! else ImageFormat.JPEG.mimeType!!,
                w0, h0, tw, th,
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "downscaleForModel failed (${input.size}B): ${t.message}")
            null
        }
    }

    private val restoredDownscaleCache = object : LinkedHashMap<String, Downscaled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Downscaled>?) = size > 16
    }

    fun downscaleForModel(file: java.io.File, bytes: ByteArray): Downscaled? {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(restoredDownscaleCache) { restoredDownscaleCache[key] }?.let { return it }
        val out = downscaleForModel(bytes) ?: return null
        synchronized(restoredDownscaleCache) { restoredDownscaleCache[key] = out }
        return out
    }

    fun downscaleNote(d: Downscaled, linuxPath: String?): String = buildString {
        append("[Image downscaled before sending: the original is ")
        append(d.originalWidth).append('×').append(d.originalHeight)
        append(" px, shown here at ").append(d.width).append('×').append(d.height)
        append(" px, so fine detail and small text may be unreadable.")
        if (!linuxPath.isNullOrBlank()) append(" The full-size original is saved at ").append(linuxPath).append('.')
        append(']')
    }


    enum class ImageFormat(val mimeType: String?) {
        JPEG("image/jpeg"),
        PNG("image/png"),
        GIF("image/gif"),
        WEBP("image/webp"),
        HEIF(null),
        AVIF(null),
        BMP(null),
        TIFF(null),
        UNKNOWN(null);

        val isUploadable: Boolean get() = mimeType != null
    }

    fun sniffFormat(b: ByteArray): ImageFormat {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        fun ascii(from: Int, s: String) = s.indices.all { at(from + it) == s[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> ImageFormat.JPEG
            at(0) == 0x89 && ascii(1, "PNG") -> ImageFormat.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> ImageFormat.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageFormat.WEBP
            ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> ImageFormat.AVIF
            ascii(4, "ftyp") && listOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
                .any { ascii(8, it) } -> ImageFormat.HEIF
            ascii(0, "BM") -> ImageFormat.BMP
            ascii(0, "II*\u0000") || ascii(0, "MM\u0000*") -> ImageFormat.TIFF
            else -> ImageFormat.UNKNOWN
        }
    }

    class UploadImage(val bytes: ByteArray, val mimeType: String) {
        fun base64(): String = java.util.Base64.getEncoder().encodeToString(bytes)
        fun dataUrl(): String = "data:$mimeType;base64,${base64()}"
    }

    const val UNSENDABLE_IMAGE_PLACEHOLDER =
        "[Image omitted: its file format could not be converted to one this model accepts]"

    fun prepareForUpload(input: ByteArray, declaredMime: String?): UploadImage? =
        prepareForUpload(input, declaredMime, ::transcodeCached, ::compressUnderBudget, ::fitCached)

    internal fun prepareForUpload(
        input: ByteArray,
        declaredMime: String?,
        transcode: (ByteArray) -> ByteArray?,
        budget: (ByteArray) -> ByteArray,
        fit: (ByteArray) -> ByteArray? = { null },
    ): UploadImage? {
        if (input.isEmpty()) return null
        val sourceFormat = sniffFormat(input)
        val accepted = if (sourceFormat.isUploadable) input else {
            transcode(input) ?: run {
                if (sourceFormat == ImageFormat.UNKNOWN) {
                    AppLogger.warning(
                        TAG,
                        "[ImageFormat] unrecognized ${input.size}B (declared ${declaredMime ?: "none"}) " +
                            "did not decode — sending as declared",
                    )
                    val sized = budget(input)
                    val mime = sniffFormat(sized).mimeType ?: declaredMime ?: ImageFormat.JPEG.mimeType!!
                    return UploadImage(sized, mime)
                }
                AppLogger.warning(
                    TAG,
                    "[ImageFormat] $sourceFormat (declared ${declaredMime ?: "none"}, ${input.size}B) " +
                        "could not be decoded — sending a text placeholder instead",
                )
                return null
            }
        }
        val fitted = fit(accepted)?.also {
            AppLogger.info(TAG, "[ImageFormat] fitted within ${MAX_EDGE_PX}px: ${accepted.size}B → ${it.size}B")
        } ?: accepted
        val sized = budget(fitted)
        val finalFormat = sniffFormat(sized)
        val mime = finalFormat.mimeType ?: return null
        if (sourceFormat != finalFormat || !sameMime(declaredMime, mime)) {
            AppLogger.info(
                TAG,
                "[ImageFormat] declared=${declaredMime ?: "none"} source=$sourceFormat " +
                    "${input.size}B → sending $mime ${sized.size}B",
            )
        }
        return UploadImage(sized, mime)
    }

    private fun sameMime(declared: String?, actual: String): Boolean {
        val d = declared?.trim()?.lowercase() ?: return false
        return d == actual || (actual == "image/jpeg" && d == "image/jpg")
    }

    private fun transcodeToJpeg(input: ByteArray): ByteArray? {
        val out = compressBytes(input, MAX_EDGE_PX, JPEG_QUALITY)
        return out.takeIf { it !== input && sniffFormat(it) == ImageFormat.JPEG }
    }

    private val transcodeCache = java.util.WeakHashMap<ByteArray, ByteArray>()

    private val fitCache = java.util.WeakHashMap<ByteArray, Any>()
    private val NO_FIT = Any()

    private fun fitCached(input: ByteArray): ByteArray? {
        when (val hit = synchronized(fitCache) { fitCache[input] }) {
            NO_FIT -> return null
            is ByteArray -> return hit
        }
        val out = downscaleForModel(input)?.bytes
        synchronized(fitCache) { fitCache[input] = out ?: NO_FIT }
        return out
    }

    private fun transcodeCached(input: ByteArray): ByteArray? {
        synchronized(transcodeCache) { transcodeCache[input] }?.let { return it }
        val out = transcodeToJpeg(input) ?: return null
        synchronized(transcodeCache) { transcodeCache[input] = out }
        return out
    }

    fun compressUnderBudget(input: ByteArray, targetMaxBytes: Long = MAX_PER_IMAGE_RAW_BYTES): ByteArray {
        if (input.size <= targetMaxBytes) return input
        var best: ByteArray = input
        var bestSize = input.size
        for ((edge, q) in LADDER) {
            val candidate = compressBytes(input, edge, q)
            if (candidate.size < bestSize) {
                best = candidate
                bestSize = candidate.size
            }
            if (candidate.size.toLong() <= targetMaxBytes) {
                AppLogger.info(TAG, "compressUnderBudget hit: ${input.size}B → ${candidate.size}B (edge=$edge q=$q)")
                return candidate
            }
        }
        AppLogger.warning(TAG, "compressUnderBudget exhausted ladder: ${input.size}B → ${best.size}B (target=${targetMaxBytes}B)")
        return best
    }

    data class BudgetResult(
        val keptBytes: List<ByteArray>,
        val compressedCount: Int,
        val droppedCount: Int,
        val totalBytes: Long,
    ) {
        val mutated: Boolean get() = compressedCount > 0 || droppedCount > 0
    }

    fun applyMessageBudget(bytesIn: List<ByteArray>): BudgetResult {
        if (bytesIn.isEmpty()) return BudgetResult(emptyList(), 0, 0, 0L)
        val kept = ArrayList<ByteArray>(bytesIn.size)
        var compressed = 0
        var dropped = 0
        var running = 0L
        for (part in bytesIn) {
            val sized = if (estimatedBase64Length(part.size) > MAX_PER_IMAGE_BYTES) {
                val c = compressUnderBudget(part, MAX_PER_IMAGE_RAW_BYTES)
                if (c.size != part.size) compressed += 1
                c
            } else part
            val sizedOnWire = estimatedBase64Length(sized.size)
            if (running + sizedOnWire > MAX_TOTAL_BYTES) {
                dropped += 1
                continue
            }
            kept.add(sized)
            running += sizedOnWire
        }
        if (dropped > 0 || compressed > 0) {
            AppLogger.info(
                TAG,
                "applyMessageBudget: in=${bytesIn.size} kept=${kept.size} compressed=$compressed dropped=$dropped total=${running}B",
            )
        }
        return BudgetResult(kept, compressed, dropped, running)
    }


    @JvmInline
    value class ImagePartId(val identityHash: Int) {
        companion object {
            fun of(data: ByteArray): ImagePartId = ImagePartId(System.identityHashCode(data))
        }
    }

    data class BudgetImage(
        val data: ByteArray,
        val linuxPath: String?,
        val mimeType: String,
        val wireBytes: Long? = null,
    )

    data class RequestBudgetPlan(
        val droppedIds: Set<ImagePartId>,
        val droppedPaths: Map<ImagePartId, String?>,
        val keptBytes: Long,
        val elidedBytes: Long,
        val droppedCount: Int,
        val totalCount: Int,
        val droppedIndices: Set<Int> = emptySet(),
    ) {
        val mutated: Boolean get() = droppedCount > 0
    }

    fun planRequestBudget(
        images: List<BudgetImage>,
        maxBytes: Long = MAX_REQUEST_BYTES,
    ): RequestBudgetPlan {
        if (images.isEmpty()) {
            return RequestBudgetPlan(emptySet(), emptyMap(), 0L, 0L, 0, 0)
        }
        val dropped = HashSet<ImagePartId>()
        val droppedPaths = HashMap<ImagePartId, String?>()
        val droppedIndices = HashSet<Int>()
        var kept = 0L
        var elided = 0L
        for (index in images.indices.reversed()) {
            val img = images[index]
            val id = ImagePartId.of(img.data)
            val effectiveSize = img.wireBytes ?: minOf(estimatedBase64Length(img.data.size), MAX_PER_IMAGE_BYTES)
            if (kept + effectiveSize <= maxBytes) {
                kept += effectiveSize
            } else {
                dropped.add(id)
                droppedPaths[id] = img.linuxPath
                droppedIndices.add(index)
                elided += effectiveSize
            }
        }
        if (dropped.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "planRequestBudget: in=${images.size} kept=${images.size - dropped.size} dropped=${dropped.size} keptBytes=${kept}B elidedBytes=${elided}B cap=${maxBytes}B",
            )
        }
        return RequestBudgetPlan(
            droppedIds = dropped,
            droppedPaths = droppedPaths,
            keptBytes = kept,
            elidedBytes = elided,
            droppedCount = droppedIndices.size,
            totalCount = images.size,
            droppedIndices = droppedIndices,
        )
    }

    class RequestBudgetApplied(val messages: List<LLMMessage>, val plan: RequestBudgetPlan?)

    fun applyRequestBudget(
        messages: List<LLMMessage>,
        resolveAttachmentsRoot: () -> java.io.File?,
    ): RequestBudgetApplied {
        data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
        fun measured(data: ByteArray, mime: String, path: String?) = ImageBudget.BudgetImage(
            data, path, mime,
            wireBytes = prepareForUpload(data, mime)?.let { estimatedBase64Length(it.bytes.size) } ?: 0L,
        )
        val images = mutableListOf<ImageRef>()
        messages.forEachIndexed { mi, msg ->
            msg.contentParts.forEachIndexed { pi, part ->
                when (part) {
                    is AgentContentPart.ImageData -> {
                        images.add(
                            ImageRef(
                                mi, pi,
                                measured(part.data, part.mimeType, part.linuxPath),
                            )
                        )
                    }
                    is AgentContentPart.ToolResult -> {
                        val img = part.imageData
                        if (img != null) {
                            images.add(
                                ImageRef(
                                    mi, pi,
                                    measured(img, part.imageMimeType ?: "image/jpeg", part.imageLinuxPath),
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (images.isEmpty()) return RequestBudgetApplied(messages, null)

        val plan = ImageBudget.planRequestBudget(images.map { it.image })
        if (!plan.mutated) return RequestBudgetApplied(messages, null)

        val attachmentsRoot = resolveAttachmentsRoot()
        val resolvedPaths = HashMap<Int, String?>()
        for ((id, ref) in images.withIndex()) {
            if (id !in plan.droppedIndices) continue
            val existing = ref.image.linuxPath
            if (existing != null) {
                resolvedPaths[id] = existing
            } else if (attachmentsRoot != null) {
                resolvedPaths[id] = ImageBudget.ensureSpillover(
                    attachmentsRoot, ref.image.data, ref.image.mimeType,
                )
            } else {
                resolvedPaths[id] = null
            }
        }

        val byMsg = images.withIndex().groupBy { it.value.msgIdx }
        val mutated = messages.toMutableList()
        for ((mi, refs) in byMsg) {
            val msg = mutated[mi]
            val newParts = msg.contentParts.toMutableList()
            for ((id, ref) in refs) {
                if (id !in plan.droppedIndices) continue
                val path = resolvedPaths[id]
                val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
                val originalPart = newParts[ref.partIdx]
                newParts[ref.partIdx] = when (originalPart) {
                    is AgentContentPart.ImageData -> placeholder
                    is AgentContentPart.ToolResult -> originalPart.copy(
                        imageData = null,
                        imageMimeType = null,
                        content = originalPart.content +
                            (if (originalPart.content.isEmpty()) "" else "\n") +
                            ImageBudget.elidedImagePlaceholder(path),
                    )
                    else -> originalPart
                }
            }
            mutated[mi] = msg.copy(contentParts = newParts)
        }

        return RequestBudgetApplied(mutated, plan)
    }

    fun elidedImagePlaceholder(linuxPath: String?): String {
        return if (linuxPath != null) {
            "[image elided to fit 25MB request budget. Original at $linuxPath — re-fetch with `read_image $linuxPath` if you need to see it.]"
        } else {
            "[image elided to fit 25MB request budget. Original bytes no longer addressable; ask the user to re-attach if needed.]"
        }
    }

    fun ensureSpillover(
        sessionAttachmentsDir: java.io.File,
        data: ByteArray,
        mimeType: String,
    ): String? {
        if (data.isEmpty()) return null
        val ext = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "bin"
        }
        val sha = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.update(data)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            System.identityHashCode(data).toString(16)
        }
        val spilloverDir = java.io.File(sessionAttachmentsDir, "spillover")
        if (!spilloverDir.exists()) {
            try {
                spilloverDir.mkdirs()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover mkdirs failed: ${e.message}")
                return null
            }
        }
        val file = java.io.File(spilloverDir, "$sha.$ext")
        if (!file.exists()) {
            try {
                file.writeBytes(data)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover write failed: ${e.message}")
                return null
            }
        }
        return "/var/minis/attachments/spillover/$sha.$ext"
    }
}

internal interface ImageCodec {
    fun bounds(input: ByteArray): Pair<Int, Int>

    fun decode(input: ByteArray, inSampleSize: Int): Any?

    fun decode(input: ByteArray): Any?

    fun decodeFile(path: String): Any?

    fun width(bitmap: Any): Int

    fun height(bitmap: Any): Int

    fun scale(bitmap: Any, width: Int, height: Int): Any

    fun compress(bitmap: Any, png: Boolean, quality: Int, out: java.io.OutputStream)

    fun recycle(bitmap: Any)
}

internal object AndroidImageCodec : ImageCodec {
    override fun bounds(input: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, opts)
        return Pair(opts.outWidth, opts.outHeight)
    }

    override fun decode(input: ByteArray, inSampleSize: Int): Any? =
        BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { this.inSampleSize = inSampleSize })

    override fun decode(input: ByteArray): Any? = BitmapFactory.decodeByteArray(input, 0, input.size)

    override fun decodeFile(path: String): Any? = BitmapFactory.decodeFile(path)

    override fun width(bitmap: Any): Int = (bitmap as Bitmap).width

    override fun height(bitmap: Any): Int = (bitmap as Bitmap).height

    override fun scale(bitmap: Any, width: Int, height: Int): Any =
        Bitmap.createScaledBitmap(bitmap as Bitmap, width, height, true)

    override fun compress(bitmap: Any, png: Boolean, quality: Int, out: java.io.OutputStream) {
        (bitmap as Bitmap).compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, out)
    }

    override fun recycle(bitmap: Any) {
        (bitmap as Bitmap).recycle()
    }
}
