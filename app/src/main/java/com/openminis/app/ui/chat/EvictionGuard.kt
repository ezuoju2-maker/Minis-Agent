package com.openminis.app.ui.chat

internal object EvictionGuard {

    fun hasWorkInFlight(
        isStreaming: Boolean,
        streamJobActive: Boolean,
        hasAgentWork: Boolean,
        hasQueuedPrompts: Boolean,
    ): Boolean = isStreaming || streamJobActive || hasAgentWork || hasQueuedPrompts
}
