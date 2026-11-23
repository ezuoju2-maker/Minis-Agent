package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NetworkAlertEvent(
    val subject: Subject,
    val recovered: Boolean = false,
    val failures: Int = 0,
    val stage: String = "",
    val failureClass: String = "",
    val detail: String = "",
) {
    sealed class Subject {
        data class Proxy(val id: String) : Subject()
        object Tailscale : Subject()
    }

    companion object {
        fun parse(line: String): NetworkAlertEvent? {
            val alert = line.indexOf("[Net][Alert] ")
            val recoveredAt = line.indexOf("[Net][Recovered] ")
            val (recovered, rest) = when {
                alert >= 0 -> false to line.substring(alert + "[Net][Alert] ".length)
                recoveredAt >= 0 -> true to line.substring(recoveredAt + "[Net][Recovered] ".length)
                else -> return null
            }
            val fields = HashMap<String, String>()
            var detail = ""
            var cursor = rest
            while (cursor.isNotEmpty()) {
                if (cursor.startsWith("err=")) {
                    detail = cursor.removePrefix("err=")
                    break
                }
                val token = cursor.substringBefore(' ')
                cursor = cursor.removePrefix(token).trimStart(' ')
                val eq = token.indexOf('=')
                if (eq > 0) fields[token.substring(0, eq)] = token.substring(eq + 1)
            }
            val subject = when (fields["exit"]) {
                "proxy" -> Subject.Proxy(fields["id"]?.takeIf { it.isNotEmpty() } ?: return null)
                "tailscale" -> Subject.Tailscale
                else -> return null
            }
            return NetworkAlertEvent(
                subject = subject,
                recovered = recovered,
                failures = fields["failures"]?.toIntOrNull() ?: 0,
                stage = fields["stage"].orEmpty(),
                failureClass = fields["class"].orEmpty(),
                detail = detail,
            )
        }
    }
}

object NetworkAlertCenter {

    private const val TAG = "Network"
    const val SNOOZE_MS = 30 * 60 * 1000L

    private val _banner = MutableStateFlow<NetworkAlertEvent?>(null)
    val banner: StateFlow<NetworkAlertEvent?> = _banner.asStateFlow()

    private val _failing = MutableStateFlow<Map<NetworkAlertEvent.Subject, NetworkAlertEvent>>(emptyMap())
    val failing: StateFlow<Map<NetworkAlertEvent.Subject, NetworkAlertEvent>> = _failing.asStateFlow()

    @Volatile
    var isOnline: () -> Boolean = { true }

    @Volatile
    var now: () -> Long = System::currentTimeMillis

    private val snoozedUntil = HashMap<NetworkAlertEvent.Subject, Long>()

    fun ingest(line: String) {
        if (!line.contains("[Net][Alert]") && !line.contains("[Net][Recovered]")) return
        NetworkAlertEvent.parse(line)?.let { handle(it, NetworkHub.settings.value) }
    }

    @Synchronized
    internal fun handle(e: NetworkAlertEvent, s: NetworkSettings) {
        if (e.recovered) {
            _failing.update { it - e.subject }
            if (_banner.value?.subject == e.subject) _banner.value = null
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} works again - banner cleared")
            return
        }
        if (!inUse(e.subject, s)) return
        _failing.update { it + (e.subject to e) }
        if (!isOnline()) {
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x while the device is offline - no banner")
            return
        }
        if ((snoozedUntil[e.subject] ?: 0L) > now()) {
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x - banner dismissed earlier, not shown again yet")
            return
        }
        AppLogger.warning(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x in a row " +
            "(stage=${e.stage} class=${e.failureClass}) - showing banner")
        _banner.value = e
    }

    @Synchronized
    fun dismissBanner() {
        val b = _banner.value ?: return
        snoozedUntil[b.subject] = now() + SNOOZE_MS
        _banner.value = null
    }

    @Synchronized
    fun takeBanner(): NetworkAlertEvent? = _banner.value.also { _banner.value = null }

    @Synchronized
    fun prune(s: NetworkSettings) {
        _failing.update { m -> m.filterKeys { inUse(it, s) } }
        _banner.value?.let { if (!inUse(it.subject, s)) _banner.value = null }
    }

    private fun inUse(subject: NetworkAlertEvent.Subject, s: NetworkSettings): Boolean = when (subject) {
        is NetworkAlertEvent.Subject.Proxy -> !s.forceDirect && s.proxies.any { it.id == subject.id }
        NetworkAlertEvent.Subject.Tailscale -> s.nodeWanted
    }

    private fun describe(subject: NetworkAlertEvent.Subject) = when (subject) {
        is NetworkAlertEvent.Subject.Proxy -> "proxy ${subject.id.take(8)}"
        NetworkAlertEvent.Subject.Tailscale -> "tailscale"
    }

    @Synchronized
    internal fun resetForTest() {
        _banner.value = null
        _failing.value = emptyMap()
        snoozedUntil.clear()
        isOnline = { true }
        now = System::currentTimeMillis
    }
}
