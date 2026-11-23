package com.openminis.app.network

import com.openminis.app.logging.AppLogger

object NetworkShellEnvironment {

    private const val TAG = "Network"
    const val NO_PROXY = ShellProxyVariables.NO_PROXY

    @Volatile
    private var lastBroadcast: Map<String, String>? = null

    fun watchRuntime(onChange: (cleared: Set<String>) -> Unit) {
        NetworkRuntime.addListener {
            changedSinceLastBroadcast(proxyVariables())?.let(onChange)
        }
    }

    @Synchronized
    internal fun changedSinceLastBroadcast(vars: Map<String, String>): Set<String>? {
        val before = lastBroadcast
        lastBroadcast = vars
        if (before == null || before == vars) return null
        return before.keys - vars.keys
    }

    @Synchronized
    internal fun resetForTest() {
        lastBroadcast = null
        lastLogged = null
    }

    @Volatile
    private var lastLogged: String? = null

    fun proxyVariables(): Map<String, String> {
        val snap = NetworkRuntime.snapshot
        val tag = NetworkScope.SHELL.tag
        val ep = snap.endpoint(tag)
        val route = snap.route(tag)
        val shellVars = ShellProxyVariables.make(ep?.url, ep?.httpUrl, routeIsDirect = route == NetworkRoute.Direct)
        val mcpBlocked = if (snap.entryDown) snap.endpoint(NetworkScope.MCP.tag) else null
        val vars = shellVars + if (mcpBlocked != null) {
            ShellProxyVariables.mcp(mcpBlocked.port, mcpBlocked.httpPort, mcpBlocked.password)
        } else {
            ShellProxyVariables.mcp(snap.port, snap.httpPort, snap.password)
        }
        val state = when {
            shellVars.isEmpty() -> "none (route=${route.raw}; shells connect directly)"
            snap.entryDown -> "BLOCKED (entry down; proxy variables point at a closed port) route=${route.raw}"
            ep?.httpPort != null -> "ALL_PROXY -> socks5h://shell:<password>@127.0.0.1:${ep.port}, " +
                "HTTP(S)_PROXY -> http://shell:<password>@127.0.0.1:${ep.httpPort}, NO_PROXY=$NO_PROXY route=${route.raw}"
            else -> "ALL_PROXY -> socks5h://shell:<password>@127.0.0.1:${ep?.port}, NO_PROXY=$NO_PROXY; " +
                "HTTP(S)_PROXY NOT set (the http front is not listening, so wget / apk / pip connect directly) route=${route.raw}"
        }
        val mcpState = when {
            ShellProxyVariables.MCP_PROXY_KEY !in vars -> "MINIS_MCP_PROXY none (entry off: minis-mcp-cli as before)"
            mcpBlocked != null -> "MINIS_MCP_PROXY -> BLOCKED (entry down) route=${snap.route(NetworkScope.MCP.tag).raw}"
            else -> "MINIS_MCP_PROXY -> http://mcp:<password>@127.0.0.1:${snap.httpPort} route=${snap.route(NetworkScope.MCP.tag).raw}"
        }
        if ("$state; $mcpState" != lastLogged) {
            lastLogged = "$state; $mcpState"
            AppLogger.info(TAG, "[Net][Shell] new shells: $state; $mcpState gen=${snap.generation}")
        }
        return vars
    }
}

object ShellProxyVariables {
    const val NO_PROXY = "localhost,127.0.0.1,::1"

    fun make(socksUrl: String?, httpUrl: String?, routeIsDirect: Boolean): Map<String, String> {
        if (routeIsDirect || socksUrl.isNullOrEmpty()) return emptyMap()
        val vars = linkedMapOf(
            "ALL_PROXY" to socksUrl, "all_proxy" to socksUrl,
            "NO_PROXY" to NO_PROXY, "no_proxy" to NO_PROXY,
        )
        if (!httpUrl.isNullOrEmpty()) {
            for (k in listOf("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")) vars[k] = httpUrl
        }
        return vars
    }

    const val MCP_PROXY_KEY = "MINIS_MCP_PROXY"

    fun mcp(port: Int?, httpPort: Int?, password: String): Map<String, String> {
        if (port == null || httpPort == null || password.isEmpty()) return emptyMap()
        val url = SocksEndpoint(port, NetworkScope.MCP.tag, password, httpPort).httpUrl ?: return emptyMap()
        return mapOf(MCP_PROXY_KEY to url)
    }
}
