package com.openminis.app.backup.remote

import com.openminis.app.logging.AppLogger
import com.openminis.rclone.gomobile.Gomobile
import org.json.JSONObject

object RcloneBridge {

    private const val TAG = "Rclone"

    class RPCException(val status: Int, payload: String) :
        Exception(extractMessage(status, payload)) {
        companion object {
            private fun extractMessage(status: Int, payload: String): String =
                runCatching { JSONObject(payload).optString("error").takeIf { it.isNotEmpty() } }
                    .getOrNull() ?: "rclone RPC failed (status $status)"
        }
    }

    @Volatile
    private var initialised = false

    @Synchronized
    fun initializeIfNeeded() {
        if (initialised) return
        Gomobile.rcloneInitialize()
        initialised = true
        applyGlobalOptions()
        applyNetworkProxy()
        AppLogger.info(TAG, "[Rclone] initialised")
    }

    @Synchronized
    fun applyNetworkProxy() {
        val ep = com.openminis.app.network.NetworkRuntime.endpoint(com.openminis.app.network.NetworkScope.BACKUP.tag)
        applySocksProxy(ep?.let { "${it.tag}:${it.password}@${it.host}:${it.port}" } ?: "")
        val proxy = ep?.let { "socks5://${it.tag}:${it.password}@${it.host}:${it.port}" } ?: ""
        if (proxy == lastAppliedProxy) return
        try {
            rpcUnchecked("options/set", mapOf("main" to mapOf("HTTPProxy" to proxy)))
            lastAppliedProxy = proxy
            AppLogger.info(TAG, "[Net][Backup] rclone HTTPProxy ${if (proxy.isEmpty()) "cleared (direct)" else "-> local entry (backup)"}")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Net][Backup] couldn't set rclone HTTPProxy: ${e.message}")
        }
    }

    private var lastAppliedProxy = ""

    private fun applySocksProxy(socks: String) {
        if (socks == lastAppliedSocks) return
        val reply = runCatching { Gomobile.rcloneSetSocks(socks) }.getOrElse { "{\"ok\":false,\"error\":\"${it.javaClass.simpleName}\"}" }
        if (!reply.startsWith("{\"ok\":true")) {
            AppLogger.warning(TAG, "[Net][Backup] couldn't set rclone SFTP/FTP socks_proxy: $reply")
            return
        }
        lastAppliedSocks = socks
        val snap = com.openminis.app.network.NetworkRuntime.snapshot
        AppLogger.info(TAG, "[Net][Backup] rclone SFTP/FTP socks_proxy " +
            (if (socks.isEmpty()) "unset (direct)" else "-> backup:<password>@127.0.0.1:${snap.port} route=${snap.route(com.openminis.app.network.NetworkScope.BACKUP.tag).raw}") +
            "; fs cache cleared")
    }

    private var lastAppliedSocks = ""

    fun applyNetworkProxyIfInitialised() {
        if (initialised) applyNetworkProxy()
    }

    const val CONNECT_TIMEOUT_SECONDS = 20L

    const val IO_TIMEOUT_SECONDS = 300L

    const val LOW_LEVEL_RETRIES = 1L
    const val RETRIES = 1L

    fun durationNanos(seconds: Long): Long = seconds * 1_000_000_000L

    fun globalTimeoutOptions(): Map<String, Any> = mapOf(
        "Timeout" to durationNanos(IO_TIMEOUT_SECONDS),
        "ConnectTimeout" to durationNanos(CONNECT_TIMEOUT_SECONDS),
        "LowLevelRetries" to LOW_LEVEL_RETRIES,
        "Retries" to RETRIES,
    )

    fun applyGlobalOptions() {
        runCatching {
            rpcUnchecked("options/set", mapOf("main" to globalTimeoutOptions()))
        }.onSuccess {
            AppLogger.info(
                TAG,
                "[Rclone] timeouts set: connect=${CONNECT_TIMEOUT_SECONDS}s io=${IO_TIMEOUT_SECONDS}s " +
                    "lowLevelRetries=$LOW_LEVEL_RETRIES retries=$RETRIES",
            )
        }.onFailure {
            AppLogger.warning(TAG, "[Rclone] couldn't set timeouts: ${it.message}")
        }
    }

    fun setInsecureTLS(allow: Boolean) {
        runCatching {
            rpcUnchecked("options/set", mapOf("main" to mapOf("InsecureSkipVerify" to allow)))
        }.onSuccess {
            AppLogger.info(
                TAG,
                "[Rclone] TLS certificate verification ${if (allow) "DISABLED" else "enabled"}",
            )
        }.onFailure {
            AppLogger.warning(TAG, "[Rclone] couldn't set TLS option: ${it.message}")
        }
    }

    private fun rpcUnchecked(method: String, params: Map<String, Any?>): JSONObject {
        val input = JSONObject(params.mapValues { toJsonValue(it.value) }).toString()
        val result = RcloneRpcTracker.track(method) { Gomobile.rcloneRPC(method, input) }
        val output = result.output ?: ""
        if (result.status != 200L) throw RPCException(result.status.toInt(), output)
        return runCatching { JSONObject(output) }.getOrDefault(JSONObject())
    }

    fun rpc(method: String, params: Map<String, Any?> = emptyMap()): JSONObject {
        initializeIfNeeded()
        val input = JSONObject(params.mapValues { toJsonValue(it.value) }).toString()
        val result = RcloneRpcTracker.track(method) { Gomobile.rcloneRPC(method, input) }
        val output = result.output ?: ""
        if (result.status != 200L) throw RPCException(result.status.toInt(), output)
        return runCatching { JSONObject(output) }.getOrDefault(JSONObject())
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(value.entries.associate { (k, v) -> k.toString() to toJsonValue(v) })
        is List<*> -> org.json.JSONArray(value.map { toJsonValue(it) })
        else -> value
    }

    fun smokeTest(): String = try {
        val v = rpc("core/version")
        val version = v.optString("version", "?")
        val goVersion = v.optString("goVersion", "?")
        val arch = v.optString("arch", "?")
        AppLogger.info(TAG, "[Rclone] smoke OK version=$version go=$goVersion arch=$arch")
        "rclone $version ($goVersion, $arch)"
    } catch (e: Exception) {
        AppLogger.error(TAG, "[Rclone] smoke FAILED: ${e.message}")
        "FAILED: ${e.message}"
    }

    fun supportedBackends(): List<String> = runCatching {
        val providers = rpc("config/providers").optJSONArray("providers") ?: return emptyList()
        (0 until providers.length())
            .mapNotNull { providers.optJSONObject(it)?.optString("Name") }
            .filter { it.isNotEmpty() }
            .sorted()
    }.getOrDefault(emptyList())
}
