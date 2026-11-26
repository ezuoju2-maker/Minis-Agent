package com.openminis.app.hostfrail

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class IntegrityRecord(
    val versionCode: Long,
    val signingDigest: String,
    val sourceDigest: String,
    val assetDigests: Map<String, String>,
    val bootMarker: String,
    val recordedAtEpochMs: Long,
)

data class DriftReport(
    val signingDrifted: Boolean,
    val sourceDrifted: Boolean,
    val assetDrift: List<String>,
    val bootDrifted: Boolean,
    val versionRegressed: Boolean,
) {
    val drifted: Boolean
        get() = signingDrifted || sourceDrifted || assetDrift.isNotEmpty() || bootDrifted || versionRegressed

    fun summary(): String = buildString {
        append("signing=").append(signingDrifted)
        append(" source=").append(sourceDrifted)
        append(" assets=").append(assetDrift.size)
        append(" boot=").append(bootDrifted)
        append(" versionRegressed=").append(versionRegressed)
        if (assetDrift.isNotEmpty()) {
            append(" changed=[")
            append(assetDrift.take(8).joinToString(","))
            if (assetDrift.size > 8) append(",+").append(assetDrift.size - 8)
            append(']')
        }
    }
}

object IntegrityLedger {

    private const val PREFS = "minis_host_origin"
    private const val K_VERSION = "baseline_version_code"
    private const val K_SIGNING = "baseline_signing_digest"
    private const val K_SOURCE = "baseline_source_digest"
    private const val K_ASSETS = "baseline_asset_digests"
    private const val K_BOOT = "baseline_boot_marker"
    private const val K_AT = "baseline_recorded_at"

    private const val SEP = "\u001f"

    private val TRUSTED_ASSET_PREFIXES: List<String> = listOf(
        "bashism/", "jieba/", "katex/", "models-dev-api.json",
    )

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun digestOf(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    fun digestOf(text: String): String = digestOf(text.toByteArray(StandardCharsets.UTF_8))

    fun combine(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) {
            val b = p.toByteArray(StandardCharsets.UTF_8)
            md.update(b.size.ushr(24).toByte())
            md.update(b.size.ushr(16).toByte())
            md.update(b.size.ushr(8).toByte())
            md.update(b.size.toByte())
            md.update(b)
        }
        return md.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    fun assetInventory(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val base = context.applicationContext.assets ?: return out
        for (prefix in TRUSTED_ASSET_PREFIXES) {
            enqueueAssets(base, prefix.trimEnd('/'), out)
        }
        return out
    }

    private fun enqueueAssets(
        assets: android.content.res.AssetManager,
        dir: String,
        sink: MutableMap<String, String>,
    ) {
        val queue = ArrayDeque<String>()
        queue.add(dir)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val children = runCatching { assets.list(cur) ?: emptyArray() }.getOrNull() ?: continue
            if (children.isEmpty()) {
                val bytes = runCatching {
                    assets.open(cur).use { it.readBytes() }
                }.getOrNull() ?: continue
                sink[cur] = digestOf(bytes)
                continue
            }
            for (c in children) {
                val child = if (cur.isEmpty()) c else "$cur/$c"
                val nested = runCatching { assets.list(child) }.getOrNull
                if (nested == null) {
                    val bytes = runCatching { assets.open(child).use { it.readBytes() } }.getOrNull()
                    if (bytes != null) sink[child] = digestOf(bytes)
                } else {
                    queue.add(child)
                }
            }
        }
    }

    fun recorded(context: Context): IntegrityRecord? {
        val p = prefs(context)
        val signing = p.getString(K_SIGNING, null) ?: return null
        val source = p.getString(K_SOURCE, null) ?: return null
        val assetsRaw = p.getString(K_ASSETS, "") ?: ""
        val assets = LinkedHashMap<String, String>()
        if (assetsRaw.isNotEmpty()) {
            for (entry in assetsRaw.split(SEP)) {
                val i = entry.indexOf('=')
                if (i > 0) assets[entry.substring(0, i)] = entry.substring(i + 1)
            }
        }
        return IntegrityRecord(
            versionCode = p.getLong(K_VERSION, -1L),
            signingDigest = signing,
            sourceDigest = source,
            assetDigests = assets,
            bootMarker = p.getString(K_BOOT, "") ?: "",
            recordedAtEpochMs = p.getLong(K_AT, 0L),
        )
    }

    fun record(context: Context, sourceDigest: String, versionCode: Long) {
        val signing = HostOrigin.signingDigest(context) ?: return
        val assets = assetInventory(context)
        prefs(context).edit()
            .putLong(K_VERSION, versionCode)
            .putString(K_SIGNING, signing)
            .putString(K_SOURCE, sourceDigest)
            .putString(K_ASSETS, assets.entries.joinToString(SEP) { "${it.key}=${it.value}" })
            .putString(K_BOOT, EnvironmentSensor.bootMarker())
            .putLong(K_AT, System.currentTimeMillis())
            .apply()
    }

    fun hasBaseline(context: Context): Boolean = prefs(context).contains(K_SIGNING)

    fun verify(context: Context, sourceDigest: String, versionCode: Long): DriftReport {
        val base = recorded(context) ?: return DriftReport(
            signingDrifted = false, sourceDrifted = false, assetDrift = emptyList(),
            bootDrifted = false, versionRegressed = false,
        )
        val liveSigning = HostOrigin.signingDigest(context)
        val liveAssets = assetInventory(context)
        val assetDrift = ArrayList<String>()
        for ((k, v) in liveAssets) {
            val expected = base.assetDigests[k]
            when {
                expected == null -> assetDrift.add("+$k")
                !expected.equals(v, ignoreCase = true) -> assetDrift.add("~$k")
            }
        }
        for (k in base.assetDigests.keys) {
            if (!liveAssets.containsKey(k)) assetDrift.add("-$k")
        }
        return DriftReport(
            signingDrifted = liveSigning != null &&
                !liveSigning.equals(base.signingDigest, ignoreCase = true),
            sourceDrifted = !sourceDigest.equals(base.sourceDigest, ignoreCase = true),
            assetDrift = assetDrift.sorted(),
            bootDrifted = !EnvironmentSensor.bootMarker().equals(base.bootMarker, ignoreCase = true),
            versionRegressed = versionCode in 1 until base.versionCode,
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun baselineSummary(context: Context): String {
        val b = recorded(context) ?: return "baseline=absent"
        return buildString {
            append("baseline=present")
            append(" version=").append(b.versionCode)
            append(" signing=").append(b.signingDigest.take(12))
            append(" source=").append(b.sourceDigest.take(12))
            append(" assets=").append(b.assetDigests.size)
            append(" boot=").append(b.bootMarker.take(24))
            append(" at=").append(b.recordedAtEpochMs)
        }
    }
}
