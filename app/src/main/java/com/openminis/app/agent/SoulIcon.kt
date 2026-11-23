package com.openminis.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

object SoulIcon {

    const val DATA_URI_PREFIX = "data:image/png;base64,"

    const val STORED_PIXELS = 96

    const val MAX_DATA_URI_CHARS = 64 * 1024

    fun isDataUri(value: String): Boolean = value.startsWith(DATA_URI_PREFIX)

    const val SIDECAR_NAME = "SOUL.icon.png"

    fun isSidecarRef(value: String): Boolean = value.trim() == SIDECAR_NAME

    fun pngBytes(dataUri: String): ByteArray? {
        if (!isDataUri(dataUri)) return null
        return decodeBase64(dataUri.removePrefix(DATA_URI_PREFIX))
    }

    fun dataUri(png: ByteArray): String =
        DATA_URI_PREFIX + Base64.encodeToString(png, Base64.NO_WRAP)

    const val CORNER_RADIUS_FRACTION = 0.22f

    enum class Rejection { UNREADABLE, TOO_LARGE }

    sealed class EncodeResult {
        data class Success(val dataUri: String) : EncodeResult()
        data class Failure(val reason: Rejection) : EncodeResult()
    }

    fun encode(source: Bitmap): EncodeResult {
        if (source.width <= 0 || source.height <= 0) {
            return EncodeResult.Failure(Rejection.UNREADABLE)
        }

        val square = squareCropped(source)
        val side = minOf(STORED_PIXELS, square.width, square.height)
        val scaled = if (square.width == side && square.height == side) {
            square
        } else {
            Bitmap.createScaledBitmap(square, side, side, true)
        }

        val png = ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return EncodeResult.Failure(Rejection.UNREADABLE)
            }
            out.toByteArray()
        }

        val encoded = DATA_URI_PREFIX + Base64.encodeToString(png, Base64.NO_WRAP)
        if (encoded.length > MAX_DATA_URI_CHARS) {
            return EncodeResult.Failure(Rejection.TOO_LARGE)
        }
        return EncodeResult.Success(encoded)
    }

    fun decode(value: String): Bitmap? {
        if (!isDataUri(value)) return null
        return runCatching {
            val bytes = Base64.decode(value.removePrefix(DATA_URI_PREFIX), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    sealed class Source {
        data class Bytes(val data: ByteArray) : Source() {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Bytes && data.contentEquals(other.data))
            override fun hashCode(): Int = data.contentHashCode()
        }
        data class LinuxPath(val path: String) : Source()
        data class Unsupported(val reason: String) : Source()
    }

    fun classifySource(raw: String): Source {
        val v = raw.trim()
        return when {
            v.startsWith("http://", true) || v.startsWith("https://", true) ->
                Source.Unsupported(
                    "remote URLs aren't supported on Android — download the file first, " +
                        "then pass a path like /var/minis/attachments/icon.png",
                )
            v.startsWith("minis://") -> {
                val rest = v.removePrefix("minis://").trimStart('/')
                if (rest.isEmpty()) Source.Unsupported("empty minis:// path")
                else Source.LinuxPath("/var/minis/$rest")
            }
            v.startsWith("data:") -> {
                val comma = v.indexOf(',')
                val meta = if (comma > 0) v.substring(0, comma) else ""
                if (comma < 0 || !meta.contains(";base64")) {
                    Source.Unsupported("only base64 data URIs are supported")
                } else {
                    decodeBase64(v.substring(comma + 1))
                        ?.let { Source.Bytes(it) }
                        ?: Source.Unsupported("the data URI's base64 could not be decoded")
                }
            }
            v.startsWith("/") -> Source.LinuxPath(v)
            looksLikeBareBase64(v) ->
                decodeBase64(v)?.let { Source.Bytes(it) }
                    ?: Source.Unsupported("that base64 could not be decoded")
            else -> Source.Unsupported(
                "not an emoji, a data URI, base64, a minis:// resource or a /var/minis path",
            )
        }
    }

    private fun decodeBase64(s: String): ByteArray? = runCatching {
        Base64.decode(s.trim(), Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun looksLikeBareBase64(v: String): Boolean =
        v.length >= 32 && v.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

    val ALLOWED_LINUX_ROOTS = listOf(
        "/var/minis/attachments",
        "/var/minis/workspace",
        "/var/minis/offloads",
        "/var/minis/shared",
        "/var/minis/memory",
        "/var/minis/skills",
    )

    fun isContained(candidate: java.io.File, root: java.io.File): Boolean {
        val c = runCatching { candidate.canonicalFile }.getOrElse { return false }
        val r = runCatching { root.canonicalFile }.getOrElse { return false }
        return c == r || c.path.startsWith(r.path + java.io.File.separator)
    }

    private fun squareCropped(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w == h) return bitmap
        val side = minOf(w, h)
        return Bitmap.createBitmap(bitmap, (w - side) / 2, (h - side) / 2, side, side)
    }


    val SUGGESTED_EMOJI: List<String> = listOf(
        "✨", "🤖", "🐱", "🦊", "🐧", "🦉", "🌟", "⚡",
        "🧠", "💡", "🔮", "🚀", "🌊", "🍀", "🎯", "🐳",
    )

    fun normalizeEmojiInput(input: String): String =
        graphemeClusters(input).lastOrNull { isEmojiGlyph(it) } ?: ""

    fun isEmojiGlyph(cluster: String): Boolean {
        if (cluster.isEmpty()) return false
        val codePoints = cluster.codePoints().toArray()
        if (codePoints.isEmpty()) return false

        if (codePoints.size > 1) {
            if (codePoints.any { it == 0xFE0F || it == 0x20E3 || it == 0x200D }) return true
            if (codePoints.all { it in 0x1F1E6..0x1F1FF }) return true
            return codePoints.any { hasEmojiPresentation(it) }
        }
        return hasEmojiPresentation(codePoints[0])
    }

    private fun hasEmojiPresentation(cp: Int): Boolean = when (cp) {
        0x231A, 0x231B,
        0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23F0, 0x23F3,
        0x25FD, 0x25FE,
        0x2614, 0x2615,
        0x2648, 0x2649, 0x264A, 0x264B, 0x264C, 0x264D,
        0x264E, 0x264F, 0x2650, 0x2651, 0x2652, 0x2653,
        0x267F, 0x2693, 0x26A1, 0x26AA, 0x26AB,
        0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26D4,
        0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26FA, 0x26FD,
        0x2705, 0x270A, 0x270B,
        0x2728,
        0x274C, 0x274E,
        0x2753, 0x2754, 0x2755, 0x2757,
        0x2795, 0x2796, 0x2797,
        0x27B0, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B55,
        -> true
        else -> when (cp) {
            in 0x2B05..0x2B07 -> false
            in 0x1F004..0x1F0CF -> true
            in 0x1F18E..0x1F19A -> true
            in 0x1F1E6..0x1F1FF -> false
            in 0x1F200..0x1F251 -> true
            in 0x1F300..0x1F320 -> true
            in 0x1F32D..0x1F335 -> true
            in 0x1F337..0x1F37C -> true
            in 0x1F37E..0x1F393 -> true
            in 0x1F3A0..0x1F3CA -> true
            in 0x1F3CF..0x1F3D3 -> true
            in 0x1F3E0..0x1F3F0 -> true
            in 0x1F3F4..0x1F3F4 -> true
            in 0x1F3F8..0x1F43E -> true
            in 0x1F440..0x1F4FC -> true
            in 0x1F4FF..0x1F53D -> true
            in 0x1F54B..0x1F54E -> true
            in 0x1F550..0x1F567 -> true
            in 0x1F57A..0x1F57A -> true
            in 0x1F595..0x1F596 -> true
            in 0x1F5A4..0x1F5A4 -> true
            in 0x1F5FB..0x1F64F -> true
            in 0x1F680..0x1F6C5 -> true
            in 0x1F6CC..0x1F6CC -> true
            in 0x1F6D0..0x1F6D2 -> true
            in 0x1F6EB..0x1F6EC -> true
            in 0x1F6F4..0x1F6FC -> true
            in 0x1F7E0..0x1F7EB -> true
            in 0x1F90C..0x1F93A -> true
            in 0x1F93C..0x1F945 -> true
            in 0x1F947..0x1F978 -> true
            in 0x1F97A..0x1F9CB -> true
            in 0x1F9CD..0x1F9FF -> true
            in 0x1FA70..0x1FAFF -> true
            else -> false
        }
    }

    fun graphemeClusters(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val cps = input.codePoints().toArray()
        var i = 0
        while (i < cps.size) {
            val start = i
            i++
            if (isRegionalIndicator(cps[start]) &&
                i < cps.size && isRegionalIndicator(cps[i])
            ) {
                i++
            } else {
                i = absorbModifiers(cps, i)
                while (i < cps.size && cps[i] == ZWJ && i + 1 < cps.size) {
                    i += 2
                    i = absorbModifiers(cps, i)
                }
            }
            out.add(String(cps, start, i - start))
        }
        return out
    }

    private const val ZWJ = 0x200D

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun absorbModifiers(cps: IntArray, from: Int): Int {
        var i = from
        while (i < cps.size) {
            val cp = cps[i]
            val isModifier = cp == 0xFE0E || cp == 0xFE0F ||
                cp == 0x20E3 ||
                cp in 0x1F3FB..0x1F3FF ||
                cp in 0x0300..0x036F ||
                cp in 0xE0020..0xE007F
            if (!isModifier) break
            i++
        }
        return i
    }
}
