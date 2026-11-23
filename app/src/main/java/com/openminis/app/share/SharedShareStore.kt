package com.openminis.app.share

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File

object SharedShareStore {
    private const val TAG = "SharedShareStore"
    private const val PREFS_NAME = "share_prefs"
    private const val KEY_PENDING_SHARE = "pending_share"
    private const val SHARE_DIR = "share_extension"

    private const val MERGE_MAX_AGE_MS = 300_000L

    private const val MERGE_MAX_ITEMS = 50

    fun sharedFileDirectory(context: Context): File =
        File(context.filesDir, SHARE_DIR).apply { mkdirs() }

    fun savePendingShare(context: Context, share: PendingShare) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = loadPendingShare(context)
        val now = System.currentTimeMillis()
        val merged = if (existing != null && now - existing.timestampMs <= MERGE_MAX_AGE_MS) {
            val seen = LinkedHashMap<Pair<PendingShare.Item.Kind, String>, PendingShare.Item>()
            for (item in existing.items + share.items) {
                seen[item.kind to item.value] = item
            }
            val all = seen.values.toList()
            val capped = if (all.size > MERGE_MAX_ITEMS) all.takeLast(MERGE_MAX_ITEMS) else all
            AppLogger.info(
                TAG,
                "merging pending share: ${existing.items.size} + ${share.items.size} -> ${capped.size} item(s)" +
                    if (capped.size < all.size) " (capped from ${all.size})" else "",
            )
            PendingShare(capped, now)
        } else {
            share
        }
        prefs.edit().putString(KEY_PENDING_SHARE, merged.toJson().toString()).apply()
        AppLogger.info(TAG, "saved pending share with ${merged.items.size} item(s)")
    }

    fun loadPendingShare(context: Context): PendingShare? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PENDING_SHARE, null) ?: return null
        return try {
            PendingShare.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "failed to decode pending share: ${e.message}")
            null
        }
    }

    fun clearPendingShare(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_SHARE).apply()
    }

    fun cleanSharedFiles(context: Context, keep: Set<String> = emptySet()) {
        val dir = sharedFileDirectory(context)
        dir.listFiles()?.forEach { f ->
            if (f.name !in keep) runCatching { f.delete() }
        }
    }
}
