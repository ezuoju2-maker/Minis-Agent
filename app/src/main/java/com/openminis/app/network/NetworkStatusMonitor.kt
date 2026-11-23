package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object NetworkStatusMonitor {

    private const val TAG = "Network"
    const val PROBE_INTERVAL_MS = 180_000L
    const val TICK_MS = 10_000L

    enum class ProxyHealth { CHECKING, AVAILABLE, UNAVAILABLE }

    private val _proxyHealth = MutableStateFlow<Map<String, ProxyHealth>>(emptyMap())
    val proxyHealth: StateFlow<Map<String, ProxyHealth>> = _proxyHealth.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probedAt = HashMap<String, Pair<ProxyServer, Long>>()
    private val inFlight = HashSet<String>()
    private var tickJob: Job? = null
    @Volatile
    private var inFront = false

    fun proxiesInUse(s: NetworkSettings): List<ProxyServer> {
        if (s.forceDirect) return emptyList()
        val routes = (listOf(s.defaultRoute) + s.scopes.values + s.providerRoutes.values).toSet()
        return s.proxies.filter { "proxy:${it.id}" in routes }
    }

    fun wanted(s: NetworkSettings): Boolean = s.nodeWanted || proxiesInUse(s).isNotEmpty()

    fun start(settings: StateFlow<NetworkSettings>) {
        scope.launch { settings.collect { settingsChanged(it) } }
    }

    @Synchronized
    fun onForeground() {
        inFront = true
        val s = NetworkHub.settings.value
        probeStale(s)
        updateTicker(s)
    }

    @Synchronized
    fun onBackground() {
        inFront = false
        tickJob?.cancel()
        tickJob = null
    }

    @Synchronized
    private fun settingsChanged(s: NetworkSettings) {
        val ids = proxiesInUse(s).map { it.id }.toSet()
        _proxyHealth.update { m -> m.filterKeys { it in ids } }
        probedAt.keys.retainAll(ids)
        if (!inFront) return
        probeStale(s)
        updateTicker(s)
    }

    private fun updateTicker(s: NetworkSettings) {
        if (!wanted(s)) {
            tickJob?.cancel()
            tickJob = null
            return
        }
        if (tickJob != null) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    @Synchronized
    private fun tick() {
        val s = NetworkHub.settings.value
        val ts = NetworkHub.tailscale.value
        if (s.nodeWanted && !ts.isLoggedIn && !ts.needsLogin) NetworkHub.refreshStatus()
        probeStale(s)
        updateTicker(s)
    }

    private fun probeStale(s: NetworkSettings) {
        val now = System.currentTimeMillis()
        for (proxy in proxiesInUse(s)) {
            if (proxy.id in inFlight) continue
            val last = probedAt[proxy.id]
            if (last != null && last.first == proxy && now - last.second < PROBE_INTERVAL_MS) continue
            probe(proxy, now)
        }
    }

    private fun probe(proxy: ProxyServer, now: Long) {
        inFlight += proxy.id
        probedAt[proxy.id] = proxy to now
        if (proxy.id !in _proxyHealth.value) _proxyHealth.update { it + (proxy.id to ProxyHealth.CHECKING) }
        scope.launch {
            val health = runCatching { NetworkHub.testProxy(proxy, NetworkHub.proxyPassword(proxy.id)) }.fold(
                { ms ->
                    AppLogger.info(TAG, "[Net][Badge] proxy ${proxy.id.take(8)} answers ($ms ms)")
                    ProxyHealth.AVAILABLE
                },
                { e ->
                    AppLogger.warning(TAG, "[Net][Badge] proxy ${proxy.id.take(8)} probe failed: " +
                        NetworkDiagnostics.classifyText(e.message.orEmpty()))
                    ProxyHealth.UNAVAILABLE
                },
            )
            synchronized(this@NetworkStatusMonitor) {
                inFlight -= proxy.id
                if (proxy in proxiesInUse(NetworkHub.settings.value)) _proxyHealth.update { it + (proxy.id to health) }
            }
        }
    }

    enum class ProxyLevel { AVAILABLE, CHECKING, UNAVAILABLE }

    fun proxyLevel(id: String, health: Map<String, ProxyHealth>, failing: Set<NetworkAlertEvent.Subject>): ProxyLevel = when {
        NetworkAlertEvent.Subject.Proxy(id) in failing -> ProxyLevel.UNAVAILABLE
        health[id] == ProxyHealth.AVAILABLE -> ProxyLevel.AVAILABLE
        health[id] == ProxyHealth.UNAVAILABLE -> ProxyLevel.UNAVAILABLE
        else -> ProxyLevel.CHECKING
    }

    enum class TailscaleLevel { CONNECTING, CONNECTED, UNAVAILABLE, NOT_LOGGED_IN, AWAITING_APPROVAL, NOT_CONNECTED }

    fun tailscaleLevel(ts: TailscaleStatus, applying: Boolean, lastError: String?, failing: Boolean): TailscaleLevel = when {
        applying && !ts.running -> TailscaleLevel.CONNECTING
        ts.isLoggedIn -> if (failing) TailscaleLevel.UNAVAILABLE else TailscaleLevel.CONNECTED
        ts.needsLogin -> TailscaleLevel.NOT_LOGGED_IN
        ts.backendState == "NeedsMachineAuth" -> TailscaleLevel.AWAITING_APPROVAL
        ts.running -> TailscaleLevel.CONNECTING
        lastError == null -> TailscaleLevel.CONNECTING
        else -> TailscaleLevel.NOT_CONNECTED
    }
}
