package com.openminis.app.ui.chat

import java.util.concurrent.atomic.AtomicLong

internal class LiveTurnGuard {
    private data class Live(val generation: Long, val assistantId: String?)

    private val generation = AtomicLong(0)

    @Volatile private var live: Live? = null

    @Synchronized
    fun begin(): Long {
        val gen = generation.incrementAndGet()
        live = Live(gen, null)
        return gen
    }

    @Synchronized
    fun markAssistant(gen: Long, assistantId: String) {
        if (live?.generation == gen) live = Live(gen, assistantId)
    }

    @Synchronized
    fun end(gen: Long) {
        if (live?.generation == gen) live = null
    }

    @Synchronized
    fun cancel(): String? {
        val was = live?.assistantId
        generation.incrementAndGet()
        live = null
        return was
    }

    fun isLive(gen: Long): Boolean = live?.generation == gen

    fun liveGeneration(): Long? = live?.generation

    fun liveAssistantId(): String? = live?.assistantId

    fun acceptsStreamingWrite(assistantId: String): Boolean {
        val l = live ?: return false
        return l.assistantId == assistantId
    }
}

internal object InFlightToolHeal {

    fun isInFlight(status: ToolBlockStatus?): Boolean =
        status == ToolBlockStatus.RUNNING ||
            status == ToolBlockStatus.STREAMING ||
            status == ToolBlockStatus.PENDING

    private fun isTerminal(status: ToolBlockStatus?): Boolean =
        status != null && !isInFlight(status)

    data class Healed(
        val messages: List<ChatMessage>,
        val toolUses: List<Triple<String, String, String>>,
        val blockCount: Int,
    )

    fun sweep(
        messages: List<ChatMessage>,
        keep: Set<String>,
        ownLifecycle: (AssistantBlock) -> Boolean = { false },
    ): Healed {
        var out: MutableList<ChatMessage>? = null
        val toolUses = mutableListOf<Triple<String, String, String>>()
        var blocks = 0
        fun stale(b: AssistantBlock) = isInFlight(b.toolStatus) && !ownLifecycle(b)
        for ((i, m) in messages.withIndex()) {
            if (m.role != "assistant" || m.id in keep) continue
            if (m.toolBlocks.none { stale(it) }) continue
            val fixed = m.toolBlocks.map { b ->
                if (!stale(b)) b else {
                    blocks++
                    if (b.kind == "tool_use") toolUses += Triple(m.id, b.id, b.toolName)
                    b.copy(toolStatus = ToolBlockStatus.CANCELLED)
                }
            }
            if (out == null) out = messages.toMutableList()
            out[i] = m.copy(toolBlocks = fixed, isStreaming = false, isAwaitingModelResponse = false)
        }
        return Healed(out ?: messages, toolUses, blocks)
    }

    fun mergeOverlayBlocks(
        canonical: List<AssistantBlock>,
        overlay: List<AssistantBlock>,
    ): List<AssistantBlock> {
        if (canonical.isEmpty()) return overlay
        val done = HashMap<String, ToolBlockStatus>()
        for (b in canonical) if (isTerminal(b.toolStatus)) done[b.id] = b.toolStatus!!
        if (done.isEmpty()) return overlay
        return overlay.map { b ->
            val kept = done[b.id]
            if (kept != null && isInFlight(b.toolStatus)) b.copy(toolStatus = kept) else b
        }
    }

    enum class StopAction {
        STOP_TURN,
        CANCEL_STALE_BLOCK,
        NONE,
    }

    fun stopAction(
        blockId: String,
        isStreaming: Boolean,
        liveBlocks: List<AssistantBlock>,
        blockStatus: ToolBlockStatus?,
    ): StopAction {
        if (isStreaming && liveBlocks.any { it.id == blockId && isInFlight(it.toolStatus) }) {
            return StopAction.STOP_TURN
        }
        return if (isInFlight(blockStatus)) StopAction.CANCEL_STALE_BLOCK else StopAction.NONE
    }

    fun appendableCancelResults(rowsParts: List<String>, toolUseIds: Collection<String>): Set<String> {
        if (toolUseIds.isEmpty() || rowsParts.isEmpty()) return emptySet()
        val answered = HashSet<String>()
        val useRow = HashMap<String, Int>()
        for ((row, json) in rowsParts.withIndex()) {
            val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val part = arr.optJSONObject(i) ?: continue
                val id = part.optJSONObject("value")?.optString("toolUseId", "").orEmpty()
                if (id.isEmpty()) continue
                val key = com.openminis.app.provider.ToolPairing.key(id)
                when (part.optString("type")) {
                    "toolResult" -> answered += key
                    "toolUse" -> useRow[key] = row
                }
            }
        }
        val last = rowsParts.lastIndex
        return toolUseIds.filterTo(HashSet()) { id ->
            val key = com.openminis.app.provider.ToolPairing.key(id)
            key !in answered && useRow[key] == last
        }
    }

    fun cancelBlock(blocks: List<AssistantBlock>, blockId: String): List<AssistantBlock> {
        if (blocks.none { it.id == blockId && isInFlight(it.toolStatus) }) return blocks
        return blocks.map {
            if (it.id == blockId && isInFlight(it.toolStatus)) it.copy(toolStatus = ToolBlockStatus.CANCELLED) else it
        }
    }
}
