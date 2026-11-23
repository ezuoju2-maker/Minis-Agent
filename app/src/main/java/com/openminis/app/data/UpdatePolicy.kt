package com.openminis.app.data

object UpdatePolicy {

    enum class Channel(val id: String) {
        STABLE("stable"),
        BETA("beta"),
        ;

        companion object {
            fun parse(raw: String?): Channel = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: STABLE
        }
    }

    const val STABLE_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val BETA_INTERVAL_MS = 6L * 60 * 60 * 1000

    const val RETRY_AFTER_FAILURE_MS = 15L * 60 * 1000

    fun intervalMs(channel: Channel): Long = if (channel == Channel.BETA) BETA_INTERVAL_MS else STABLE_INTERVAL_MS


    fun compareVersionNames(a: String, b: String): Int {
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = (an.getOrNull(i) ?: 0).compareTo(bn.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        if (ap == null && bp == null) return 0
        if (ap == null) return 1
        if (bp == null) return -1
        val ax = ap.split('.')
        val bx = bp.split('.')
        for (i in 0 until maxOf(ax.size, bx.size)) {
            val x = ax.getOrNull(i) ?: return -1
            val y = bx.getOrNull(i) ?: return 1
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    private fun split(v: String): Pair<List<Int>, String?> {
        val t = v.trim().removePrefix("v").removePrefix("V")
        val dash = t.indexOf('-')
        val core = if (dash >= 0) t.substring(0, dash) else t
        val pre = if (dash >= 0) t.substring(dash + 1).ifEmpty { null } else null
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }

    fun isBetaName(v: String): Boolean = v.contains("-beta", ignoreCase = true)

    fun isBetaTag(tag: String): Boolean = tag.trim().startsWith("0-beta", ignoreCase = true)

    fun isLegacyPreview(tag: String): Boolean = tag.trim().endsWith("-preview", ignoreCase = true)

    data class ApkName(val version: String, val build: Int, val abi: String)

    private val APK_NAME = Regex("^MinisApp-(.+)-build(\\d+)-([\\w-]+)\\.apk$")

    fun parseApkName(name: String): ApkName? {
        val m = APK_NAME.matchEntire(name.trim()) ?: return null
        val build = m.groupValues[2].toIntOrNull() ?: return null
        val ver = m.groupValues[1].takeIf { it.isNotBlank() } ?: return null
        return ApkName(ver, build, m.groupValues[3])
    }

    fun buildFromApkName(name: String): Int? = parseApkName(name)?.build

    fun versionFromApkName(name: String): String? = parseApkName(name)?.version

    fun parseDigest(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        val hex = d.substring(7).lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    fun archiveRefusal(archivePackage: String?, archiveCode: Long?, ourPackage: String, installedCode: Long): String? = when {
        archivePackage == null || archiveCode == null -> "unreadable"
        archivePackage != ourPackage -> "package"
        archiveCode <= installedCode -> "not-newer"
        else -> null
    }


    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    data class Release(
        val tag: String,
        val name: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val body: String,
        val assets: List<Asset>,
        val htmlUrl: String = "",
    )

    data class Candidate(
        val tag: String,
        val prerelease: Boolean,
        val versionName: String,
        val versionCode: Int?,
        val apk: Asset,
        val changelog: String,
        val releaseName: String,
        val htmlUrl: String,
    )

    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun chooseApk(assets: List<Asset>, supportedAbis: List<String>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        fun abiOf(a: Asset): String? = parseApkName(a.name)?.abi
            ?: KNOWN_ABIS.firstOrNull { a.name.contains("-$it", ignoreCase = true) }
        for (abi in supportedAbis) {
            apks.filter { abiOf(it).equals(abi, ignoreCase = true) }
                .maxByOrNull { if (parseApkName(it.name) != null) 1 else 0 }
                ?.let { return it }
        }
        return apks.firstOrNull { abiOf(it) == null }
    }

    fun eligible(release: Release, channel: Channel): Boolean {
        if (release.draft || isLegacyPreview(release.tag)) return false
        return when (channel) {
            Channel.STABLE -> !release.prerelease && !isBetaTag(release.tag) && !isBetaName(release.tag)
            Channel.BETA -> true
        }
    }

    fun toCandidate(release: Release, supportedAbis: List<String>): Candidate? {
        val apk = chooseApk(release.assets, supportedAbis) ?: return null
        val parsed = parseApkName(apk.name)
        return Candidate(
            tag = release.tag,
            prerelease = release.prerelease,
            versionName = parsed?.version ?: release.tag.trim().removePrefix("v"),
            versionCode = parsed?.build,
            apk = apk,
            changelog = release.body,
            releaseName = release.name.ifBlank { release.tag },
            htmlUrl = release.htmlUrl,
        )
    }

    fun isNewer(c: Candidate, channel: Channel, localName: String, localCode: Int): Boolean = when {
        c.versionCode != null -> c.versionCode > localCode
        channel == Channel.STABLE -> compareVersionNames(c.versionName, localName) > 0
        else -> false
    }

    fun compareCandidates(a: Candidate, b: Candidate): Int =
        if (a.versionCode != null && b.versionCode != null) a.versionCode.compareTo(b.versionCode)
        else compareVersionNames(a.versionName, b.versionName)

    fun pickUpdate(candidates: List<Candidate>, channel: Channel, localName: String, localCode: Int): Candidate? =
        candidates.filter { isNewer(it, channel, localName, localCode) }.maxWithOrNull(::compareCandidates)


    data class ThrottleState(
        val lastSuccessAt: Long?,
        val lastAttemptAt: Long?,
        val cacheChannel: Channel?,
        val cacheVersionCode: Int?,
    )

    enum class AutoDecision {
        DISABLED,
        IN_FLIGHT,
        FRESH,
        BACKING_OFF,
        CHECK,
        INVALIDATE_AND_CHECK,
    }

    fun autoDecision(
        now: Long,
        state: ThrottleState,
        channel: Channel,
        localCode: Int,
        buildAllowsAuto: Boolean,
        userEnabled: Boolean,
        inFlight: Boolean,
    ): AutoDecision {
        if (!buildAllowsAuto || !userEnabled) return AutoDecision.DISABLED
        if (inFlight) return AutoDecision.IN_FLIGHT
        val cacheValid = state.cacheChannel == channel && state.cacheVersionCode == localCode
        if (!cacheValid && (state.cacheChannel != null || state.cacheVersionCode != null)) return AutoDecision.INVALIDATE_AND_CHECK
        val success = state.lastSuccessAt
        val expired = !cacheValid || success == null || now < success || now - success >= intervalMs(channel)
        if (!expired) return AutoDecision.FRESH
        val attempt = state.lastAttemptAt
        val failedRecently = attempt != null && (success == null || attempt > success) &&
            now >= attempt && now - attempt < RETRY_AFTER_FAILURE_MS
        return if (failedRecently) AutoDecision.BACKING_OFF else AutoDecision.CHECK
    }


    fun hasUpdateBadge(
        buildAllowsAuto: Boolean,
        userEnabled: Boolean,
        availableCode: Int?,
        localCode: Int,
        skippedCode: Int?,
        downloadedCode: Int?,
    ): Boolean {
        if (!buildAllowsAuto || !userEnabled || availableCode == null) return false
        return availableCode > localCode && availableCode != skippedCode && availableCode != downloadedCode
    }

    fun hasHomeBadge(hasUpdateBadge: Boolean, channel: Channel): Boolean = hasUpdateBadge && channel == Channel.BETA

    fun displayVersion(name: String, code: Int?): String = if (code != null) "$name (build $code)" else name
}
