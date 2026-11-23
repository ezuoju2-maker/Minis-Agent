package com.openminis.app.config.readable

import com.openminis.app.config.ConfigValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.NumberFormat
import java.util.Locale



class ConfigReadable private constructor(
    val fieldLabel: String?,
    val formatter: ConfigValueFormatter,
    val genericReason: String?,
) {
    constructor(formatter: ConfigValueFormatter, label: String? = null) : this(label, formatter, null)

    val isGeneric: Boolean get() = genericReason != null

    companion object {
        fun generic(reason: String): ConfigReadable = ConfigReadable(null, ConfigValueFormatter.Generic, reason)

        val notWritable: ConfigReadable = generic("not writable through minis-config")
    }
}

sealed class ConfigRefKind {
    object ModelEntry : ConfigRefKind()
    object Group : ConfigRefKind()
    object ProviderInstance : ConfigRefKind()
    data class ThinkingRule(val instanceId: String) : ConfigRefKind()
    object ModelSource : ConfigRefKind()
    object SubAgent : ConfigRefKind()
}

enum class NumberUnit { PLAIN, TOKENS, SECONDS, BYTES, POINTS }

sealed class ConfigValueFormatter {
    object Generic : ConfigValueFormatter()
    data class Bool(val on: String = "On", val off: String = "Off") : ConfigValueFormatter()
    data class EnumLabels(val labels: Map<String, String>) : ConfigValueFormatter()
    data class Number(val unit: NumberUnit = NumberUnit.PLAIN, val zero: String? = null) : ConfigValueFormatter()
    data class Text(val preview: Int = 40, val empty: String = "(empty)") : ConfigValueFormatter()
    object LongText : ConfigValueFormatter()
    data class Url(val empty: String = "Default") : ConfigValueFormatter()
    object Image : ConfigValueFormatter()
    object Secret : ConfigValueFormatter()
    data class Ref(val kind: ConfigRefKind, val empty: String = "Not set") : ConfigValueFormatter()
    data class RefList(val kind: ConfigRefKind, val ordered: Boolean) : ConfigValueFormatter()
    data class StringList(
        val ordered: Boolean,
        val labels: Map<String, String> = emptyMap(),
        val splitText: ((String) -> List<String>)? = null,
    ) : ConfigValueFormatter()
    class Custom(val make: (ConfigValue) -> ReadableArg) : ConfigValueFormatter()
    class Sentence(val make: (old: ConfigValue, new: ConfigValue) -> List<ReadableLine>?) : ConfigValueFormatter()
}


data class ReadableLine(val key: String, val args: List<ReadableArg> = emptyList())

sealed class ReadableArg {
    data class Text(val value: String) : ReadableArg()
    data class Key(val key: String) : ReadableArg()
    data class Line(val line: ReadableLine) : ReadableArg()
    data class Items(val items: List<ReadableArg>) : ReadableArg()
    data class Clauses(val items: List<ReadableArg>) : ReadableArg()
}

