package com.openminis.app.network

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkSettingsTest {

    @Before fun reset() = NetworkRuntime.resetForTest()

    @After fun after() = NetworkRuntime.resetForTest()

    @Test
    fun defaultsMatchIosAndNeedNothing() {
        val s = NetworkSettings()
        assertFalse(s.tailscaleEnabled)
        assertFalse(s.acceptRoutes)
        assertEquals("direct", s.defaultRoute)
        assertFalse(s.needsEntry)
        assertFalse(s.nodeWanted)
        assertTrue(s.isUnconfigured)
        for (scope in NetworkScope.entries) assertEquals(NetworkRoute.FollowDefault, s.route(scope))
    }

    @Test
    fun decodesIosJsonAndPartialOrBrokenData() {
        val ios = """{"tailscaleEnabled":true,"hostname":"","acceptRoutes":false,"defaultRoute":"proxy:A1",
            "scopes":{"shell":"direct","browser":"tailscale"},
            "proxies":[{"id":"A1","name":"Mac","kind":"http","host":"10.1.1.60","port":18888,"username":"u","bypass":["*.apple.com"]}],
            "providerRoutes":{"P9":"tailscale"}}"""
        val s = NetworkSettingsStore.decode(ios)
        assertTrue(s.tailscaleEnabled)
        assertEquals(NetworkRoute.Proxy("A1"), NetworkRoute.parse(s.defaultRoute))
        assertEquals("http", s.proxies.single().kind)
        assertEquals(listOf("*.apple.com"), s.proxies.single().bypass)
        assertEquals(NetworkRoute.Tailscale, s.providerRoute("P9"))
        assertFalse(s.forceDirect)
        assertEquals(NetworkSettings(), NetworkSettingsStore.decode("""{"future":1}"""))
        assertEquals(NetworkSettings(), NetworkSettingsStore.decode("not json"))
        assertEquals(NetworkSettings(), NetworkSettingsStore.decode(null))
        assertEquals(s, NetworkSettingsStore.decode(NetworkSettingsStore.encode(s)))
    }

    @Test
    fun routeRawValuesAreIosStrings() {
        assertEquals("default", NetworkRoute.FollowDefault.raw)
        assertEquals("direct", NetworkRoute.Direct.raw)
        assertEquals("tailscale", NetworkRoute.Tailscale.raw)
        assertEquals("proxy:x", NetworkRoute.Proxy("x").raw)
        for (raw in listOf("default", "direct", "tailscale", "proxy:x")) assertEquals(raw, NetworkRoute.parse(raw).raw)
        assertEquals(NetworkRoute.FollowDefault, NetworkRoute.parse("bogus"))
        assertEquals("prov:abc", NetworkRuntime.providerTag("abc"))
        val tags = NetworkSettings(providerRoutes = mapOf("abc" to "direct")).tagRoutes
        assertEquals(setOf("shell", "browser", "mcp", "backup", "prov:abc"), tags.keys)
    }

    @Test
    fun needsEntryOnlyWhenSomethingIsRouted() {
        assertTrue(NetworkSettings(tailscaleEnabled = true).needsEntry)
        assertTrue(NetworkSettings(defaultRoute = "tailscale").needsEntry)
        assertTrue(NetworkSettings(scopes = mapOf("shell" to "proxy:a")).needsEntry)
        assertTrue(NetworkSettings(providerRoutes = mapOf("p" to "tailscale")).needsEntry)
        assertFalse(NetworkSettings(scopes = mapOf("shell" to "direct", "mcp" to "default")).needsEntry)
        val all = NetworkSettings(tailscaleEnabled = true, defaultRoute = "proxy:a", forceDirect = true)
        assertFalse(all.needsEntry)
        assertFalse(all.nodeWanted)
    }

    @Test
    fun clientsStayDirectUnlessTheEntryIsUpAndARouteNeedsIt() {
        val tags = listOf("default", "shell", "browser", "mcp", "backup", "prov:x")
        for (t in tags) assertNull(NetworkRuntime.endpoint(t))
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 4000, password = "pw", defaultRoute = "direct"))
        for (t in tags) assertNull(t, NetworkRuntime.endpoint(t))
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 4000, password = "pw", defaultRoute = "direct",
            tagRoutes = mapOf("shell" to "proxy:a", "browser" to "default")))
        assertNotNull(NetworkRuntime.endpoint("shell"))
        assertNull(NetworkRuntime.endpoint("browser"))
        assertNull(NetworkRuntime.endpoint("default"))
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 4000, password = "pw", tailscaleEnabled = true))
        for (t in tags) assertEquals(4000, NetworkRuntime.endpoint(t)?.port)
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = null, password = "", tailscaleEnabled = true, defaultRoute = "proxy:a"))
        for (t in tags) assertNull(NetworkRuntime.endpoint(t))
    }

    @Test
    fun publishBumpsTheGenerationOnlyOnChange() {
        val a = NetworkRuntime.Snapshot(port = 1, password = "p")
        NetworkRuntime.publish(a)
        val g = NetworkRuntime.snapshot.generation
        NetworkRuntime.publish(a)
        assertEquals(g, NetworkRuntime.snapshot.generation)
        NetworkRuntime.publish(a.copy(port = 2))
        assertEquals(g + 1, NetworkRuntime.snapshot.generation)
    }

    @Test
    fun socksUrlEncodesTheCredentialsAndNeverPrintsThem() {
        val ep = SocksEndpoint(1234, "prov:a b", "p@ss/word")
        assertEquals("socks5h://prov%3Aa%20b:p%40ss%2Fword@127.0.0.1:1234", ep.url)
        assertFalse(ep.toString().contains("p@ss"))
        assertFalse(NetworkRuntime.Snapshot(port = 1, password = "secret").toString().contains("secret"))
    }

    @Test
    fun deletingAProxyFallsItsRoutesBack() {
        val s = NetworkSettings(
            defaultRoute = "proxy:a",
            scopes = mapOf("shell" to "proxy:a", "mcp" to "proxy:b"),
            proxies = listOf(ProxyServer(id = "a"), ProxyServer(id = "b")),
            providerRoutes = mapOf("p1" to "proxy:a", "p2" to "tailscale"),
        ).withoutProxy("a")
        assertEquals("direct", s.defaultRoute)
        assertEquals(mapOf("shell" to "default", "mcp" to "proxy:b"), s.scopes)
        assertEquals(mapOf("p2" to "tailscale"), s.providerRoutes)
        assertEquals(listOf("b"), s.proxies.map { it.id })
    }

    @Test
    fun anUnconfiguredAppNeverCallsIntoGo() {
        val hub = File("src/main/java/com/openminis/app/network/NetworkHub.kt").readText()
        val init = hub.substringAfter("fun init(context: Context)").substringBefore("\n    }")
        assertTrue("init must only apply when something is on", init.contains("if (s.nodeWanted || s.needsEntry)"))
        assertTrue(hub.contains("} else if (nodeTouched) {"))
        assertTrue(hub.contains("} else if (entryTouched) {"))
        val diag = File("src/main/java/com/openminis/app/network/NetworkDiagnostics.kt").readText()
        val early = diag.indexOf("if (!s.needsEntry && !s.nodeWanted)")
        assertTrue(early >= 0 && early < diag.indexOf("TailscaleNode.health()"))
    }

    @Test
    fun exitNodePrivacyDefaultsOffAndDecodesIosJson() {
        val d = NetworkSettings()
        assertTrue(!d.exitNodeDNS && !d.exitNodeFailClosed)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString(NetworkSettings.serializer(), """{"tailscaleEnabled":true,"exitNodeDNS":true,"exitNodeFailClosed":true}""")
        assertTrue(s.exitNodeDNS && s.exitNodeFailClosed)
        val hub = File("src/main/java/com/openminis/app/network/NetworkHub.kt").readText()
        assertTrue(hub.contains("if (s.exitNodeDNS) put(\"exitNodeDNS\", true)"))
    }
}
