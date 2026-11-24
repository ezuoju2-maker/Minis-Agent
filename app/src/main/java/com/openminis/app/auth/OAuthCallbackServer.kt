package com.openminis.app.auth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI

class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val onError: (OAuthCallbackError) -> Unit,
    private val onCode: (code: String, state: String?) -> Unit,
) {
    companion object {
        private const val TAG = "OAuthCallbackServer"

        internal fun parseRequest(requestLine: String): CallbackRequest {
            val parts = requestLine.split(" ")
            if (parts.size < 2) return CallbackRequest.Other
            val params = try {
                URI("http://localhost${parts[1]}").rawQuery?.split("&")?.filter { it.isNotEmpty() }?.associate {
                    val kv = it.split("=", limit = 2)
                    decode(kv[0]) to (if (kv.size > 1) decode(kv[1]) else "")
                } ?: emptyMap()
            } catch (e: Exception) {
                return CallbackRequest.Other
            }
            val code = params["code"]
            if (!code.isNullOrEmpty()) return CallbackRequest.Code(code, params["state"])
            val error = params["error"]
            if (error != null) {
                return CallbackRequest.Error(
                    OAuthCallbackError(
                        error = error.ifEmpty { "unknown_error" },
                        description = params["error_description"]?.takeIf { it.isNotBlank() },
                    ),
                )
            }
            return CallbackRequest.Other
        }

        private fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")

        internal fun errorPage(error: String, packageName: String? = null): String {
            val back = packageName?.let {
                "<p>Return to Minis to continue.</p><p><a href=\"${returnToAppUrl(it)}\" style=\"display:inline-block;" +
                    "padding:12px 24px;border-radius:8px;background:#10a37f;color:#fff;text-decoration:none;" +
                    "font-size:18px\">Return to Minis</a></p>"
            }.orEmpty()
            val html = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                "<body style=\"font-family:sans-serif;text-align:center;padding:24px\">" +
                "<h1>Authorization Failed</h1><p>${htmlEscape(error)}</p>$back</body></html>"
            return "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$html"
        }

        private fun htmlEscape(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        internal fun successPage(packageName: String): String =
            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                "<body style=\"font-family:sans-serif;text-align:center;padding:24px\">" +
                "<h1>Authorization received</h1><p>Return to Minis to finish signing in.</p>" +
                "<p><a href=\"${returnToAppUrl(packageName)}\" style=\"display:inline-block;padding:12px 24px;" +
                "border-radius:8px;background:#10a37f;color:#fff;text-decoration:none;font-size:18px\">Return to Minis</a></p>" +
                "<script>window.close()</script></body></html>"

        internal fun returnToAppUrl(packageName: String): String =
            "intent://oauth/return#Intent;scheme=minis;package=$packageName;end"


        private class Served(val at: Long, val state: String?)

        private val listening = java.util.concurrent.ConcurrentHashMap<Int, OAuthCallbackServer>()

        private val served = java.util.concurrent.ConcurrentHashMap<Int, Served>()

        internal const val SERVED_WINDOW_MS = 10 * 60_000L

        internal fun forwardDecision(
            listeningNow: Boolean,
            servedAt: Long?,
            servedState: String?,
            redirectState: String?,
            now: Long,
        ): Forward {
            val recent = servedAt != null && now - servedAt in 0..SERVED_WINDOW_MS
            val sameCallback = servedState == null || redirectState == null || servedState == redirectState
            return when {
                recent && sameCallback -> Forward.ALREADY_SERVED
                listeningNow -> Forward.FORWARD
                else -> Forward.NOT_LISTENING
            }
        }

        fun forwardDecision(port: Int, redirectState: String?): Forward {
            val s = served[port]
            return forwardDecision(listening.containsKey(port), s?.at, s?.state, redirectState, android.os.SystemClock.uptimeMillis())
        }
    }

    enum class Forward { FORWARD, ALREADY_SERVED, NOT_LISTENING }

    internal sealed class CallbackRequest {
        data class Code(val code: String, val state: String?) : CallbackRequest()
        data class Error(val error: OAuthCallbackError) : CallbackRequest()
        object Other : CallbackRequest()
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun finish(): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        running = false
        return true
    }

    var boundPort: Int = port
        private set

    @Volatile var onExternalCancel: (() -> Unit)? = null

    private val keepAlive = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private fun releaseKeepAlive(reason: String, token: String? = null) {
        val id = if (token == null) keepAlive.getAndSet(null)
        else token.takeIf { keepAlive.compareAndSet(it, null) }
        id?.let { com.openminis.app.service.OAuthWaitKeepAlive.end(it, "$reason port=$boundPort") }
    }

    fun start() {
        running = true
        val token = com.openminis.app.service.OAuthWaitKeepAlive.begin("oauth-callback port=$port")
        keepAlive.getAndSet(token)
            ?.let { com.openminis.app.service.OAuthWaitKeepAlive.end(it, "restarted port=$port") }
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound = false
                for (p in portsToTry) {
                    var attempt = 0
                    while (!bound && attempt < 2) {
                        try {
                            val ss = ServerSocket()
                            ss.reuseAddress = true
                            ss.bind(java.net.InetSocketAddress(p))
                            serverSocket = ss
                            boundPort = p
                            bound = true
                        } catch (e: java.net.BindException) {
                            attempt++
                        } catch (e: java.io.IOException) {
                            attempt++
                        }
                        if (!bound && attempt < 2) {
                            try { Thread.sleep(40) } catch (_: InterruptedException) { break }
                        }
                    }
                    if (bound) break
                    Log.w(TAG, "Port $p unavailable after retries, trying next...")
                }
                if (!bound) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    releaseKeepAlive("bind-failed", token)
                    return@Thread
                }
                Log.d(TAG, "Listening on port $boundPort")
                served.remove(boundPort)
                listening[boundPort] = this
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: continue
                        Log.d(TAG, "Request: ${OAuthLogRedaction.url(requestLine)}")

                        if (requestLine.startsWith("OPTIONS")) {
                            var origin: String? = null
                            while (true) {
                                val h = reader.readLine() ?: break
                                if (h.isEmpty()) break
                                val lower = h.lowercase()
                                if (lower.startsWith("origin:")) {
                                    origin = h.substringAfter(":").trim()
                                }
                            }
                            val trustedHosts = setOf("auth.x.ai", "accounts.x.ai")
                            val originHost = origin?.let { raw ->
                                runCatching { java.net.URI(raw.trim()).host?.lowercase() }.getOrNull()
                            }
                            val allowOrigin = if (origin != null && originHost != null && originHost in trustedHosts) {
                                origin
                            } else {
                                "null"
                            }
                            val pre = "HTTP/1.1 204 No Content\r\n" +
                                "Access-Control-Allow-Origin: $allowOrigin\r\n" +
                                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: *\r\n" +
                                "Access-Control-Max-Age: 600\r\n" +
                                "Connection: close\r\n\r\n"
                            socket.getOutputStream().write(pre.toByteArray())
                            socket.close()
                            continue
                        }

                        when (val req = parseRequest(requestLine)) {
                            is CallbackRequest.Code -> {
                                val html = successPage(com.openminis.app.BuildConfig.APPLICATION_ID)
                                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\nConnection: close\r\n\r\n$html"
                                served[boundPort] = Served(android.os.SystemClock.uptimeMillis(), req.state)
                                socket.getOutputStream().write(response.toByteArray())
                                socket.close()
                                releaseKeepAlive("callback", token)
                                if (finish()) onCode(req.code, req.state)
                                stop()
                                return@Thread
                            }
                            is CallbackRequest.Error -> {
                                Log.w(TAG, "Callback error: ${req.error.error} description=${req.error.description != null}")
                                served[boundPort] = Served(android.os.SystemClock.uptimeMillis(), null)
                                socket.getOutputStream().write(
                                    errorPage(req.error.error, com.openminis.app.BuildConfig.APPLICATION_ID).toByteArray(Charsets.UTF_8),
                                )
                                socket.close()
                                releaseKeepAlive("error-callback", token)
                                if (finish()) onError(req.error)
                                stop()
                                return@Thread
                            }
                            CallbackRequest.Other -> {
                                socket.getOutputStream().write(
                                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                                )
                            }
                        }
                        socket.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling connection", e)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            } finally {
                listening.remove(boundPort, this)
                releaseKeepAlive("server-exit", token)
            }
        }.apply { name = com.openminis.app.diagnostics.ThreadNames.of("oauth-cb") }.start()
    }

    fun stop() {
        releaseKeepAlive("stopped")
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        if (finish()) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}

data class OAuthCallbackError(val error: String, val description: String?) {
    fun userMessage(context: android.content.Context): String = when {
        description != null -> context.getString(com.openminis.app.R.string.oauth_callback_error, description)
        error == "access_denied" -> context.getString(com.openminis.app.R.string.oauth_callback_access_denied)
        else -> context.getString(com.openminis.app.R.string.oauth_callback_error, error)
    }

    fun toException(context: android.content.Context): OAuthCallbackException =
        OAuthCallbackException(this, userMessage(context))
}

class OAuthCallbackException(val callbackError: OAuthCallbackError, message: String) : Exception(message)