data class ReadableSummary(
    val v: Int = 1,
    val locale: String,
    val text: String,
    val lines: List<ReadableLine>,
    val subject: String?,
) {
    fun displayText(localize: (String) -> String?): String {
        if (lines.isEmpty()) return text
        return lines.joinToString("\n") { ReadableCore.render(it, localize) }
    }

    fun jsonString(): String = JSONObject().apply {
        put("v", v)
        put("locale", locale)
        put("text", text)
        put("lines", JSONArray().also { arr -> lines.forEach { arr.put(ReadableJson.encode(it)) } })
        if (subject != null) put("subject", subject)
    }.toString()

    companion object {
        fun decode(json: String?): ReadableSummary? {
            if (json.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("lines")
                ReadableSummary(
                    v = o.optInt("v", 1),
                    locale = o.optString("locale", ""),
                    text = o.getString("text"),
                    lines = (0 until arr.length()).map { ReadableJson.decodeLine(arr.getJSONObject(it)) },
                    subject = if (o.has("subject") && !o.isNull("subject")) o.getString("subject") else null,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

internal object ReadableJson {
    fun encode(line: ReadableLine): JSONObject = JSONObject().apply {
        put("key", line.key)
        put("args", JSONArray().also { arr -> line.args.forEach { arr.put(encode(it)) } })
    }

    private fun encode(arg: ReadableArg): JSONObject {
        val (case, payload) = when (arg) {
            is ReadableArg.Text -> "text" to arg.value
            is ReadableArg.Key -> "key" to arg.key
            is ReadableArg.Line -> "line" to encode(arg.line)
            is ReadableArg.Items -> "list" to JSONArray().also { a -> arg.items.forEach { a.put(encode(it)) } }
            is ReadableArg.Clauses -> "clauses" to JSONArray().also { a -> arg.items.forEach { a.put(encode(it)) } }
        }
        return JSONObject().put(case, JSONObject().put("_0", payload))
    }

    fun decodeLine(o: JSONObject): ReadableLine {
        val args = o.optJSONArray("args") ?: JSONArray()
        return ReadableLine(o.getString("key"), (0 until args.length()).map { decodeArg(args.getJSONObject(it)) })
    }

    private fun decodeArg(o: JSONObject): ReadableArg {
        val case = o.keys().next()
        val body = o.getJSONObject(case)
        return when (case) {
            "text" -> ReadableArg.Text(body.getString("_0"))
            "key" -> ReadableArg.Key(body.getString("_0"))
            "line" -> ReadableArg.Line(decodeLine(body.getJSONObject("_0")))
            "list" -> ReadableArg.Items(decodeArgs(body.getJSONArray("_0")))
            "clauses" -> ReadableArg.Clauses(decodeArgs(body.getJSONArray("_0")))
            else -> throw IllegalArgumentException("unknown ReadableArg case $case")
        }
    }

    private fun decodeArgs(a: JSONArray): List<ReadableArg> =
        (0 until a.length()).map { decodeArg(a.getJSONObject(it)) }
}


class RedactedConfigValue private constructor(val value: ConfigValue) {
    companion object {
        fun field(fieldLeaf: String, value: ConfigValue): RedactedConfigValue {
            var v = value
            if (fieldLeaf in ConfigValue.SECRET_KEYS) {
                val masked = (ConfigValue.Obj(mapOf(fieldLeaf to value)).redactingSecrets() as ConfigValue.Obj).value
                masked[fieldLeaf]?.let { v = it }
            }
            if (v is ConfigValue.Str && isInlineImage(v.value)) v = ConfigValue.Str("<image>")
            return RedactedConfigValue(v)
        }

        fun payload(payload: ConfigValue): RedactedConfigValue =
            RedactedConfigValue(maskImages(payload.redactingSecrets()))

        fun isInlineImage(s: String): Boolean = s.startsWith("data:image/")

        private fun maskImages(v: ConfigValue): ConfigValue = when (v) {
            is ConfigValue.Str -> if (isInlineImage(v.value)) ConfigValue.Str("<image>") else v
            is ConfigValue.Arr -> ConfigValue.Arr(v.value.map(::maskImages))
            is ConfigValue.Obj -> ConfigValue.Obj(v.value.mapValues { maskImages(it.value) })
            else -> v
        }
    }
}


object ReadableCore {
    const val MAX_LISTED_NAMES = 5

    const val listSeparatorKey = ", "
    const val clauseSeparatorKey = "; "

    data class ListItem(val id: String, val count: Int)

    data class ListDiff(
        val newFirst: String?,
        val added: List<ListItem>,
        val removed: List<ListItem>,
        val reordered: Boolean,
        val keptCount: Int,
    ) {
        val isEmpty: Boolean get() = newFirst == null && added.isEmpty() && removed.isEmpty() && !reordered
    }

    fun listDiff(old: List<String>, new: List<String>, ordered: Boolean): ListDiff {
        val oldCount = old.groupingBy { it }.eachCount()
        val newCount = new.groupingBy { it }.eachCount()

        val added = new.distinct().mapNotNull { id ->
            val d = (newCount[id] ?: 0) - (oldCount[id] ?: 0)
            if (d > 0) ListItem(id, d) else null
        }
        val removed = old.distinct().mapNotNull { id ->
            val d = (oldCount[id] ?: 0) - (newCount[id] ?: 0)
            if (d > 0) ListItem(id, d) else null
        }

        fun kept(list: List<String>): List<String> {
            val budget = HashMap<String, Int>()
            for ((id, n) in oldCount) budget[id] = minOf(n, newCount[id] ?: 0)
            val out = ArrayList<String>()
            for (id in list) {
                val b = budget[id] ?: 0
                if (b > 0) {
                    budget[id] = b - 1
                    out.add(id)
                }
            }
            return out
        }
        val keptOld = kept(old)
        val keptNew = kept(new)

        val first = new.firstOrNull()
        val newFirst = if (ordered && first != null && first != old.firstOrNull()) first else null
        return ListDiff(newFirst, added, removed, reordered = keptOld != keptNew, keptCount = keptNew.size)
    }

    private class Placeholder(val start: Int, val end: Int, val position: Int?)

    private fun placeholders(format: String): List<Placeholder>? {
        val out = ArrayList<Placeholder>()
        var sequential = false
        var positional = false
        var i = 0
        while (i < format.length) {
            if (format[i] != '%') { i++; continue }
            val start = i
            i++
            if (i >= format.length) return null
            if (format[i] == '%') { i++; continue }
            if (format[i] == '@' || format[i] == 's') {
                sequential = true
                out.add(Placeholder(start, i + 1, null))
                i++
                continue
            }
            val digitsStart = i
            while (i < format.length && format[i].isDigit()) i++
            if (i == digitsStart || i + 1 >= format.length || format[i] != '$' ||
                (format[i + 1] != '@' && format[i + 1] != 's')
            ) return null
            val n = format.substring(digitsStart, i).toIntOrNull() ?: return null
            if (n < 1) return null
            positional = true
            out.add(Placeholder(start, i + 2, n))
            i += 2
        }
        if (sequential && positional) return null
        return out
    }

    fun placeholderCount(format: String): Int {
        val ps = placeholders(format) ?: return -1
        if (ps.isEmpty()) return 0
        return if (ps.first().position == null) ps.size else ps.maxOf { it.position!! }
    }

    private fun substitute(format: String, args: List<String>): String {
        val ps = placeholders(format) ?: return format
        val sb = StringBuilder()
        var last = 0
        var seq = 0
        var i = 0
        while (i < format.length) {
            val p = ps.firstOrNull { it.start == i }
            if (p != null) {
                sb.append(format, last, p.start)
                val idx = p.position?.minus(1) ?: seq++
                sb.append(args.getOrElse(idx) { "" })
                i = p.end
                last = i
                continue
            }
            if (format[i] == '%' && i + 1 < format.length && format[i + 1] == '%') {
                sb.append(format, last, i).append('%')
                i += 2
                last = i
                continue
            }
            i++
        }
        sb.append(format, last, format.length)
        return sb.toString()
    }

    fun render(line: ReadableLine, localize: (String) -> String?): String {
        val args = line.args.map { render(it, localize) }
        var format = localize(line.key) ?: line.key
        if (placeholderCount(format) != args.size) format = line.key
        if (placeholderCount(format) != args.size) return (listOf(line.key) + args).joinToString(" ")
        return substitute(format, args)
    }

    fun render(arg: ReadableArg, localize: (String) -> String?): String = when (arg) {
        is ReadableArg.Text -> arg.value
        is ReadableArg.Key -> localize(arg.key) ?: arg.key
        is ReadableArg.Line -> render(arg.line, localize)
        is ReadableArg.Items -> arg.items.joinToString(localize(listSeparatorKey) ?: listSeparatorKey) {
            render(it, localize)
        }
        is ReadableArg.Clauses -> arg.items.joinToString(localize(clauseSeparatorKey) ?: clauseSeparatorKey) {
            render(it, localize)
        }
    }

    fun quoted(s: String, preview: Int): String {
        val flat = s.replace("\n", " ")
        val short = if (flat.codePointCount(0, flat.length) > preview) {
            flat.substring(0, flat.offsetByCodePoints(0, preview)) + "…"
        } else {
            flat
        }
        return "“$short”"
    }

    fun lineDelta(old: String, new: String): Pair<Int, Int> {
        val o = if (old.isEmpty()) emptyList() else old.split("\n")
        val n = if (new.isEmpty()) emptyList() else new.split("\n")
        val d = listDiff(o, n, ordered = false)
        return d.added.sumOf { it.count } to d.removed.sumOf { it.count }
    }

    fun safeURL(s: String): String {
        val uri = try { URI(s) } catch (_: Exception) { null }
        if (uri == null || uri.scheme == null || uri.isOpaque) {
            return s.substringBefore('?').substringBefore('#')
        }
        return try {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        } catch (_: Exception) {
            s.substringBefore('?').substringBefore('#')
        }
    }

    fun compactCount(n: Int): String {
        if (n >= 1_000_000 && n % 1_000_000 == 0) return "${n / 1_000_000}M"
        if (n >= 1_048_576 && n % 1_048_576 == 0) return "${n / 1_048_576}M"
        if (n >= 1_000 && n % 1_000 == 0) return "${n / 1_000}K"
        if (n >= 1_024 && n % 1_024 == 0) return "${n / 1_024}K"
        return NumberFormat.getIntegerInstance(Locale.US).format(n)
    }

    fun plainNumber(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        val f = NumberFormat.getNumberInstance(Locale.US)
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 2
        f.minimumIntegerDigits = 1
        f.isGroupingUsed = false
        return f.format(d)
    }
}
