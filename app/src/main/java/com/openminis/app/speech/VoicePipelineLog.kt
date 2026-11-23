package com.openminis.app.speech

import com.openminis.app.logging.AppLogger

internal object VoicePipelineLog {
    private const val TAG = "VoicePipeline"

    fun event(name: String, vararg meta: Pair<String, Any?>) {
        val suffix = if (meta.isEmpty()) "" else
            meta.joinToString(" ", prefix = " ") { (k, v) -> "$k=${v ?: "-"}" }
        AppLogger.info(TAG, "$name$suffix")
    }

    fun errorKind(e: Throwable?): String = e?.javaClass?.simpleName ?: "none"
}
