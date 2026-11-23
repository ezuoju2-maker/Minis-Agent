package com.openminis.app.agent.userask

import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult

object UserAskToolRunner {
    private const val TAG = "UserAsk"

    fun interface BeforeShow {
        fun onAsk(ask: PendingAsk)
    }

    fun executeDeferred(
        argsJson: String,
        toolCallId: String,
        sessionId: String?,
        sessionTitle: String?,
        nowMs: Long = System.currentTimeMillis(),
        requestId: String = java.util.UUID.randomUUID().toString(),
        beforeShow: BeforeShow? = null,
    ): ToolExecutionResult {
        val request = UserAskParser.parse(argsJson).getOrElse {
            return ToolExecutionResult("Error: ${it.message}", false, toolTitle = "")
        }
        if (sessionId.isNullOrEmpty()) {
            return ToolExecutionResult(UserAskResolver.encode(UserAskResolver.unavailable(request.questions)), true, toolTitle = "")
        }
        val ask = PendingAsk.create(requestId, sessionId, toolCallId, request.questions, nowMs, timeoutMinutes = 0)
            .copy(sessionTitle = sessionTitle, deferred = true)
        runCatching { beforeShow?.onAsk(ask) }.onFailure {
            AppLogger.warning(TAG, "[UserAsk] record not saved for req=${ask.requestId.take(8)}: ${it.javaClass.simpleName}")
        }
        UserAskCenter.beginDeferred(ask)
        AppLogger.info(
            TAG,
            "[UserAsk] DEFERRED sid=${sessionId.take(8)} tool=${toolCallId.take(16)} questions=${request.questions.size} - card left open, run continues",
        )
        return ToolExecutionResult(UserAskDeferred.toolResult(request.questions.size), true, toolTitle = "")
    }

    suspend fun execute(
        argsJson: String,
        toolCallId: String,
        sessionId: String?,
        sessionTitle: String?,
        timeoutMinutes: Int,
        nowMs: Long = System.currentTimeMillis(),
        requestId: String = java.util.UUID.randomUUID().toString(),
        beforeShow: BeforeShow? = null,
    ): ToolExecutionResult {
        val request = UserAskParser.parse(argsJson).getOrElse {
            return ToolExecutionResult("Error: ${it.message}", false, toolTitle = "")
        }
        if (sessionId.isNullOrEmpty()) {
            return ToolExecutionResult(UserAskResolver.encode(UserAskResolver.unavailable(request.questions)), true, toolTitle = "")
        }
        val ask = PendingAsk.create(
            requestId, sessionId, toolCallId, request.questions, nowMs,
            UserAskSettingsSpec.clampTimeout(timeoutMinutes),
        ).copy(sessionTitle = sessionTitle)
        UserAskCenter.claim(ask.requestId)
        runCatching { beforeShow?.onAsk(ask) }.onFailure {
            AppLogger.warning(TAG, "[UserAsk] record not saved for req=${ask.requestId.take(8)}: ${it.javaClass.simpleName}")
        }
        AppLogger.info(TAG, "[UserAsk] waiting_user ENTER sid=${sessionId.take(8)} tool=${toolCallId.take(16)}")
        UserAskCenter.begin(ask)
        val result = UserAskCenter.wait(ask.requestId)
        AppLogger.info(
            TAG,
            "[UserAsk] waiting_user EXIT sid=${sessionId.take(8)} statuses=${UserAskResolver.statuses(result).map { it.wire }} resultLen=${result.length}",
        )
        return ToolExecutionResult(result, true, toolTitle = "")
    }
}
