package com.openminis.app.network

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.rclone.gomobile.Gomobile
import com.openminis.rclone.gomobile.LogSink
import java.io.File
import org.json.JSONObject

object TailscaleNode {

    private const val TAG = "Network"

    class NodeException(message: String) : Exception(message)

    private val loginLink = Regex("""(login\.tailscale\.com/a/)[A-Za-z0-9]+""")

    fun redact(line: String): String = loginLink.replace(line, "$1<redacted>")

    internal fun isVerbose(line: String): Boolean =
        line.contains("[v1]") || line.contains("[v2]") || line.contains("JSON]")

    @Volatile
    private var loggerInstalled = false

    @Volatile
    var nodeTouched = false
        private set

    @Volatile
    var entryTouched = false
        private set

    @Synchronized
    fun installLogger() {
        if (loggerInstalled) return
        Gomobile.tsSetLogger(object : LogSink {
            override fun line(line: String) {
                NetworkAlertCenter.ingest(line)
                val text = if (line.startsWith("[Net][")) redact(line) else "[Net][TS] " + redact(line)
                if (isVerbose(line)) AppLogger.debug(TAG, text) else AppLogger.info(TAG, text)
            }
        })
        loggerInstalled = true
    }

    fun stateDirectory(context: Context): File = File(context.noBackupFilesDir, "tailscale")

    private fun call(raw: String): Any? {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw NodeException("tsnet returned invalid JSON")
        if (obj.optBoolean("ok")) return obj.opt("result")
        throw NodeException(obj.optString("error").ifEmpty { "tsnet call failed" })
    }

    private fun callObject(raw: String): JSONObject = call(raw) as? JSONObject ?: JSONObject()

    fun start(context: Context, authKey: String?, hostname: String) {
        installLogger()
        val dir = stateDirectory(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw NodeException("cannot create the Tailscale state directory")
        nodeTouched = true
        TailscalePathMonitor.start(context)
        AppLogger.info(TAG, "[Net][TS] start hostname=$hostname authKey=${if (authKey.isNullOrEmpty()) "none" else "provided"} dir=noBackupFiles/tailscale")
        val t0 = System.nanoTime()
        try {
            call(Gomobile.tsStart(authKey.orEmpty(), hostname, dir.absolutePath))
            AppLogger.info(TAG, "[Net][TS] start ok in ${"%.2f".format((System.nanoTime() - t0) / 1e9)}s")
        } catch (e: NodeException) {
            AppLogger.error(TAG, "[Net][TS] start FAILED: ${redact(e.message.orEmpty())}")
            throw e
        }
    }

    fun setAcceptRoutes(on: Boolean) {
        call(Gomobile.tsSetAcceptRoutes(on))
        AppLogger.info(TAG, "[Net][TS] accept subnet routes = $on")
    }

    fun setExitNode(ip: String) {
        call(Gomobile.tsSetExitNode(ip))
        AppLogger.info(TAG, "[Net][TS] exit node = ${ip.ifEmpty { "none" }}")
    }

    fun stop() {
        call(Gomobile.tsStop())
        AppLogger.info(TAG, "[Net][TS] stopped")
    }

    fun logout() {
        call(Gomobile.tsLogout())
        AppLogger.info(TAG, "[Net][TS] logged out")
    }

    fun status(): JSONObject = callObject(Gomobile.tsStatus())

    fun routes(): JSONObject = callObject(Gomobile.tsRoutes())

    fun dialTest(address: String, timeoutMs: Int = 8000): Long =
        callObject(Gomobile.tsDialTest(address, timeoutMs.toLong())).optLong("ms", -1)


    data class Entry(val port: Int, val httpPort: Int?, val password: String)

    fun configureEntry(config: JSONObject): Entry {
        installLogger()
        entryTouched = true
        val r = callObject(Gomobile.netConfigure(config.toString()))
        val port = r.optInt("port", 0)
        val password = r.optString("password")
        if (port <= 0 || password.isEmpty()) throw NodeException("local entry did not report its port")
        return Entry(port, r.optInt("httpPort", 0).takeIf { it > 0 }, password)
    }

    fun stopEntry() {
        call(Gomobile.netStop())
    }


    fun hookRoutes(tag: String, local: List<String>): JSONObject =
        callObject(Gomobile.netHookRoutes(tag, org.json.JSONArray(local).toString()))

    fun hookRoutes2(tag: String, local: List<String>, resolvers: List<String>): JSONObject =
        callObject(Gomobile.netHookRoutes2(tag, org.json.JSONArray(local).toString(), org.json.JSONArray(resolvers).toString()))

    data class XparentPorts(val port4: Int, val port6: Int, val udpPort: Int)

    fun xparentStart(mapDir: String, tag: String): XparentPorts {
        installLogger()
        val r = callObject(Gomobile.netXparentStart(mapDir, tag))
        return XparentPorts(r.optInt("port4"), r.optInt("port6"), r.optInt("udpPort"))
    }

    fun shellProbe(resolver: String, names: List<String>, timeoutMs: Int = 5000): JSONObject =
        callObject(Gomobile.netShellProbe(resolver, org.json.JSONArray(names).toString(), timeoutMs.toLong()))

    fun xparentStop() {
        runCatching { call(Gomobile.netXparentStop()) }
    }

    fun xparentStatus(): JSONObject = callObject(Gomobile.netXparentStatus())

    fun entryStatus(): JSONObject = callObject(Gomobile.netStatus())

    fun health(): JSONObject = callObject(Gomobile.netHealth())

    fun selfTest(timeoutMs: Int = 5000): Long = callObject(Gomobile.netSelfTest(timeoutMs.toLong())).optLong("ms", -1)

    fun goDiagnose(timeoutMs: Int = 8000): JSONObject = callObject(Gomobile.netDiagnose(timeoutMs.toLong()))

    fun decide(tag: String, host: String): JSONObject = callObject(Gomobile.netDecide(tag, host))

    fun probe(tag: String, address: String, timeoutMs: Int = 8000): JSONObject {
        val obj = JSONObject(Gomobile.netProbe(tag, address, timeoutMs.toLong()))
        return obj.optJSONObject("result") ?: obj
    }

    fun testUpstream(upstream: JSONObject, target: String, timeoutMs: Int = 8000): Long =
        callObject(Gomobile.netTestUpstream(upstream.toString(), target, timeoutMs.toLong())).optLong("ms", -1)
}
