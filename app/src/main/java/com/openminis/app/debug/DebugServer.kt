package com.openminis.app.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class DebugServer(
    private val context: Context,
    private val port: Int = 5321,
) {
    companion object {
        private const val TAG = "DebugServer"

        fun isAuthorized(isLoopback: Boolean, providedToken: String?, expectedToken: String): Boolean {
            if (isLoopback) return true
            if (expectedToken.isEmpty()) return false
            val provided = providedToken ?: return false
            if (provided.length != expectedToken.length) return false
            var diff = 0
            for (i in expectedToken.indices) {
                diff = diff or (provided[i].code xor expectedToken[i].code)
            }
            return diff == 0
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val rpcHandler = DebugRPCHandler(context)

    private val authToken: String by lazy {
        val f = java.io.File(context.filesDir, "debug_server_token")
        if (f.exists()) {
            f.readText().trim().ifEmpty { generateToken(f) }
        } else {
            generateToken(f)
        }
    }

    private fun generateToken(f: java.io.File): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        runCatching { f.writeText(token) }
        return token
    }

    @Volatile
    private var stopped = false

    fun start() {
        if (serverSocket != null) return
        stopped = false

        acceptJob = scope.launch {
            try {
                val ss = ServerSocket(port, 10)
                serverSocket = ss
                Log.i(TAG, "Debug server listening on port $port (all interfaces)")
                Log.i(TAG, "Remote (non-loopback) clients must send X-Minis-Token: $authToken")

                while (!stopped) {
                    try {
                        val client = ss.accept()
                        launch { handleConnection(client) }
                    } catch (e: Exception) {
                        if (!stopped) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port: ${e.message}")
            }
        }
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        Log.i(TAG, "Server stopped")
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val reader = HttpRequestReader(s.getInputStream())
                val writer = PrintWriter(s.getOutputStream(), true)

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Parse error"))
                    return
                }
                val method = parts[0]
                val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"

                if (method == "OPTIONS") {
                    sendCorsPreflightResponse(writer)
                    return
                }

                var contentLength = 0
                var providedToken: String? = null
                var accept = ""
                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                    val lower = headerLine.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (lower.startsWith("accept:")) {
                        accept = lower.substringAfter(":").trim()
                    }
                    if (lower.startsWith("x-minis-token:")) {
                        providedToken = headerLine.substringAfter(":").trim()
                    }
                    if (lower.startsWith("authorization:")) {
                        val v = headerLine.substringAfter(":").trim()
                        if (v.lowercase().startsWith("bearer ")) {
                            providedToken = v.substring(7).trim()
                        }
                    }
                }

                val isLoopback = s.inetAddress?.isLoopbackAddress == true
                if (!isAuthorized(isLoopback, providedToken, authToken)) {
                    Log.w(TAG, "401 unauthorized ${if (isLoopback) "loopback" else s.inetAddress?.hostAddress ?: "?"} (missing/wrong token)")
                    sendResponse(writer, 401, rpcHandler.errorJSON(-32000, "Unauthorized — send X-Minis-Token (see `adb shell run-as com.openminis.app cat files/debug_server_token`)"))
                    return
                }

                if (method == "GET") {
                    val wantsHuman = accept.contains("text/html") ||
                        accept.contains("text/markdown") ||
                        accept.contains("text/plain")
                    when (path) {
                        "/schema" -> {
                            sendResponse(writer, 200, schemaJSON())
                            return
                        }
                        "/", "/skill", "/skill/" -> {
                            if (path == "/" && !wantsHuman) {
                                sendResponse(writer, 200, schemaJSON())
                            } else {
                                sendSkill(writer, wantsHuman)
                            }
                            return
                        }
                        "/skill/examples/python", "/skill/examples/minis_rpc_android.py" -> {
                            sendSkillAsset(writer, "examples/minis_rpc_android.py", "text/x-python; charset=utf-8")
                            return
                        }
                        "/skill/examples/curl", "/skill/examples/curl.md" -> {
                            sendSkillAsset(writer, "examples/curl.md", "text/markdown; charset=utf-8")
                            return
                        }
                    }
                }

                if (method != "POST") {
                    sendResponse(writer, 405, rpcHandler.errorJSON(-32600, "Only POST accepted"))
                    return
                }

                if (contentLength <= 0) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Empty body"))
                    return
                }

                val body = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = reader.read(body, totalRead, contentLength - totalRead)
                    if (n < 0) break
                    totalRead += n
                }
                val jsonBody = String(body, 0, totalRead, Charsets.UTF_8)

                val responseJSON = runBlocking {
                    rpcHandler.handle(jsonBody)
                }

                sendResponse(writer, 200, responseJSON)
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            }
        }
    }

    private fun schemaJSON(): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        return """{"app":"MinisApp","platform":"android","version":"$version",""" +
            """"rpc":"jsonrpc-2.0","auth":"token-lan","transport":"plaintext",""" +
            """"rpc_path":"/","skill_path":"/skill"}"""
    }

    private fun sendSkill(writer: PrintWriter, wantsHuman: Boolean) {
        val skill = readSkillAsset("SKILL.md")
        if (skill == null) {
            sendResponse(writer, 503, """{"error":"skill assets not bundled in this build"}""")
            return
        }
        if (wantsHuman) {
            sendResponse(writer, 200, skill, "text/markdown; charset=utf-8")
            return
        }
        val py = readSkillAsset("examples/minis_rpc_android.py") ?: ""
        val curl = readSkillAsset("examples/curl.md") ?: ""
        val payload = org.json.JSONObject().apply {
            put("skill", skill)
            put("clients", org.json.JSONObject().apply {
                put("python", py)
                put("curl", curl)
            })
            put("client_paths", org.json.JSONObject().apply {
                put("python", "/skill/examples/python")
                put("curl", "/skill/examples/curl")
            })
        }
        sendResponse(writer, 200, payload.toString())
    }

    private fun sendSkillAsset(writer: PrintWriter, assetPath: String, contentType: String) {
        val body = readSkillAsset(assetPath)
        if (body == null) {
            sendResponse(writer, 503, """{"error":"asset not bundled: $assetPath"}""")
            return
        }
        sendResponse(writer, 200, body, contentType)
    }

    private fun readSkillAsset(relPath: String): String? = try {
        context.assets.open("debug-skill/$relPath").bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun sendResponse(
        writer: PrintWriter,
        statusCode: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    private class HttpRequestReader(private val input: java.io.InputStream) {
        private val buf = java.io.ByteArrayOutputStream(128)

        fun readLine(): String? {
            buf.reset()
            var sawAny = false
            while (true) {
                val b = input.read()
                if (b < 0) return if (sawAny) buf.toString("UTF-8") else null
                sawAny = true
                if (b == '\n'.code) return buf.toString("UTF-8")
                if (b != '\r'.code) buf.write(b)
            }
        }

        fun read(dst: ByteArray, off: Int, len: Int): Int = input.read(dst, off, len)
    }

    private fun sendCorsPreflightResponse(writer: PrintWriter) {
        writer.print("HTTP/1.1 204 No Content\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("Access-Control-Max-Age: 86400\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }
}
