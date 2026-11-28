package com.openminis.app.browser.lightpanda

import android.content.Context
import android.os.Build
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object LightPandaInstaller {

    const val VERSION = "1.0.6"
    const val GUEST_PATH = "/usr/local/bin/lightpanda"
    const val DOWNLOAD_URL =
        "https://github.com/lightpanda-io/browser/releases/download/1.0.6/lightpanda-aarch64-linux"
    const val PROJECT_URL = "https://github.com/lightpanda-io/browser"

    private const val EXPECTED_BYTES = 74_092_616L
    private const val EXPECTED_SHA256 =
        "26e0042a52f493d8cc1b04faef42aea9b46bef9ccfcc86170098bd330c19a890"
    private const val MARKER = ".lightpanda"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun binary(context: Context): File =
        File(RootfsManager.getInstance(context).rootfsDir, "usr/local/bin/lightpanda")

    fun isInstalled(context: Context): Boolean {
        val rootfs = RootfsManager.getInstance(context)
        if (!rootfs.isInstalled) return false
        val file = binary(context)
        if (!file.isFile || file.length() != EXPECTED_BYTES) return false
        val marker = File(rootfs.rootfsDir, MARKER)
        return marker.isFile && marker.readText().trim() == VERSION
    }

    suspend fun installIfNeeded(context: Context): String? = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext null
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            return@withContext "Lightpanda publishes an arm64 Linux build only; this device reports " +
                Build.SUPPORTED_ABIS.joinToString("/")
        }
        val rootfs = RootfsManager.getInstance(context)
        if (!rootfs.isInstalled) {
            return@withContext "Ubuntu sandbox is not installed yet. Install it first, then retry."
        }

        val target = binary(context)
        val tmp = File(target.parentFile, ".lightpanda.tmp")
        target.parentFile?.mkdirs()

        val failure = runCatching {
            val request = Request.Builder().url(DOWNLOAD_URL).header("User-Agent", "minis-lightpanda").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching "Download failed: HTTP ${response.code} from $DOWNLOAD_URL"
                }
                val body = response.body ?: return@runCatching "Download failed: empty body"
                writeVerified(body.byteStream(), tmp)
            }
            null
        }.getOrElse { e ->
            runCatching { tmp.delete() }
            "Download failed: ${e.message ?: e::class.java.simpleName}"
        }
        if (failure != null) return@withContext failure

        val verifyError = runCatching {
            if (tmp.length() != EXPECTED_BYTES) return@runCatching "size ${tmp.length()} != $EXPECTED_BYTES"
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { String.format("%02x", it) }
            if (hex != EXPECTED_SHA256) return@runCatching "checksum $hex != $EXPECTED_SHA256"
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return@runCatching "could not move the binary into place"
            target.setExecutable(true, false)
            target.setReadable(true, false)
            File(rootfs.rootfsDir, MARKER).writeText(VERSION)
            null
        }.getOrElse { e ->
            "Install failed: ${e.message ?: e::class.java.simpleName}"
        }
        runCatching { tmp.delete() }
        verifyError
    }

    private fun writeVerified(input: InputStream, target: File) {
        FileOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
    }
}
