package com.openminis.app.share

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ShareCoordinator {
    private const val TAG = "ShareCoordinator"
    private const val BUFFER_TTL_MS = 300_000L
    private const val LAUNCH_MAX_AGE_MS = 5L * 60 * 1000

    private data class Buffered(
        val share: PendingShare,
        val bufferedAtMs: Long,
        val targetSessionId: String? = null,
    )

    @Volatile private var buffer: Buffered? = null

    private val _bufferVersion = MutableStateFlow(0)
    val bufferVersion: StateFlow<Int> = _bufferVersion.asStateFlow()

    @Synchronized
    fun processPendingShare(context: Context) {
        val pending = SharedShareStore.loadPendingShare(context) ?: run {
            AppLogger.info(TAG, "[Share] processPendingShare: no pending share")
            return
        }
        val ageMs = System.currentTimeMillis() - pending.timestampMs
        if (ageMs > LAUNCH_MAX_AGE_MS) {
            AppLogger.info(TAG, "[Share] processPendingShare: stale (age=${ageMs}ms), discarding")
            SharedShareStore.clearPendingShare(context)
            SharedShareStore.cleanSharedFiles(context, keep = liveBufferFileNames())
            return
        }
        SharedShareStore.clearPendingShare(context)
        val now = System.currentTimeMillis()
        val existing = buffer
        val mergedShare = if (existing != null && now - existing.bufferedAtMs <= BUFFER_TTL_MS) {
            val seen = LinkedHashMap<Pair<PendingShare.Item.Kind, String>, PendingShare.Item>()
            for (item in existing.share.items + pending.items) {
                seen[item.kind to item.value] = item
            }
            AppLogger.info(
                TAG,
                "[Share] processPendingShare: merging ${existing.share.items.size} + " +
                    "${pending.items.size} -> ${seen.size} item(s)",
            )
            PendingShare(seen.values.toList(), pending.timestampMs)
        } else {
            AppLogger.info(TAG, "[Share] processPendingShare: buffering ${pending.items.size} item(s)")
            pending
        }
        buffer = Buffered(mergedShare, now)
        _bufferVersion.value = _bufferVersion.value + 1
        logBufferState("buffered", mergedShare, now, bufferedAtMs = now)
    }

    private fun logBufferState(
        phase: String,
        share: PendingShare,
        nowMs: Long,
        bufferedAtMs: Long,
    ) {
        val items = share.items.mapIndexed { i, item ->
            val v = item.value
            val preview = v.take(DIAG_VALUE_PREVIEW).replace("\n", "\\n")
            "#$i(${item.kind.name} len=${v.length} \"$preview\"" +
                if (v.length > DIAG_VALUE_PREVIEW) "…\")" else "\")"
        }
        AppLogger.info(
            TAG,
            "[XSessionDiag] share/$phase: items=${share.items.size} " +
                "shareTimestampMs=${share.timestampMs} bufferedAtMs=$bufferedAtMs now=$nowMs " +
                "ageMs=${nowMs - bufferedAtMs} ttlRemainingMs=${BUFFER_TTL_MS - (nowMs - bufferedAtMs)} " +
                "-> ${items.joinToString(" | ")}",
        )
    }

    private const val DIAG_VALUE_PREVIEW = 100

    private fun liveBufferFileNames(): Set<String> =
        buffer?.share?.items
            ?.filter { it.kind == PendingShare.Item.Kind.ATTACHMENT }
            ?.map { it.value }
            ?.toSet()
            ?: emptySet()

    fun hasUnaddressedShare(): Boolean = buffer?.let { it.targetSessionId == null } == true

    @Synchronized
    fun addressTo(sessionId: String): Boolean {
        val buf = buffer ?: return false
        if (buf.targetSessionId != null) return false
        buffer = buf.copy(targetSessionId = sessionId)
        AppLogger.info(TAG, "[Share] addressed ${buf.share.items.size} item(s) to session=${sessionId.take(16)}")
        _bufferVersion.value = _bufferVersion.value + 1
        return true
    }

    @Synchronized
    fun consumeBuffer(context: Context, sessionId: String): PendingShare? {
        val buf = buffer ?: return null
        if (buf.targetSessionId != sessionId) return null
        val diagSessionId: String? = sessionId
        buffer = null
        val nowMs = System.currentTimeMillis()
        val ageMs = nowMs - buf.bufferedAtMs
        if (ageMs > BUFFER_TTL_MS) {
            AppLogger.info(TAG, "[Share] consumeBuffer: expired (age=${ageMs}ms)")
            AppLogger.info(
                TAG,
                "[XSessionDiag] share/consume-expired: " +
                    "consumer=${diagSessionId?.take(8) ?: "unknown"} " +
                    "items=${buf.share.items.size} ageMs=$ageMs ttlMs=$BUFFER_TTL_MS",
            )
            SharedShareStore.cleanSharedFiles(context)
            notifyExpired(context)
            return null
        }
        AppLogger.info(TAG, "[Share] consumeBuffer: ${buf.share.items.size} item(s)")
        logBufferState("consume", buf.share, nowMs, buf.bufferedAtMs)
        AppLogger.info(
            TAG,
            "[XSessionDiag] share/consumed-by: consumer=${diagSessionId?.take(8) ?: "unknown"} " +
                "items=${buf.share.items.size} ageMs=$ageMs",
        )
        return buf.share
    }

    private fun notifyExpired(context: Context) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        context.applicationContext,
                        context.getString(com.openminis.app.R.string.share_expired_toast),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
