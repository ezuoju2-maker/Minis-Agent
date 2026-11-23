package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object PendingUpdateStore {

    private const val TAG = "PendingUpdateStore"
    private const val PREFS = "pending_update"
    private const val KEY = "pending"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    data class PendingUpdate(
        val targetVersionName: String,
        val targetVersionCode: Int? = null,
        val apkPath: String,
        val apkSize: Long,
        val sha256: String?,
        val downloadedAtMs: Long,
    )

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        init(context)
        return prefs!!
    }

    fun setPending(context: Context, pending: PendingUpdate) {
        val json = JSONObject().apply {
            put("targetVersionName", pending.targetVersionName)
            if (pending.targetVersionCode != null) put("targetVersionCode", pending.targetVersionCode)
            put("apkPath", pending.apkPath)
            put("apkSize", pending.apkSize)
            if (pending.sha256 != null) put("sha256", pending.sha256) else put("sha256", JSONObject.NULL)
            put("downloadedAtMs", pending.downloadedAtMs)
        }
        requirePrefs(context).edit().putString(KEY, json.toString()).apply()
        AppLogger.info(
            TAG,
            "setPending version=${pending.targetVersionName} size=${pending.apkSize} sha256=${pending.sha256 != null}",
        )
    }

    fun getPending(context: Context): PendingUpdate? {
        val p = requirePrefs(context)
        val raw = p.getString(KEY, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            AppLogger.warning(TAG, "stored JSON malformed, discarding")
            p.edit().remove(KEY).apply()
            return null
        }
        val pending = PendingUpdate(
            targetVersionName = obj.optString("targetVersionName"),
            targetVersionCode = if (obj.has("targetVersionCode")) obj.optInt("targetVersionCode") else null,
            apkPath = obj.optString("apkPath"),
            apkSize = obj.optLong("apkSize"),
            sha256 = obj.optString("sha256", "").takeIf { it.isNotEmpty() && it != "null" },
            downloadedAtMs = obj.optLong("downloadedAtMs"),
        )
        val age = System.currentTimeMillis() - pending.downloadedAtMs
        if (age > MAX_AGE_MS) {
            AppLogger.info(TAG, "pending update expired age=${age}ms; clearing")
            clearPending(context)
            return null
        }
        return pending
    }

    fun clearPending(context: Context) {
        requirePrefs(context).edit().remove(KEY).apply()
        AppLogger.info(TAG, "clearPending")
    }

    fun verify(pending: PendingUpdate): File? {
        val f = File(pending.apkPath)
        if (!f.exists()) {
            AppLogger.warning(TAG, "verify: file missing ${pending.apkPath}")
            return null
        }
        if (f.length() != pending.apkSize) {
            AppLogger.warning(TAG, "verify: size mismatch expected=${pending.apkSize} actual=${f.length()}")
            return null
        }
        if (pending.sha256 != null) {
            val actual = runCatching { sha256(f) }.getOrNull()
            if (actual == null || !actual.equals(pending.sha256, ignoreCase = true)) {
                AppLogger.warning(TAG, "verify: sha256 mismatch expected=${pending.sha256} actual=$actual")
                return null
            }
        }
        return f
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
