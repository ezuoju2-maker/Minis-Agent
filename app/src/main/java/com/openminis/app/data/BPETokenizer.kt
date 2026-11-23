package com.openminis.app.data

import android.content.Context
import kotlin.math.ceil
import kotlin.math.max

object BPETokenizer {
    const val TOKENS_PER_MESSAGE = 3
    const val TOKENS_PER_REPLY = 3
    private const val IMAGE_GRID = 32
    private const val IMAGE_MAX_EDGE = 2048
    private const val IMAGE_MIN_TOKENS = 85
    private const val IMAGE_FAILURE_FALLBACK = 1000

    @Volatile private var encoder: Map<List<Byte>, Int>? = null

    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val vocab = encoder
        if (vocab == null) return heuristicTokenCount(text)
        return bpeEncode(text.toByteArray(Charsets.UTF_8), vocab).size
    }

    fun countImageTokens(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val (outWidth, outHeight) = com.openminis.app.provider.ImageBudget.codec.bounds(data)
        val w = outWidth.toFloat()
        val h = outHeight.toFloat()
        if (w <= 0f || h <= 0f) return IMAGE_FAILURE_FALLBACK
        val longEdge = max(w, h)
        val scale = if (longEdge > IMAGE_MAX_EDGE) IMAGE_MAX_EDGE / longEdge else 1f
        val scaledW = w * scale
        val scaledH = h * scale
        val tokens = (ceil(scaledW / IMAGE_GRID) * ceil(scaledH / IMAGE_GRID)).toInt()
        return max(IMAGE_MIN_TOKENS, tokens)
    }

    fun loadVocabularyFromAssets(context: Context, fileName: String = "cl100k_base.tiktoken"): Boolean {
        if (encoder != null) return true
        return try {
            val dict = HashMap<List<Byte>, Int>(100_300)
            context.assets.open(fileName).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val space = line.indexOf(' ')
                    if (space <= 0) continue
                    val decoded = runCatching {
                        android.util.Base64.decode(
                            line.substring(0, space),
                            android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP,
                        )
                    }.getOrNull() ?: continue
                    val rank = line.substring(space + 1).trim().toIntOrNull() ?: continue
                    dict[decoded.toList()] = rank
                }
            }
            if (dict.isNotEmpty()) {
                encoder = dict
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun heuristicTokenCount(text: String): Int {
        var cp = 0
        var i = 0
        while (i < text.length) {
            cp++
            i += if (text[i].isHighSurrogate() && i + 1 < text.length) 2 else 1
        }
        return max(1, cp / 3)
    }

    private fun bpeEncode(bytes: ByteArray, vocab: Map<List<Byte>, Int>): List<Int> {
        if (bytes.isEmpty()) return emptyList()
        val segments = ArrayList<IntArray>(bytes.size)
        for (i in bytes.indices) segments.add(intArrayOf(i, i + 1))
        while (segments.size > 1) {
            var minRank = Int.MAX_VALUE
            var minIdx = -1
            for (i in 0 until segments.size - 1) {
                val start = segments[i][0]
                val end = segments[i + 1][1]
                val slice = bytes.sliceArray(start until end).toList()
                val rank = vocab[slice] ?: continue
                if (rank < minRank) {
                    minRank = rank
                    minIdx = i
                }
            }
            if (minIdx < 0) break
            segments[minIdx] = intArrayOf(segments[minIdx][0], segments[minIdx + 1][1])
            segments.removeAt(minIdx + 1)
        }
        return segments.mapNotNull { seg ->
            vocab[bytes.sliceArray(seg[0] until seg[1]).toList()]
        }
    }
}
