package com.openminis.app.network

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class TailscalePeer(
    val hostName: String,
    val dnsName: String,
    val os: String,
    val ips: List<String>,
    val online: Boolean,
    val exitNodeOption: Boolean,
    val exitNode: Boolean,
) {
    val displayName: String get() = dnsName.substringBefore('.').ifEmpty { hostName }
    val ipv4: String? get() = ips.firstOrNull { !it.contains(':') } ?: ips.firstOrNull()
}

data class TailscaleStatus(
    val running: Boolean = false,
    val backendState: String = "",
    val authURL: String? = null,
    val ips: List<String> = emptyList(),
    val dnsName: String = "",
    val tailnet: String = "",
    val peers: List<TailscalePeer> = emptyList(),
    val error: String? = null,
    val health: List<String> = emptyList(),
) {
    val isLoggedIn: Boolean get() = backendState == "Running"
    val needsLogin: Boolean get() = backendState == "NeedsLogin"

    val loginError: String?
        get() = health.firstOrNull { it.contains("last login error", ignoreCase = true) }

    val tailnetUsable: Boolean
        get() = running && backendState !in setOf("NeedsLogin", "NeedsMachineAuth", "Stopped")

    companion object {
        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }

        fun parse(j: JSONObject): TailscaleStatus = TailscaleStatus(
            running = j.optBoolean("running"),
            backendState = j.optString("backendState"),
            authURL = j.optString("authURL").ifEmpty { null },
            ips = j.optJSONArray("tailscaleIPs").strings(),
            dnsName = j.optString("dnsName").trim('.'),
            tailnet = j.optString("tailnet"),
            error = j.optString("error").ifEmpty { null },
            health = j.optJSONArray("health").strings(),
            peers = j.optJSONArray("peers")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { p ->
                    TailscalePeer(
                        hostName = p.optString("hostName"),
                        dnsName = p.optString("dnsName").trim('.'),
                        os = p.optString("os"),
                        ips = p.optJSONArray("tailscaleIPs").strings(),
                        online = p.optBoolean("online"),
                        exitNodeOption = p.optBoolean("exitNodeOption"),
                        exitNode = p.optBoolean("exitNode"),
                    )
                }.sortedWith(compareBy({ !it.online }, { it.displayName.lowercase() }))
            }.orEmpty(),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
object NetworkHub {

    private const val TAG = "Network"
    internal const val HEALTH_INTERVAL_MS = 30_000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Dispatchers.IO.limitedParallelism(1)

    private val _settings = MutableStateFlow(NetworkSettings())
    val settings: StateFlow<NetworkSettings> = _settings.asStateFlow()

    private val _tailscale = MutableStateFlow(TailscaleStatus())
    val tailscale: StateFlow<TailscaleStatus> = _tailscale.asStateFlow()

    @Volatile
    private var nodeUp = false

    private val _entryPort = MutableStateFlow<Int?>(null)
    val entryPort: StateFlow<Int?> = _entryPort.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    private val applyGeneration = AtomicInteger(0)

    private val nodeTouched get() = TailscaleNode.nodeTouched
    private val entryTouched get() = TailscaleNode.entryTouched

    private var pollJob: Job? = null
    private var pollers = 0

    val isInitialized: Boolean get() = ::appContext.isInitialized

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        NetworkRuntime.addListener { snap -> main.post { WebViewNetworkProxy.apply(snap) } }
        NetworkShellEnvironment.watchRuntime { cleared ->
            scope.launch {
                runCatching { com.openminis.app.sandbox.ExecutionCoordinator.broadcastProxyChange(cleared) }
                    .onFailure { AppLogger.warning(TAG, "[Net][Shell] could not update running shells: ${it.message}") }
            }
        }
        NetworkRuntime.addListener { scope.launch { com.openminis.app.backup.remote.RcloneBridge.applyNetworkProxyIfInitialised() } }
        ShellTransparentNet.init(appContext)
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        NetworkAlertCenter.isOnline = {
            runCatching {
                cm?.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrDefault(true)
        }
        scope.launch { settings.collect { NetworkAlertCenter.prune(it) } }
        NetworkStatusMonitor.start(settings)
        val s = NetworkSettingsStore.load(appContext)
        _settings.value = s
        if (s.nodeWanted || s.needsEntry) {
            AppLogger.info(TAG, "[Net][Session] bootstrap: tailscale=${s.tailscaleEnabled} default=${s.defaultRoute} proxies=${s.proxies.size} forceDirect=${s.forceDirect}")
            apply()
        }
    }

    fun update(change: (NetworkSettings) -> NetworkSettings) {
        val old = _settings.value
        val next = change(old)
        if (next == old) return
        val restartNode = next.hostname != old.hostname && next.nodeWanted && old.nodeWanted
        _settings.value = next
        NetworkSettingsStore.save(appContext, next)
        apply(restartNode = restartNode)
    }

    fun apply(restartNode: Boolean = false, authKey: String? = null): Job {
        val generation = applyGeneration.incrementAndGet()
        _applying.value = true
        return scope.launch(serial) { applyNow(generation, restartNode, authKey) }
    }

    private fun applyNow(generation: Int, restartNode: Boolean, authKey: String?) {
        val s = _settings.value
        var failure: String? = null
        var nodeUp = false
        if (s.nodeWanted) {
            try {
                if (restartNode || authKey != null) runCatching { TailscaleNode.stop() }
                TailscaleNode.start(appContext, authKey, hostname(s))
                TailscaleNode.setAcceptRoutes(s.acceptRoutes)
                runCatching { TailscaleNode.setExitNode(s.exitNodeIP.orEmpty()) }
                    .onFailure { AppLogger.warning(TAG, "[Net][TS] exit node not applied: ${it.message}") }
                nodeUp = true
            } catch (e: Exception) {
                failure = "Tailscale: " + TailscaleNode.redact(e.message ?: e.javaClass.simpleName)
                AppLogger.error(TAG, "[Net][TS] apply failed: $failure (non-tailnet traffic is unaffected)")
            }
        } else if (nodeTouched) {
            runCatching { TailscaleNode.stop() }
        }

        var port: Int? = null
        var httpPort: Int? = null
        var password = ""
        if (s.needsEntry) {
            try {
                val r = TailscaleNode.configureEntry(routingConfig(s, tailscale = nodeUp))
                port = r.port
                httpPort = r.httpPort
                password = r.password
            } catch (e: Exception) {
                val msg = "Local entry: " + (e.message ?: e.javaClass.simpleName)
                failure = listOfNotNull(failure, msg).joinToString("; ")
                AppLogger.error(TAG, "[Net][Entry] configure failed: $msg -> direct routes go direct, proxy / tailscale routes are blocked until it works")
            }
        } else if (entryTouched) {
            runCatching { TailscaleNode.stopEntry() }
        }

        val status = if (nodeTouched) runCatching { TailscaleStatus.parse(TailscaleNode.status()) }.getOrDefault(TailscaleStatus()) else TailscaleStatus()
        if (generation != applyGeneration.get()) return
        _entryPort.value = port
        _lastError.value = failure
        _tailscale.value = status
        _applying.value = false
        this.nodeUp = nodeUp
        NetworkRuntime.publish(
            NetworkRuntime.Snapshot(
                port = port,
                httpPort = httpPort,
                password = password,
                entryDown = s.needsEntry && port == null,
                tailscaleEnabled = nodeUp && status.tailnetUsable,
                defaultRoute = s.defaultRoute,
                tagRoutes = s.tagRoutes,
            ),
        )
        AppLogger.info(TAG, "[Net][Session] applied tailscale=${s.tailscaleEnabled}(${if (nodeUp) status.backendState.ifEmpty { "started" } else "off"}) entry=${port ?: "off"} default=${s.defaultRoute} proxies=${s.proxies.size} forceDirect=${s.forceDirect}")
        ShellTransparentNet.sync("apply")
        if (nodeUp) scope.launch {
            delay(3000)
            if (generation == applyGeneration.get()) refreshStatusNow()
        }
    }

    private fun refreshStatusNow() {
        if (!nodeTouched) return
        publishStatus(runCatching { TailscaleStatus.parse(TailscaleNode.status()) }.getOrDefault(TailscaleStatus()))
    }

    private fun publishStatus(status: TailscaleStatus) {
        val prev = _tailscale.value
        val before = prev.tailnetUsable
        _tailscale.value = status
        if (prev.backendState != status.backendState || prev.running != status.running) {
            ShellTransparentNet.sync("tailscale ${status.backendState.ifEmpty { "-" }}")
            scope.launch {
                delay(6000)
                ShellTransparentNet.sync("tailscale settled")
            }
        }
        if (before == status.tailnetUsable) return
        scope.launch(serial) { regateTailnet() }
    }

    private fun regateTailnet() {
        val status = _tailscale.value
        val want = nodeUp && status.tailnetUsable
        val cur = NetworkRuntime.snapshot
        if (cur.tailscaleEnabled == want) return
        AppLogger.info(TAG, "[Net][TS] tailnet ${if (want) "reachable" else "NOT reachable"} " +
            "(state=${status.backendState.ifEmpty { "-" }} running=${status.running}) -> direct-routed traffic " +
            if (want) "goes through the local entry again" else "no longer goes through the local entry")
        NetworkRuntime.publish(cur.copy(tailscaleEnabled = want))
    }

    private var healthJob: Job? = null

    @Synchronized
    fun onForeground() {
        NetworkStatusMonitor.onForeground()
        verifyEntry()
        if (healthJob != null) return
        healthJob = scope.launch {
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                verifyEntry()
            }
        }
    }

    @Synchronized
    fun onBackground() {
        NetworkStatusMonitor.onBackground()
        healthJob?.cancel()
        healthJob = null
    }

    fun verifyEntry() {
        if (nodeUp) scope.launch { refreshStatusNow() }
        ShellTransparentNet.sync("health")
        if (!entryTouched || _entryPort.value == null) return
        scope.launch(serial) {
            val listening = runCatching {
                val h = TailscaleNode.health()
                h.optBoolean("alive") && h.optString("httpAddr").isNotEmpty()
            }.getOrDefault(false)
            if (!listening) {
                AppLogger.warning(TAG, "[Net][Entry] entry on ${_entryPort.value} is not listening -> proxy / tailscale routes blocked, direct routes direct, until it restarts")
                NetworkRuntime.publish(NetworkRuntime.snapshot.copy(port = null, httpPort = null, password = "", entryDown = true))
                applyNow(applyGeneration.incrementAndGet(), restartNode = false, authKey = null)
            }
        }
    }

    private fun hostname(s: NetworkSettings): String {
        val raw = s.hostname.ifBlank { "minis-android" }
        val name = raw.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("").trim('-')
        return name.ifEmpty { "minis" }.take(63)
    }

    internal fun routingConfig(s: NetworkSettings, tailscale: Boolean): JSONObject = JSONObject().apply {
        put("defaultRoute", s.defaultRoute)
        put("tags", JSONObject(s.tagRoutes))
        put("upstreams", JSONArray().apply {
            for (p in s.proxies) put(upstreamJson(p, NetworkSecrets.password(appContext, p.id)))
        })
        put("tailscale", tailscale)
        if (s.exitNodeDNS) put("exitNodeDNS", true)
    }

    internal fun upstreamJson(p: ProxyServer, password: String) = JSONObject().apply {
        put("id", p.id)
        put("name", p.displayName)
        put("type", p.kind)
        put("host", p.host)
        put("port", p.port)
        put("username", p.username)
        put("password", password)
        put("bypass", JSONArray(p.bypass))
    }


    fun refreshStatus() {
        if (!nodeTouched) return
        scope.launch { refreshStatusNow() }
    }

    @Synchronized
    fun startPolling() {
        pollers++
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                refreshStatusNow()
                delay(2000)
            }
        }
    }

    @Synchronized
    fun stopPolling() {
        pollers = (pollers - 1).coerceAtLeast(0)
        if (pollers == 0) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    val browserLogin: TailscaleLoginFlow by lazy {
        TailscaleLoginFlow(
            scope = scope,
            status = { if (nodeTouched) runCatching { TailscaleStatus.parse(TailscaleNode.status()) }.getOrDefault(TailscaleStatus()) else TailscaleStatus() },
            returnToApp = ::bringAppForward,
            publish = ::publishStatus,
            freshLogin = { apply(restartNode = true).join() },
        )
    }

    internal fun bringAppForward() {
        val intent = android.content.Intent(appContext, com.openminis.app.MainActivity::class.java).addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        runCatching { appContext.startActivity(intent) }
            .onFailure { AppLogger.warning(TAG, "[Net][TS] login: could not bring the app back: ${it.javaClass.simpleName}") }
    }

    fun login(authKey: String) {
        apply(restartNode = true, authKey = authKey.trim())
    }

    fun logout() {
        scope.launch(serial) {
            runCatching { TailscaleNode.logout() }
            applyNow(applyGeneration.incrementAndGet(), false, null)
        }
    }

    fun clearTailscale() {
        scope.launch(serial) {
            if (nodeTouched) runCatching { TailscaleNode.logout() }
        }
        update { it.copy(tailscaleEnabled = false, exitNodeIP = null) }
        scope.launch(serial) {
            TailscaleNode.stateDirectory(appContext).deleteRecursively()
            AppLogger.info(TAG, "[Net][TS] signed out and cleared the node state")
            publishStatus(TailscaleStatus())
        }
    }


    fun saveProxy(proxy: ProxyServer, password: String?) {
        if (password != null) NetworkSecrets.setPassword(appContext, proxy.id, password)
        val before = _settings.value
        update { s ->
            val i = s.proxies.indexOfFirst { it.id == proxy.id }
            s.copy(proxies = if (i >= 0) s.proxies.toMutableList().also { it[i] = proxy } else s.proxies + proxy)
        }
        if (password != null && _settings.value == before) apply()
    }

    fun deleteProxy(id: String) {
        NetworkSecrets.remove(appContext, id)
        update { it.withoutProxy(id) }
    }

    fun proxyInUse(id: String, s: NetworkSettings = _settings.value): Boolean {
        val r = "proxy:$id"
        return s.defaultRoute == r || r in s.scopes.values || r in s.providerRoutes.values
    }

    fun stopUsingProxy(id: String) {
        update { it.stopUsing(id) }
        AppLogger.warning(TAG, "[Net] stopped using proxy ${id.take(8)} - its routes now connect directly")
    }

    val pendingProxyEditor = MutableStateFlow<String?>(null)

    fun proxyPassword(id: String): String = NetworkSecrets.password(appContext, id)

    fun testProxy(proxy: ProxyServer, password: String, target: String = "www.apple.com:443"): Long =
        TailscaleNode.testUpstream(upstreamJson(proxy, password), target)


    fun providerRoute(instanceId: String): NetworkRoute = _settings.value.providerRoute(instanceId)

    fun setProviderRoute(route: NetworkRoute, instanceId: String) {
        update { s ->
            s.copy(providerRoutes = if (route == NetworkRoute.FollowDefault) s.providerRoutes - instanceId
            else s.providerRoutes + (instanceId to route.raw))
        }
    }


    fun restoreDirect() {
        AppLogger.warning(TAG, "[Net][Session] restore direct connections: network module off")
        update { it.copy(forceDirect = true) }
    }

    fun resume() {
        AppLogger.info(TAG, "[Net][Session] network module back on")
        update { it.copy(forceDirect = false) }
    }

    suspend fun awaitIdle() = withContext(serial) { }
}
