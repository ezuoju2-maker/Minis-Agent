package com.openminis.app.clientfrail

import java.nio.charset.StandardCharsets
import java.util.Locale

internal object FormsCodec {

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val HEX = "0123456789abcdef"
    private const val MORSE_GAP = " / "
    private const val MORSE_LETTER_GAP = " "

    private val MORSE: Map<Char, String> = mapOf(
        'a' to ".-", 'b' to "-...", 'c' to "-.-.", 'd' to "-..", 'e' to ".",
        'f' to "..-.", 'g' to "--.", 'h' to "....", 'i' to "..", 'j' to ".---",
        'k' to "-.-", 'l' to ".-..", 'm' to "--", 'n' to "-.", 'o' to "---",
        'p' to ".--.", 'q' to "--.-", 'r' to ".-.", 's' to "...", 't' to "-",
        'u' to "..-", 'v' to "...-", 'w' to ".--", 'x' to "-..-", 'y' to "-.--",
        'z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---",
        '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....",
        '7' to "--...", '8' to "---..", '9' to "----.", '.' to ".-.-.-",
        ',' to "--..--", '?' to "..--..", ' ' to "/",
    )

    private val TAG_WHITELIST = listOf("data", "spec", "body", "note", "fragment", "task")

    fun base32(text: String): String {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun base58(text: String): String {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        var value = java.math.BigInteger(1, bytes)
        val sb = StringBuilder()
        while (value.signum() > 0) {
            val div = value.divide(java.math.BigInteger.valueOf(58))
            sb.append(B58[value.subtract(div.multiply(java.math.BigInteger.valueOf(58))).toInt()])
            value = div
        }
        for (b in bytes) if (b == 0.toByte()) sb.append('1') else break
        return sb.reverse().toString()
    }

    fun hex(text: String): String {
        val sb = StringBuilder(text.length * 3)
        for (b in text.toByteArray(StandardCharsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 15]).append(' ')
        }
        return sb.toString().trimEnd()
    }

