package com.openminis.app.sandbox

import android.content.Context
import android.content.SharedPreferences

object ContainerDns {

    const val PREFS = "container_network"
    const val KEY_MODE = "network.dnsMode"
    const val KEY_SERVERS = "network.dnsCustomServers"

    enum class DnsMode(val wire: String) {
        AUTO("auto"),
        CUSTOM("custom"),
        ;

        companion object {
            fun fromWire(raw: String?): DnsMode = values().firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: AUTO
        }
    }

    const val RESOLVER_MAX_SERVERS = 3

    data class ParseResult(
        val servers: List<String>,
        val invalid: List<String>,
    ) {
        val isValid: Boolean get() = invalid.isEmpty()
    }

    fun parse(raw: String): ParseResult {
        val servers = LinkedHashSet<String>()
        val invalid = mutableListOf<String>()
        for (token in raw.split(Regex("""[\s,;]+"""))) {
            val t = token.trim()
            if (t.isEmpty() || t.equals("nameserver", ignoreCase = true)) continue
            val addr = t.removePrefix("[").removeSuffix("]")
            if (isValidServer(addr)) servers += addr else invalid += t
        }
        return ParseResult(servers.toList(), invalid)
    }

    fun isValidServer(s: String): Boolean = isValidIpv4(s) || isValidIpv6(s)

    fun isValidIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
                (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }

    fun isValidIpv6(s: String): Boolean {
        if (s.isEmpty()) return false
        val pct = s.indexOf('%')
        val addr = if (pct >= 0) {
            val zone = s.substring(pct + 1)
            if (zone.isEmpty() || !zone.all { it.isLetterOrDigit() || it in "._-" }) return false
            s.substring(0, pct)
        } else {
            s
        }
        if (!addr.contains(':')) return false
        val doubleColons = Regex("::").findAll(addr).count()
        if (doubleColons > 1 || addr.contains(":::")) return false
        val compressed = doubleColons == 1
        if (addr.startsWith(":") && !addr.startsWith("::")) return false
        if (addr.endsWith(":") && !addr.endsWith("::")) return false

        val halves = if (compressed) addr.split("::") else listOf(addr)
        val groups = halves.flatMap { h -> if (h.isEmpty()) emptyList() else h.split(':') }
        var count = 0
        for ((i, g) in groups.withIndex()) {
            val last = i == groups.lastIndex
            if (last && g.contains('.')) {
                if (!isValidIpv4(g)) return false
                count += 2
                continue
            }
            if (g.isEmpty() || g.length > 4 || !g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
            count += 1
        }
        return if (compressed) count <= 7 else count == 8
    }

    sealed class Plan {
        object System : Plan()

        data class Custom(val servers: List<String>) : Plan()
    }

    fun plan(mode: DnsMode, rawServers: String?): Plan {
        if (mode != DnsMode.CUSTOM) return Plan.System
        val servers = parse(rawServers.orEmpty()).servers
        return if (servers.isEmpty()) Plan.System else Plan.Custom(servers)
    }

    fun customResolvConf(servers: List<String>): String = buildString {
        append("# Written by DeepSeek: Container Network DNS is set to Custom (Settings > Rootfs).\n")
        for (s in servers) append("nameserver ").append(s).append('\n')
    }


    interface Store {
        fun mode(): DnsMode
        fun rawServers(): String
        fun save(mode: DnsMode, rawServers: String)
    }

    class PrefsStore(private val context: Context) : Store {
        override fun mode(): DnsMode = mode(context)
        override fun rawServers(): String = rawServers(context)
        override fun save(mode: DnsMode, rawServers: String) = save(context, mode, rawServers)
    }

    fun normalize(raw: String): String = parse(raw).servers.joinToString("\n")

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): DnsMode = DnsMode.fromWire(prefs(context).getString(KEY_MODE, null))

    fun rawServers(context: Context): String = prefs(context).getString(KEY_SERVERS, null).orEmpty()

    fun plan(context: Context): Plan = plan(mode(context), rawServers(context))

    fun save(context: Context, mode: DnsMode, rawServers: String) {
        val parsed = parse(rawServers)
        require(parsed.isValid) { "Invalid DNS server(s): ${parsed.invalid.joinToString(", ")}" }
        prefs(context).edit()
            .putString(KEY_MODE, mode.wire)
            .putString(KEY_SERVERS, normalize(rawServers))
            .apply()
    }
}
