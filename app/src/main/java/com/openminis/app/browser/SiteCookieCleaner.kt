package com.openminis.app.browser

import android.webkit.CookieManager
import com.openminis.app.logging.AppLogger

object SiteCookieCleaner {

    private const val TAG = "SiteCookieCleaner"
    private const val EXPIRED = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"

    data class Result(val host: String, val found: Set<String>, val remaining: Set<String>) {
        val cleared: Boolean get() = remaining.isEmpty()
    }

    internal fun cookieNames(header: String?): Set<String> {
        if (header.isNullOrBlank()) return emptySet()
        return header.split(';')
            .map { it.trim().substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    internal fun hostsFor(domain: String): List<String> {
        val host = domain.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return emptyList()
        val bare = host.removePrefix("www.")
        return if (bare != host && bare.contains('.')) listOf(host, bare) else listOf(host)
    }

    internal fun domainAttributes(host: String): List<String> {
        val labels = host.split('.')
        if (labels.size < 2 || host.all { it.isDigit() || it == '.' }) return listOf(host)
        return (0..labels.size - 2).map { labels.drop(it).joinToString(".") }
    }

    internal fun expiryCookies(name: String, host: String): List<String> {
        val scopes = listOf("") + domainAttributes(host).map { "; Domain=$it" }
        return scopes.flatMap { scope ->
            listOf(
                "$name=; $EXPIRED; Path=/$scope",
                "$name=; $EXPIRED; Path=/$scope; Secure",
            )
        }
    }

    private fun namesAt(cm: CookieManager, host: String): Set<String> =
        cookieNames(cm.getCookie("https://$host")) + cookieNames(cm.getCookie("http://$host"))

    fun cookieNamesFor(domain: String, cm: CookieManager = CookieManager.getInstance()): Set<String> =
        hostsFor(domain).flatMap { namesAt(cm, it) }.toSet()

    fun hasCookiesFor(domain: String, cm: CookieManager = CookieManager.getInstance()): Boolean =
        cookieNamesFor(domain, cm).isNotEmpty()

    internal fun sitesWithCookies(historyDomains: List<String>, probe: (String) -> Boolean): List<String> =
        historyDomains.filter(probe)

    data class SectionState(val hasCookies: Boolean, val domains: List<String>)

    fun loadSection(historyDomains: List<String>, cm: CookieManager = CookieManager.getInstance()): SectionState {
        val hasAny = cm.hasCookies()
        val domains = if (hasAny) sitesWithCookies(historyDomains) { hasCookiesFor(it, cm) } else emptyList()
        return SectionState(hasAny, domains)
    }

    fun clear(domain: String, cm: CookieManager = CookieManager.getInstance()): Result {
        val hosts = hostsFor(domain)
        val found = linkedSetOf<String>()
        for (host in hosts) {
            val names = namesAt(cm, host)
            found += names
            for (name in names) {
                for (value in expiryCookies(name, host)) {
                    cm.setCookie("https://$host", value)
                    cm.setCookie("http://$host", value)
                }
            }
        }
        cm.flush()
        val remaining = cookieNamesFor(domain, cm)
        AppLogger.info(TAG, "clear $domain hosts=$hosts found=${found.size} remaining=${remaining.size} $remaining")
        return Result(domain, found, remaining)
    }
}
