package com.openminis.app.browser.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CdpClient(private val wsUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val connectLock = Mutex()
    private val sessionLock = Mutex()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var session: String? = null

    @Volatile
    private var opened = CompletableDeferred<Boolean>()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            val id = obj.optInt("id", -1)
            if (id >= 0) pending.remove(id)?.complete(obj)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            opened.complete(false)
            drain("CDP closed: $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.complete(false)
            drain(t.message ?: "CDP connection failed")
        }
    }

    val isConnected: Boolean
        get() = socket != null && opened.isCompleted && runCatching { opened.getCompleted() }.getOrDefault(false)

    val currentSession: String?
        get() = session

    suspend fun connect(): Boolean = connectLock.withLock {
        if (isConnected) return@withLock true
        socket?.cancel()
        socket = null
        session = null
        opened = CompletableDeferred()
        val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        socket = ws
        val ok = withTimeoutOrNull(10_000) { opened.await() } ?: false
        if (!ok) {
            runCatching { ws.cancel() }
            socket = null
        }
        ok
    }

    suspend fun ensureSession(): String {
        session?.let { return it }
        return sessionLock.withLock {
            session?.let { return@withLock it }
            val target = call("Target.createTarget", JSONObject().put("url", "about:blank"))
            val targetId = target.optString("targetId")
            if (targetId.isEmpty()) throw IOException("CDP did not return a target id")
            val attached = call(
                "Target.attachToTarget",
                JSONObject().put("targetId", targetId).put("flatten", true),
            )
            val sid = attached.optString("sessionId")
            if (sid.isEmpty()) throw IOException("CDP did not return a session id")
            session = sid
            sid
        }
    }

    suspend fun call(
        method: String,
        params: JSONObject? = null,
        sessionId: String? = null,
        timeoutMs: Long = 20_000,
    ): JSONObject {
        val ws = socket ?: throw IOException("CDP is not connected")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            if (!ws.send(CdpCodec.encodeCall(id, method, params, sessionId))) {
                throw IOException("CDP rejected $method")
            }
            val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw IOException("CDP $method timed out after ${timeoutMs}ms")
            return CdpCodec.resultOf(response.toString())
        } finally {
            pending.remove(id)
        }
    }

    suspend fun navigate(url: String, waitMs: Long = 12_000): JSONObject {
        val sid = ensureSession()
        val result = call("Page.navigate", JSONObject().put("url", url), sid)
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            val state = evaluateString("document.readyState", sid)
            if (state == "complete" || state == "interactive") return result
            kotlinx.coroutines.delay(200)
        }
        return result
    }

    suspend fun evaluateString(expression: String, sessionId: String? = null, awaitPromise: Boolean = false): String {
        val sid = sessionId ?: ensureSession()
        val result = call(
            "Runtime.evaluate",
            JSONObject()
                .put("expression", expression)
                .put("returnByValue", true)
                .put("awaitPromise", awaitPromise),
            sid,
        )
        CdpCodec.exceptionText(result)?.let { throw IllegalStateException(it) }
        return CdpCodec.stringValue(result) ?: ""
    }

    suspend fun screenshotPng(): ByteArray {
        val sid = ensureSession()
        val result = call("Page.captureScreenshot", JSONObject().put("format", "png"), sid, 30_000)
        val data = result.optString("data")
        if (data.isEmpty()) throw IOException("CDP returned an empty screenshot")
        return Base64.getDecoder().decode(data)
    }

    fun close() {
        runCatching { socket?.close(1000, "bye") }
        socket = null
        session = null
        drain("CDP closed by client")
    }

    private fun drain(reason: String) {
        pending.values.forEach { it.completeExceptionally(IOException(reason)) }
        pending.clear()
    }
}
