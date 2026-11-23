package com.openminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val DOWNLOAD_FILENAME = "minis-update.apk"
    private const val UPDATES_DIR = "updates"

    private const val PER_PAGE = 100
    private const val MAX_PAGES = 5

    sealed class CheckResult {
        data class UpdateAvailable(
            val tagName: String,
            val versionName: String,
            val versionCode: Int?,
            val releaseName: String,
            val changelog: String,
            val apkUrl: String,
            val apkSizeBytes: Long,
            val sha256: String?,
            val releaseUrl: String,
        ) : CheckResult()
        data object UpToDate : CheckResult()
        data object NoReleaseAvailable : CheckResult()
        data class NoApkAsset(val tagName: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
        data object Forbidden : CheckResult()
        data object NetworkUnreachable : CheckResult()
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val client: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, baseClient)

    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    val isChecking: Boolean get() = inFlight.get()

    private fun UpdateStateStore.Available.toResult() = CheckResult.UpdateAvailable(
        tagName = tag, versionName = versionName, versionCode = versionCode, releaseName = releaseName,
        changelog = changelog, apkUrl = apkUrl, apkSizeBytes = apkSize, sha256 = sha256, releaseUrl = releaseUrl,
    )

    private class HttpFailure(val result: CheckResult) : Exception()

    suspend fun check(context: Context, reason: String = "manual"): CheckResult = withContext(Dispatchers.IO) {
        UpdateStateStore.init(context)
        if (!inFlight.compareAndSet(false, true)) {
            AppLogger.info(TAG, "[UpdateCheck] $reason: a check is already running")
            while (inFlight.get()) kotlinx.coroutines.delay(100)
            return@withContext UpdateStateStore.available(context)?.toResult() ?: CheckResult.UpToDate
        }
        try {
            runCheck(context, reason)
        } finally {
            inFlight.set(false)
        }
    }

    private fun runCheck(context: Context, reason: String): CheckResult {
        val channel = UpdateStateStore.channel.value
        val localCode = BuildConfig.VERSION_CODE
        val localName = BuildConfig.VERSION_NAME
        val now = System.currentTimeMillis()
        UpdateStateStore.beginAttempt(context, now, channel, localCode)
        val etag = UpdateStateStore.etag(context)
        AppLogger.info(
            TAG,
            "[UpdateCheck] request reason=$reason channel=${channel.id} local=$localName ($localCode) repo=${BuildConfig.RELEASE_REPO} etag=${etag != null}",
        )
        return try {
            val page1 = fetchPage(1, etag)
            if (page1 == null) {
                AppLogger.info(TAG, "[UpdateCheck] 304 not modified")
                UpdateStateStore.markSuccess(context, now, etag, null, notModified = true)
                return UpdateStateStore.available(context)?.toResult() ?: CheckResult.UpToDate
            }
            val releases = page1.second.toMutableList()
            var page = 1
            var lastSize = page1.second.size
            while (lastSize >= PER_PAGE && page < MAX_PAGES) {
                page++
                val more = fetchPage(page, null)?.second ?: break
                releases += more
                lastSize = more.size
            }
            decide(context, now, page1.first, releases, channel, localName, localCode)
        } catch (f: HttpFailure) {
            f.result
        } catch (e: UnknownHostException) {
            AppLogger.error(TAG, "check failed: UnknownHostException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: ConnectException) {
            AppLogger.error(TAG, "check failed: ConnectException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: SocketTimeoutException) {
            AppLogger.error(TAG, "check failed: SocketTimeoutException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: IOException) {
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: Exception) {
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fetchPage(page: Int, etag: String?): Pair<String?, List<UpdatePolicy.Release>>? {
        val url = "https://api.github.com/repos/${BuildConfig.RELEASE_REPO}/releases?per_page=$PER_PAGE&page=$page"
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (etag != null) b.header("If-None-Match", etag)
        client.newCall(b.build()).execute().use { resp ->
            AppLogger.info(TAG, "GET releases page=$page HTTP ${resp.code}")
            if (resp.code == 304) return null
            if (resp.code == 404) return null to emptyList()
            if (resp.code == 403 || resp.code == 451) {
                AppLogger.warning(TAG, "GitHub API ${resp.code} — geo-block or rate-limit")
                throw HttpFailure(CheckResult.Forbidden)
            }
            if (!resp.isSuccessful) {
                AppLogger.warning(TAG, "GitHub API ${resp.code}")
                throw HttpFailure(CheckResult.Error("GitHub API ${resp.code}"))
            }
            val body = resp.body?.string() ?: throw HttpFailure(CheckResult.Error("empty body"))
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: throw HttpFailure(CheckResult.Error("bad JSON"))
            return resp.header("ETag") to parseReleases(arr)
        }
    }

    internal fun parseReleases(arr: JSONArray): List<UpdatePolicy.Release> {
        val out = mutableListOf<UpdatePolicy.Release>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val tag = r.optString("tag_name")
            if (tag.isEmpty()) continue
            val assets = mutableListOf<UpdatePolicy.Asset>()
            val ja = r.optJSONArray("assets")
            for (j in 0 until (ja?.length() ?: 0)) {
                val a = ja!!.optJSONObject(j) ?: continue
                val url = a.optString("browser_download_url").ifEmpty { null } ?: continue
                val sha = UpdatePolicy.parseDigest(a.optString("digest", "").ifEmpty { null })
                assets += UpdatePolicy.Asset(a.optString("name"), url, a.optLong("size", 0), sha)
            }
            out += UpdatePolicy.Release(
                tag = tag,
                name = r.optString("name").ifEmpty { tag },
                prerelease = r.optBoolean("prerelease", false),
                draft = r.optBoolean("draft", false),
                body = r.optString("body", ""),
                assets = assets,
                htmlUrl = r.optString("html_url", ""),
            )
        }
        return out
    }

    private fun decide(
        context: Context,
        now: Long,
        etag: String?,
        releases: List<UpdatePolicy.Release>,
        channel: UpdatePolicy.Channel,
        localName: String,
        localCode: Int,
    ): CheckResult {
        val abis = Build.SUPPORTED_ABIS.toList()
        val eligible = releases.filter { UpdatePolicy.eligible(it, channel) }
        AppLogger.info(TAG, "[UpdateCheck] releases=${releases.size} eligible=${eligible.size} channel=${channel.id}")
        if (eligible.isEmpty()) {
            UpdateStateStore.markSuccess(context, now, etag, null, notModified = false)
            return CheckResult.NoReleaseAvailable
        }
        val candidates = eligible.mapNotNull { UpdatePolicy.toCandidate(it, abis) }
        val chosen = UpdatePolicy.pickUpdate(candidates, channel, localName, localCode)
        if (chosen != null) {
            val available = UpdateStateStore.Available(
                tag = chosen.tag, versionName = chosen.versionName, versionCode = chosen.versionCode,
                apkUrl = chosen.apk.url, apkSize = chosen.apk.size, sha256 = chosen.apk.sha256,
                changelog = chosen.changelog, releaseName = chosen.releaseName, releaseUrl = chosen.htmlUrl,
            )
            UpdateStateStore.markSuccess(context, now, etag, available, notModified = false)
            AppLogger.info(
                TAG,
                "[UpdateCheck] update available: ${UpdatePolicy.displayVersion(localName, localCode)} -> " +
                    "${UpdatePolicy.displayVersion(chosen.versionName, chosen.versionCode)} tag=${chosen.tag} apk=${chosen.apk.name}",
            )
            return available.toResult()
        }
        UpdateStateStore.markSuccess(context, now, etag, null, notModified = false)
        val noApk = eligible.firstOrNull { r ->
            !r.prerelease && UpdatePolicy.compareVersionNames(r.tag, localName) > 0 &&
                UpdatePolicy.chooseApk(r.assets, abis) == null
        }
        if (noApk != null && channel == UpdatePolicy.Channel.STABLE) {
            AppLogger.info(TAG, "[UpdateCheck] ${noApk.tag} is newer but has no APK for $abis")
            return CheckResult.NoApkAsset(noApk.tag)
        }
        AppLogger.info(TAG, "[UpdateCheck] up to date: ${UpdatePolicy.displayVersion(localName, localCode)} channel=${channel.id}")
        return CheckResult.UpToDate
    }

    const val RELEASES_URL: String = "https://github.com/OpenMinis/OpenMinis/releases"

    suspend fun download(
        context: Context,
        url: String,
        versionName: String? = null,
        versionCode: Int? = null,
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val outDir = File(context.filesDir, UPDATES_DIR).apply { mkdirs() }
            val safeName = versionName
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "minis-$it.apk" }
                ?: DOWNLOAD_FILENAME
            val outFile = File(outDir, safeName)
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Error("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext DownloadResult.Error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var totalRead = 0L
                        var lastReported = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalRead += read
                            if (total > 0) {
                                val pct = ((totalRead * 100) / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            }
            AppLogger.info(TAG, "Downloaded ${outFile.length()} bytes to ${outFile.absolutePath}")
            val sha = runCatching { PendingUpdateStore.sha256(outFile) }
                .onFailure { AppLogger.warning(TAG, "sha256 compute failed: ${it.message}") }
                .getOrNull()
            if (expectedSha256 != null && !expectedSha256.equals(sha, ignoreCase = true)) {
                AppLogger.error(TAG, "[UpdateCheck] sha256 mismatch - discarding the download")
                outFile.delete()
                return@withContext DownloadResult.Error(context.getString(com.openminis.app.R.string.check_update_verify_failed))
            }
            if (versionName != null) {
                PendingUpdateStore.setPending(
                    context,
                    PendingUpdateStore.PendingUpdate(
                        targetVersionName = versionName,
                        targetVersionCode = versionCode,
                        apkPath = outFile.absolutePath,
                        apkSize = outFile.length(),
                        sha256 = sha,
                        downloadedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            DownloadResult.Success(outFile)
        } catch (e: Exception) {
            AppLogger.error(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun resumablePendingFile(context: Context): File? {
        val pending = PendingUpdateStore.getPending(context) ?: return null
        val stale = pending.targetVersionCode?.let { it <= BuildConfig.VERSION_CODE }
            ?: (UpdatePolicy.compareVersionNames(pending.targetVersionName, BuildConfig.VERSION_NAME) <= 0)
        if (stale) {
            AppLogger.info(TAG, "pending target ${pending.targetVersionName} <= local; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        val file = PendingUpdateStore.verify(pending)
        if (file == null) {
            AppLogger.info(TAG, "pending APK failed integrity; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        return file
    }

    fun archiveRefusal(context: Context, apk: File): String? {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            }
        }.getOrNull()
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
        val refusal = UpdatePolicy.archiveRefusal(info?.packageName, code, context.packageName, BuildConfig.VERSION_CODE.toLong())
        AppLogger.info(TAG, "[UpdateCheck] archive package=${info?.packageName} versionCode=$code refusal=$refusal")
        return refusal
    }

    fun installApk(context: Context, apk: File): Boolean {
        if (archiveRefusal(context, apk) != null) {
            PendingUpdateStore.clearPending(context)
            apk.delete()
            return false
        }
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.info(TAG, "installApk launched apk=${apk.absolutePath} size=${apk.length()}")
            PendingUpdateStore.clearPending(context)
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "installApk failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
