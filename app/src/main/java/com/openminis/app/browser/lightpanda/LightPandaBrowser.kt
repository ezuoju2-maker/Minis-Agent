package com.openminis.app.browser.lightpanda

import android.content.Context
import com.openminis.app.browser.cdp.CdpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LightPandaBrowser private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: LightPandaBrowser? = null

        fun getInstance(context: Context): LightPandaBrowser {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return LightPandaBrowser(context.applicationContext).also { instance = it }
            }
        }
    }

    private val server = LightPandaServer.getInstance(context)
    private val lock = Mutex()

    @Volatile
    private var client: CdpClient? = null

    @Volatile
    private var clientUrl: String = ""

    val isRunning: Boolean
        get() = server.isRunning

    fun status(): String {
        val connected = client?.isConnected == true
        return server.statusLine() + " · CDP " + if (connected) "connected" else "idle"
    }

    suspend fun client(): CdpClient = lock.withLock {
        val running = server.ensureRunning()
        val wsUrl = running.getOrElse { throw IllegalStateException(it.message ?: "Lightpanda failed to start") }
        val existing = client
        if (existing != null && existing.isConnected && clientUrl == wsUrl) return@withLock existing
        existing?.close()
        val fresh = CdpClient(wsUrl)
        if (!fresh.connect()) throw IllegalStateException("CDP handshake failed at $wsUrl")
        client = fresh
        clientUrl = wsUrl
        fresh
    }

    fun stop() {
        client?.close()
        client = null
        clientUrl = ""
        server.stop()
    }
}
