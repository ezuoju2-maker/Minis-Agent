package com.openminis.app.data.session

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger

object OrphanChildSweeper {
    private const val TAG = "OrphanSweep"

    private const val GRACE_MS = 24L * 60 * 60 * 1000

    data class Report(val examined: Int, val deleted: Int, val spared: Int, val bytesFreed: Long)

    suspend fun sweep(context: Context, repo: ChatRepository): Report {
        val cutoff = System.currentTimeMillis() - GRACE_MS
        val totalChildren = runCatching { repo.dao.childSessionCount() }.getOrDefault(-1)
        val candidates = runCatching { repo.dao.orphanChildSessionIds(cutoff) }.getOrElse {
            AppLogger.warning(TAG, "sweep aborted — candidate query failed: ${it.message}")
            return Report(0, 0, 0, 0)
        }
        AppLogger.info(
            TAG,
            "sweep start: childSessions=$totalChildren candidates=${candidates.size} graceHours=${GRACE_MS / 3_600_000}",
        )
        if (candidates.isEmpty()) return Report(0, 0, 0, 0)

        var deleted = 0
        var spared = 0
        var bytes = 0L
        for (id in candidates) {
            val row = runCatching { repo.getSession(id) }.getOrNull()
            if (row == null) {
                spared++
                AppLogger.info(TAG, "SPARED child=${id.take(8)} — row gone before delete")
                continue
            }
            val parentId = row.parentSessionId
            if (parentId.isNullOrEmpty()) {
                spared++
                AppLogger.warning(TAG, "SPARED child=${id.take(8)} — no parent id on re-read")
                continue
            }
            val parent = runCatching { repo.getSession(parentId) }.getOrNull()
            if (parent != null) {
                spared++
                AppLogger.warning(
                    TAG,
                    "SPARED child=${id.take(8)} — parent ${parentId.take(8)} exists after all",
                )
                continue
            }
            val ageDays = (System.currentTimeMillis() - row.updatedAt) / 86_400_000
            AppLogger.info(
                TAG,
                "DELETE child=${id.take(8)} parent=${parentId.take(8)} (missing) " +
                    "age=${ageDays}d title='${row.title?.take(40).orEmpty()}'",
            )
            val report = runCatching {
                SessionDeleter.deleteTree(context, repo, id, "orphan-sweep")
            }
            val done = report.getOrNull()
            if (done == null) {
                spared++
                AppLogger.warning(
                    TAG,
                    "SPARED child=${id.take(8)} — delete failed: ${report.exceptionOrNull()?.message}",
                )
            } else {
                deleted += done.deletedIds.size
                bytes += done.bytesFreed
            }
        }
        AppLogger.info(TAG, "sweep done: deleted=$deleted spared=$spared freed=${bytes}B")
        return Report(candidates.size, deleted, spared, bytes)
    }
}
