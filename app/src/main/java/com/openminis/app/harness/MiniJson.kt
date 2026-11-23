package com.openminis.app.harness

/**
 * v2.0 · A tiny, dependency-free JSON codec for the agent harness.
 *
 * Why reimplement JSON? The harness must compile and run on a plain JVM
 * (CI, unit tests, deterministic replay) with NOTHING but the Kotlin stdlib —
 * no org.json, no Android, no kotlinx-serialization, no coroutines. That makes
 * the core event log portable into a desktop replay tool, a server-side
 * evaluator or a τ-bench style simulator without dragging an Android runtime
 * along. It supports exactly the subset the harness emits: objects, arrays,
 * strings (with escapes), numbers, booleans and null.
 *
 * The parser is strict on structure and deliberately forgiving on trailing
 * whitespace. Any malformed input throws — callers decide whether to reject a
 * checkpoint (see EventSourcedRun.restore) rather than silently corrupting it.
 */
object MiniJson {

    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c.code < 0x20) {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else append(c)
            }
        }
        append('"')
    }

    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
            quote(k.toString()) + ":" + stringify(v)
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        is Enum<*> -> quote(value.name)
        else -> quote(value.toString())
    }

    fun obj(vararg pairs: Pair<String, Any?>): String = stringify(pairs.toMap())

    /** Parse into Map<String, Any?>, List<Any?>, String, Long/Double, Boolean or null. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("trailing JSON at ${p.pos}")
        return v
    }

    fun parseObject(text: String): Map<String, Any?>? {
        val v = parse(text) ?: return null
        if (v !is Map<*, *>) return null
        return v.entries.associate { it.key.toString() to it.value }
    }

    fun str(map: Map<String, Any?>?, key: String, default: String = ""): String =
        map?.get(key) as? String ?: default

    fun long(map: Map<String, Any?>?, key: String, default: Long = 0L): Long =
        when (val v = map?.get(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: default
            else -> default
        }

    fun int(map: Map<String, Any?>?, key: String, default: Int = 0): Int =
        long(map, key, default.toLong()).toInt()

    fun bool(map: Map<String, Any?>?, key: String, default: Boolean = false): Boolean =
        map?.get(key) as? Boolean ?: default

    private class Parser(private val s: String) {
        var pos = 0
            private set

        fun atEnd(): Boolean = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("unexpected end of JSON")
            return when (s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("bad literal at $pos")
            pos += word.length
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw IllegalArgumentException("expected key at $pos")
                val k = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw IllegalArgumentException("expected ':' at $pos")
                pos++
                out[k] = readValue()
                skipWs()
                if (atEnd()) throw IllegalArgumentException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                if (atEnd()) throw IllegalArgumentException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw IllegalArgumentException("dangling escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalArgumentException("bad \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    c.code < 0x20 -> throw IllegalArgumentException("control char in string")
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun readNumber(): Number {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "-+.eE")) pos++
            val raw = s.substring(start, pos)
            if (raw.isEmpty()) throw IllegalArgumentException("bad number at $start")
            return if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
                raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $raw")
            } else {
                raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $raw")
            }
        }
    }
}