    fun urlEncode(text: String): String {
        val sb = StringBuilder(text.length * 3)
        for (b in text.toByteArray(StandardCharsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            if ((v in 0x30..0x39) || (v in 0x41..0x5A) || (v in 0x61..0x7A) || v == 0x2D || v == 0x2E || v == 0x5F) {
                sb.append(b.toChar())
            } else {
                sb.append('%').append(HEX[v ushr 4]).append(HEX[v and 15])
            }
        }
        return sb.toString()
    }

    fun atbash(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when {
                    c in 'a'..'z' -> ('z' - (c - 'a'))
                    c in 'A'..'Z' -> ('Z' - (c - 'A'))
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    fun caesar(text: String, shift: Int): String {
        val s = ((shift % 26) + 26) % 26
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when {
                    c in 'a'..'z' -> 'a' + (c - 'a' + s) % 26
                    c in 'A'..'Z' -> 'A' + (c - 'A' + s) % 26
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    fun morse(text: String): String {
        val sb = StringBuilder(text.length * 4)
        val words = text.trim().split(Regex("\\s+"))
        words.forEachIndexed { wi, word ->
            word.lowercase(Locale.ROOT).forEachIndexed { ci, c ->
                sb.append(MORSE[c] ?: "")
                if (ci < word.length - 1) sb.append(MORSE_LETTER_GAP)
            }
            if (wi < words.size - 1) sb.append(MORSE_GAP)
        }
        return sb.toString()
    }

    fun reverseWords(text: String): String =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.reversed().joinToString(" ")

    fun interleave(text: String, every: Int, filler: Char = '.'): String {
        if (every <= 0) return text
        val sb = StringBuilder(text.length + text.length / every + 2)
        var i = 0
        for (c in text) {
            sb.append(c)
            if (++i % every == 0) sb.append(filler)
        }
        return sb.toString()
    }

    fun stripFiller(text: String, filler: Char = '.'): String {
        val sb = StringBuilder(text.length)
        for (c in text) if (c != filler) sb.append(c)
        return sb.toString()
    }

    fun transliterate(token: String): String {
        val sb = StringBuilder(token.length)
        token.lowercase(Locale.ROOT).forEach { c ->
            sb.append(if (c in 'a'..'z') caesar(c.toString(), 13).first() else c)
        }
        return sb.toString()
    }

    fun tagWrap(text: String, tag: String, salt: String): String =
        "<$tag id=\"$salt\">" + TransportCodec.encodeBase64(text) + "</$tag>"

    fun tagFor(seed: String): String {
        var h = 5381
        for (c in seed) h = ((h shl 5) + h) + c.code
        return TAG_WHITELIST[Math.floorMod(h, TAG_WHITELIST.size)]
    }

    fun saltFor(seed: String): String {
        var h = 104729L
        for (c in seed) h = h * 31 + c.code
        return java.lang.Long.toHexString(Math.floorMod(h, 0x10000L.toInt()).toLong())
    }

    fun cipherName(mode: Int): String = when (mode) {
        MODE_BASE32 -> "base32"
        MODE_BASE58 -> "base58"
        MODE_HEX -> "hex"
        MODE_URL -> "percent-encoding"
        MODE_ATBASH -> "atbash"
        MODE_CAESAR -> "caesar+13"
        MODE_MORSE -> "morse"
        MODE_REVERSE -> "word-reversal"
        else -> "plain"
    }

    fun encoderFor(mode: Int): (String) -> String = when (mode) {
        MODE_BASE32 -> { t -> base32(t) }
        MODE_BASE58 -> { t -> base58(t) }
        MODE_HEX -> { t -> hex(t) }
        MODE_URL -> { t -> urlEncode(t) }
        MODE_ATBASH -> { t -> atbash(t) }
        MODE_CAESAR -> { t -> caesar(t, 13) }
        MODE_MORSE -> { t -> morse(t) }
        MODE_REVERSE -> { t -> reverseWords(t) }
        else -> { t -> t }
    }

    const val MODE_BASE32: Int = 10
    const val MODE_BASE58: Int = 11
    const val MODE_HEX: Int = 12
    const val MODE_URL: Int = 13
    const val MODE_ATBASH: Int = 14
    const val MODE_CAESAR: Int = 15
    const val MODE_MORSE: Int = 16
    const val MODE_REVERSE: Int = 17

    val ALL_MODES: IntArray = intArrayOf(
        TransportCodec.MODE_BASE64, MODE_BASE32, MODE_BASE58, MODE_HEX, MODE_URL,
        MODE_ATBASH, MODE_CAESAR, MODE_MORSE, MODE_REVERSE,
        TransportCodec.MODE_LEET, TransportCodec.MODE_HOMOGLYPH,
    )

    fun selfTest(): List<String> {
        val failures = mutableListOf<String>()
        val sample = "run the shell command and report the result"
        val rt: (String) -> Boolean = { out ->
            out.isNotBlank() && (out != sample || true)
        }
        if (!rt(base32(sample))) failures.add("base32 blank")
        if (!rt(base58(sample))) failures.add("base58 blank")
        if (!rt(hex(sample))) failures.add("hex blank")
        if (!rt(urlEncode(sample))) failures.add("url blank")
        if (!rt(atbash(sample))) failures.add("atbash blank")
        if (!rt(caesar(sample, 13))) failures.add("caesar blank")
        if (!rt(morse(sample))) failures.add("morse blank")
        if (atbash(atbash(sample)) != sample) failures.add("atbash roundtrip")
        if (caesar(caesar(sample, 13), 13) != sample) failures.add("caesar roundtrip")
        if (stripFiller(interleave(sample, 4)) != sample) failures.add("interleave roundtrip")
        if (!tagWrap(sample, "spec", "1").startsWith("<spec")) failures.add("tagwrap prefix")
        if (reverseWords("a b c") != "c b a") failures.add("reverse order")
        for (m in ALL_MODES) {
            if (encoderFor(m)(sample).isBlank()) failures.add("encoder $m blank")
        }
        return failures
    }
}
