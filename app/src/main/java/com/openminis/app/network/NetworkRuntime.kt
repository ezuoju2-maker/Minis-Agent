package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import java.net.URLEncoder

data class SocksEndpoint(
    val port: Int,
    val tag: String,
    val password: String,
    val httpPort: Int? = null,
) {
    val host: String get() = "127.0.0.1"

    val url: String
        get() = "socks5h://${enc(tag)}:${enc(password)}@$host:$port"

    val httpUrl: String?
        get() = httpPort?.let { "http://${enc(tag)}:${enc(password)}@$host:$it" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    override fun toString(): String = "SocksEndpoint(127.0.0.1:$port, tag=$tag)"
}

object NetworkRuntime {

    private const val TAG = "Network"

    data class Snapshot(
        val port: Int? = null,
        val httpPort: Int? = null,
        val password: String = "",
        val tailscaleEnabled: Boolean = false,
        val defaultRoute: String = "direct",
        val tagRoutes: Map<String, String> = emptyMap(),
        val generation: Int = 0,
        val entryDown: Boolean = false,
    ) {
        fun route(tag: String): NetworkRoute {
            val r = NetworkRoute.parse(tagRoutes[tag] ?: "default")
            return if (r == NetworkRoute.FollowDefault) NetworkRoute.parse(defaultRoute).let {
                if (it == NetworkRoute.FollowDefault) NetworkRoute.Direct else it
            } else r
        }

        fun endpoint(tag: String): SocksEndpoint? {
            if (entryDown) {
                return if (route(tag) == NetworkRoute.Direct) null
                else SocksEndpoint(BLOCKED_PORT, tag, BLOCKED_PASSWORD, BLOCKED_PORT)
            }
            val p = port ?: return null
            if (password.isEmpty()) return null
            if (route(tag) == NetworkRoute.Direct && !tailscaleEnabled) return null
            return SocksEndpoint(p, tag, password, httpPort)
        }

        override fun toString(): String =
            "Snapshot(gen=$generation, entry=${if (entryDown) "DOWN" else port ?: "off"}, http=${httpPort ?: "off"}, tailscale=$tailscaleEnabled, default=$defaultRoute, tags=${tagRoutes.size})"
    }

    const val BLOCKED_PORT = 1
    internal const val BLOCKED_PASSWORD = "entry-down"

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = mutableListOf<(Snapshot) -> Unit>()

    fun providerTag(instanceId: String) = "prov:$instanceId"

    fun endpoint(tag: String): SocksEndpoint? = snapshot.endpoint(tag)

    @Synchronized
    fun publish(next: Snapshot) {
        val cur = snapshot
        val same = next.copy(generation = cur.generation) == cur
        if (same) return
        val n = next.copy(generation = cur.generation + 1)
        snapshot = n
        AppLogger.info(TAG, "[Net][Session] runtime gen=${n.generation} entry=${if (n.entryDown) "DOWN" else n.port ?: "off"} http=${n.httpPort ?: "off"} tailscale=${n.tailscaleEnabled} default=${n.defaultRoute} tags=${n.tagRoutes.size}")
        listeners.toList().forEach { runCatching { it(n) } }
    }

    @Synchronized
    fun addListener(l: (Snapshot) -> Unit) {
        listeners += l
    }

    @Synchronized
    internal fun resetForTest() {
        snapshot = Snapshot()
        listeners.clear()
    }
}
