package com.openminis.app.sandbox.offload

internal class OffloadArgs(argv: List<String>, booleanFlags: Set<String> = emptySet()) {
    val positional: List<String>
    private val flags: Set<String>
    private val values: Map<String, String>

    init {
        val effectiveBooleanFlags = booleanFlags + OffloadOutput.OUTPUT_FLAGS
        val pos = mutableListOf<String>()
        val fs = mutableSetOf<String>()
        val vs = mutableMapOf<String, String>()
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            when {
                a.startsWith("--") && a.contains('=') -> {
                    val eq = a.indexOf('=')
                    vs[a.substring(2, eq)] = a.substring(eq + 1)
                }
                a.startsWith("--") -> {
                    val key = a.substring(2)
                    val next = argv.getOrNull(i + 1)
                    if (key in effectiveBooleanFlags || next == null || next.startsWith("-")) {
                        fs.add(key)
                    } else {
                        vs[key] = next
                        i++
                    }
                }
                a.startsWith("-") && a.length > 1 -> fs.add(a.substring(1))
                else -> pos.add(a)
            }
            i++
        }
        positional = pos
        flags = fs
        values = vs
    }

    fun hasFlag(vararg names: String): Boolean = names.any { it in flags }
    fun get(vararg names: String): String? = names.firstNotNullOfOrNull { values[it] }
    fun getInt(vararg names: String): Int? = get(*names)?.toIntOrNull()
    fun getLong(vararg names: String): Long? = get(*names)?.toLongOrNull()
    fun getDouble(vararg names: String): Double? = get(*names)?.toDoubleOrNull()
    fun getBool(vararg names: String): Boolean? =
        get(*names)?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }
}
