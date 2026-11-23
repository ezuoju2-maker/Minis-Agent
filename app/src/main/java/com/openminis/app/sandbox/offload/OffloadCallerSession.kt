package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.ExecutionCoordinator

internal object OffloadCallerSession {

    data class Caller(val sid: String?, val source: String)

    fun resolve(
        dispatchSid: String?,
        mountedFor: (String) -> String = ExecutionCoordinator::mountedSessionIdFor,
    ): Caller {
        if (dispatchSid.isNullOrEmpty()) return Caller(null, "none (no chat session)")
        val mounted = mountedFor(dispatchSid)
        return if (mounted != dispatchSid) {
            Caller(mounted, "mounted parent (dispatch=${dispatchSid.take(8)})")
        } else {
            Caller(dispatchSid, "env")
        }
    }
}
