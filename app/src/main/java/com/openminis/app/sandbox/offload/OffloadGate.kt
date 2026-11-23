package com.openminis.app.sandbox.offload

import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal object OffloadGate {
    fun allow(toolName: String, displayName: String, sessionId: String? = null): Boolean = runBlocking {
        OffloadPermissionManager.checkPermission(
            toolName,
            displayName,
            sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID,
        )
    }

    fun allow(toolName: String, displayName: String, request: NativeOffloadRequest): Boolean =
        allow(toolName, displayName, request.sessionId)

    fun enforce(
        toolName: String,
        displayName: String,
        args: OffloadArgs,
        request: NativeOffloadRequest? = null,
    ): NativeOffloadResult? {
        if (allow(toolName, displayName, request?.sessionId)) return null
        val body = JSONObject()
            .put("error", "permission_denied")
            .put("message",
                "Agent is not allowed to use $displayName. Open Settings → Permissions to change.")
            .toString()
        return NativeOffloadResult(126, OffloadOutput.formatBody(body, args) + "\n")
    }
}
