package com.openminis.app.network

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class NetworkRoute {
    object FollowDefault : NetworkRoute()
    object Direct : NetworkRoute()
    object Tailscale : NetworkRoute()
    data class Proxy(val id: String) : NetworkRoute()

    val raw: String
        get() = when (this) {
            FollowDefault -> "default"
            Direct -> "direct"
            Tailscale -> "tailscale"
            is Proxy -> "proxy:$id"
        }

    companion object {
        fun parse(raw: String?): NetworkRoute = when {
            raw == "direct" -> Direct
            raw == "tailscale" -> Tailscale
            raw != null && raw.startsWith("proxy:") -> Proxy(raw.removePrefix("proxy:"))
            else -> FollowDefault
        }
    }
}

@Serializable
data class ProxyServer(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val name: String = "",
    val kind: String = "socks5",
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val bypass: List<String> = emptyList(),
) {
    val displayName: String get() = name.ifEmpty { "$host:$port" }

    companion object {
        val KINDS = listOf("socks5", "http", "https")

        fun kindLabel(kind: String) = when (kind) {
            "http" -> "HTTP"
            "https" -> "HTTPS"
            else -> "SOCKS5"
        }

        fun defaultPort(kind: String) = when (kind) {
            "http" -> 8080
            "https" -> 443
            else -> 1080
        }
    }
}

enum class NetworkScope(val tag: String) {
    SHELL("shell"), BROWSER("browser"), MCP("mcp"), BACKUP("backup");
}

@Serializable
data class NetworkSettings(
    val tailscaleEnabled: Boolean = false,
    val hostname: String = "",
    val acceptRoutes: Boolean = false,
    val exitNodeIP: String? = null,
    val defaultRoute: String = "direct",
    val scopes: Map<String, String> = emptyMap(),
    val proxies: List<ProxyServer> = emptyList(),
    val providerRoutes: Map<String, String> = emptyMap(),
    val forceDirect: Boolean = false,
    val exitNodeFailClosed: Boolean = false,
    val exitNodeDNS: Boolean = false,
) {
    fun route(scope: NetworkScope): NetworkRoute = NetworkRoute.parse(scopes[scope.tag] ?: "default")

    fun providerRoute(instanceId: String): NetworkRoute = NetworkRoute.parse(providerRoutes[instanceId] ?: "default")

    val tagRoutes: Map<String, String>
        get() = buildMap {
            for (s in NetworkScope.entries) put(s.tag, route(s).raw)
            for ((id, r) in providerRoutes) put(NetworkRuntime.providerTag(id), r)
        }

    val needsEntry: Boolean
        get() {
            if (forceDirect) return false
            if (tailscaleEnabled) return true
            if (NetworkRoute.parse(defaultRoute) != NetworkRoute.Direct) return true
            return (scopes.values + providerRoutes.values).any {
                val r = NetworkRoute.parse(it)
                r != NetworkRoute.Direct && r != NetworkRoute.FollowDefault
            }
        }

    val nodeWanted: Boolean get() = tailscaleEnabled && !forceDirect

    fun withoutProxy(id: String): NetworkSettings {
        val r = "proxy:$id"
        return copy(
            proxies = proxies.filterNot { it.id == id },
            defaultRoute = if (defaultRoute == r) "direct" else defaultRoute,
            scopes = scopes.mapValues { (_, v) -> if (v == r) "default" else v },
            providerRoutes = providerRoutes.filterValues { it != r },
        )
    }

    fun stopUsing(id: String): NetworkSettings {
        val r = "proxy:$id"
        return copy(
            defaultRoute = if (defaultRoute == r) "direct" else defaultRoute,
            scopes = scopes.mapValues { (_, v) -> if (v == r) "default" else v },
            providerRoutes = providerRoutes.filterValues { it != r },
        )
    }

    val isUnconfigured: Boolean get() = this == NetworkSettings()
}

object NetworkSettingsStore {
    private const val PREFS = "minis_network"
    const val KEY = "network.settings.v1"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun decode(raw: String?): NetworkSettings =
        raw?.let { runCatching { json.decodeFromString<NetworkSettings>(it) }.getOrNull() } ?: NetworkSettings()

    fun encode(s: NetworkSettings): String = json.encodeToString(s)

    fun load(context: Context): NetworkSettings =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, s: NetworkSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(s)).apply()
    }
}

object NetworkSecrets {
    private const val FILE = "minis_network_secrets"

    private fun prefs(context: Context) = EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)

    fun password(context: Context, proxyId: String): String =
        runCatching { prefs(context).getString("proxy.$proxyId", null) }.getOrNull().orEmpty()

    fun setPassword(context: Context, proxyId: String, password: String) {
        runCatching {
            val e = prefs(context).edit()
            if (password.isEmpty()) e.remove("proxy.$proxyId") else e.putString("proxy.$proxyId", password)
            e.apply()
        }.onFailure { AppLogger.error("Network", "[Net][Proxy] could not store the password for a proxy: ${it.javaClass.simpleName}") }
    }

    fun remove(context: Context, proxyId: String) = setPassword(context, proxyId, "")
}
