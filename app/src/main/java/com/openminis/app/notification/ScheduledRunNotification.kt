package com.openminis.app.notification

import com.openminis.app.data.db.MessageHeadRow
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.scheduled.ScheduledTaskMarker

internal object ScheduledRunNotification {

    const val MAX_SCAN_ROWS = 64

    fun tag(taskId: String?): String? = taskId?.let { "job-$it" }

    suspend fun taskIdOpeningTurn(rowAt: suspend (offset: Int) -> MessageHeadRow?): String? {
        for (offset in 0 until MAX_SCAN_ROWS) {
            val row = rowAt(offset) ?: return null
            if (row.role != "user") continue
            if (!ChatRepository.isTurnStartingUserParts(row.partsJson)) continue
            return taskIdIn(row.partsJson)
        }
        return null
    }

    fun taskIdIn(partsJson: String): String? {
        val arr = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "text") continue
            val text = o.optString("value", "").trim()
            ScheduledTaskMarker.parse(text)?.let { return it.taskId }
        }
        return null
    }
}
