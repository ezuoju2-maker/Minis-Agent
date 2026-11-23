package com.openminis.app.network

import android.net.Uri
import com.openminis.app.ProductionSources
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkSettingsSectionTest {

    private fun route(link: String) =
        (DeepLinkHandler.parse(Uri.parse(link)) as DeepLinkAction.OpenSettingsScreen).route

    @Test
    fun eachNetworkPageHasItsOwnDeepLink() {
        assertEquals(Routes.NETWORK, route("minis://settings/network"))
        assertEquals(Routes.NETWORK_TAILSCALE, route("minis://settings/tailscale"))
        for (p in listOf("proxy", "proxies")) assertEquals(p, Routes.NETWORK_PROXIES, route("minis://settings/$p"))
        for (p in listOf("dns", "container-dns", "container_dns")) assertEquals(p, Routes.CONTAINER_DNS, route("minis://settings/$p"))
        assertEquals(Routes.ROOTFS_MANAGEMENT, route("minis://settings/rootfs"))
        assertTrue(ProductionSources.read("ui/sandbox/RootfsManagementScreen.kt").contains("ContainerNetworkSection()"))
    }

    @Test
    fun theNetworkSectionSitsBelowStorageWithFourRows() {
        val s = ProductionSources.read("ui/settings/SettingsScreen.kt")
        val storage = s.indexOf("// -- Storage --")
        val network = s.indexOf("// -- Network --")
        val permissions = s.indexOf("// -- Permissions --")
        assertTrue(storage in 0 until network && network < permissions)
        val section = s.substring(network, permissions)
        for (cb in listOf("onNetworkClick", "onTailscaleClick", "onProxiesClick", "onContainerDnsClick")) {
            assertTrue(cb, section.contains("onClick = $cb"))
        }
        assertEquals(1, s.split("onClick = onNetworkClick").size - 1)
        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        for (r in listOf("NETWORK_TAILSCALE", "NETWORK_PROXIES", "CONTAINER_DNS")) assertTrue(r, nav.contains("composable(Routes.$r)"))
    }

    @Test
    fun theRoutingPageListsProvidersAndEachPageShowsOnlyItsSections() {
        val s = ProductionSources.read("ui/settings/NetworkSettingsScreen.kt")
        assertTrue(s.contains("if (page == NetworkPage.ROUTING && providers.isNotEmpty()) SettingsSection("))
        assertTrue(s.contains("is RoutePickerTarget.Provider -> NetworkHub.setProviderRoute(r, target.id)"))
        assertTrue(s.contains("if (page == NetworkPage.TAILSCALE) SettingsSection("))
        assertTrue(s.contains("if (page == NetworkPage.PROXIES) SettingsSection("))
        val paused = s.substringAfter("SettingsScaffold(title = title").substringBefore("// Default route")
        assertTrue(paused.contains("if (settings.forceDirect)"))
        assertFalse(paused.contains("page =="))
        assertTrue(ProductionSources.read("ui/navigation/AppNavigation.kt")
            .contains("providers = providerRepository.instances.map { com.openminis.app.ui.settings.NetworkProviderRow(it.id, it.label) }"))
    }

    @Test
    fun onlyRoutedProvidersAreListedAndOthersAreAddedInBulk() {
        val s = ProductionSources.read("ui/settings/NetworkSettingsScreen.kt")
        assertTrue(s.contains("val routed = providers.filter { settings.providerRoutes.containsKey(it.id) }"))
        assertTrue(s.contains("routed.forEachIndexed { i, p ->"))
        assertTrue(s.contains("if (unrouted.isNotEmpty()) SettingsRow("))
        assertTrue(s.contains("candidates = providers.filter { !settings.providerRoutes.containsKey(it.id) }"))
        assertTrue(s.contains("NetworkHub.update { s -> s.copy(providerRoutes = s.providerRoutes + ids.associateWith { r.raw }) }"))
        val dialog = s.substringAfter("private fun AddProviderRoutesSheet(").substringBefore("\n}\n")
        assertFalse(dialog.contains("FollowDefault"))
        assertTrue(dialog.contains("enabled = selected.isNotEmpty()"))
        assertTrue(dialog.contains("ModalBottomSheet("))
        assertFalse(dialog.contains("AlertDialog("))
        assertTrue(s.contains("footer = stringResource(R.string.network_providers_unlisted_footer)"))
    }

    @Test
    fun aProviderPageHasItsOwnNetworkRow() {
        val detail = ProductionSources.read("ui/settings/ProviderDetailScreen.kt")
        assertTrue(detail.indexOf("// ─── Custom Base URL") < detail.indexOf("ProviderNetworkRouteSection(instance.id)"))
        val screen = ProductionSources.read("ui/settings/NetworkSettingsScreen.kt")
        val section = screen.substringAfter("internal fun ProviderNetworkRouteSection(").substringBefore("\n}\n")
        assertTrue(section.contains("NetworkHub.setProviderRoute(r, instanceId)"))
        assertTrue(section.contains("includeFollowDefault = true"))
    }
}
