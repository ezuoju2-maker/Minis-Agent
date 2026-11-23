package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.WeakHashMap
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient

object NetworkHttp {

    private const val TAG = "Network"

    const val DEFAULT_TAG = "default"

    private data class Derived(val generation: Int, val client: OkHttpClient)

    private val cache = WeakHashMap<OkHttpClient, MutableMap<String, Derived>>()

    fun client(tag: String?, base: OkHttpClient): OkHttpClient {
        val t = tag ?: DEFAULT_TAG
        val snap = NetworkRuntime.snapshot
        val ep = snap.endpoint(t) ?: return base
        val httpPort = ep.httpPort ?: return base
        synchronized(cache) {
            val perBase = cache.getOrPut(base) { HashMap() }
            perBase[t]?.let { if (it.generation == snap.generation) return it.client }
            val derived = base.newBuilder()
                .proxySelector(EntrySelector(httpPort, fallbackDirect = snap.route(t) == NetworkRoute.Direct))
                .proxyAuthenticator(entryAuthenticator(t, ep.password))
                .build()
            perBase[t] = Derived(snap.generation, derived)
            AppLogger.info(TAG, "[Net][Session] okhttp tag=$t -> local entry (gen ${snap.generation})")
            return derived
        }
    }

    internal fun entryAuthenticator(tag: String, password: String) = Authenticator { _, response ->
        if (response.request.header("Proxy-Authorization") != null) {
            AppLogger.warning(TAG, "[Net][Session] local entry rejected tag=$tag (stale password?)")
            null
        } else {
            response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(tag, password))
                .build()
        }
    }

    internal class EntrySelector(private val httpPort: Int, private val fallbackDirect: Boolean) : java.net.ProxySelector() {
        private val entry = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
        @Volatile private var checkedAt = 0L
        @Volatile private var alive = true

        private fun entryAlive(): Boolean {
            val now = System.nanoTime()
            if (now - checkedAt < 2_000_000_000L) return alive
            checkedAt = now
            alive = runCatching {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", httpPort), 300) }
            }.isSuccess
            if (!alive) {
                AppLogger.warning(TAG, "[Net][Session] local entry 127.0.0.1:$httpPort is gone -> " +
                    (if (fallbackDirect) "direct route goes direct" else "proxy/tailscale route fails until it is back") + "; restarting it")
                NetworkHub.verifyEntry()
            }
            return alive
        }

        override fun select(uri: java.net.URI?): List<Proxy> = when {
            entryAlive() -> if (fallbackDirect) listOf(entry, Proxy.NO_PROXY) else listOf(entry)
            fallbackDirect -> listOf(Proxy.NO_PROXY)
            else -> listOf(entry)
        }

        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
            if ((sa as? InetSocketAddress)?.port == httpPort && ioe is java.net.ConnectException) {
                checkedAt = 0L
                NetworkHub.verifyEntry()
            }
        }
    }

    fun following(tag: String, base: OkHttpClient): OkHttpClient {
        val baseSelector = base.proxySelector
        val baseAuth = base.proxyAuthenticator
        return base.newBuilder()
            .proxySelector(FollowingSelector(tag, baseSelector))
            .proxyAuthenticator { route, response ->
                val ep = NetworkRuntime.snapshot.endpoint(tag)
                if (ep?.httpPort != null && route?.proxy?.type() == Proxy.Type.HTTP &&
                    (route.proxy.address() as? InetSocketAddress)?.port == ep.httpPort
                ) {
                    entryAuthenticator(tag, ep.password).authenticate(route, response)
                } else {
                    baseAuth.authenticate(route, response)
                }
            }
            .build()
    }

    internal class FollowingSelector(private val tag: String, private val base: java.net.ProxySelector) : java.net.ProxySelector() {
        @Volatile private var current: Pair<Int, EntrySelector>? = null

        private fun entry(): EntrySelector? {
            val snap = NetworkRuntime.snapshot
            val ep = snap.endpoint(tag) ?: return null
            val httpPort = ep.httpPort ?: return null
            current?.let { (gen, sel) -> if (gen == snap.generation) return sel }
            return EntrySelector(httpPort, fallbackDirect = snap.route(tag) == NetworkRoute.Direct)
                .also { current = snap.generation to it }
        }

        override fun select(uri: java.net.URI?): List<Proxy> = entry()?.select(uri) ?: base.select(uri)

        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
            entry()?.connectFailed(uri, sa, ioe) ?: base.connectFailed(uri, sa, ioe)
        }
    }

    internal fun clearCacheForTest() = synchronized(cache) { cache.clear() }
}

interface NetworkTagged {
    var netTag: String?
}
