package com.openminis.app.ui.chat

object ToolFoldPlanner {
    const val MINIMUM_TOOL_COUNT = 2

    const val SEPARATOR_MINIMUM_LINES = 2

    enum class ToolOutcome { UNFINISHED, SUCCEEDED, FAILED, CANCELLED }

    sealed class Input {
        data class Tool(val outcome: ToolOutcome) : Input()
        object Thinking : Input() { override fun toString() = "Thinking" }
        data class Text(val isEmpty: Boolean) : Input()
        object Note : Input() { override fun toString() = "Note" }
        object Barrier : Input() { override fun toString() = "Barrier" }
    }

    data class Group(
        val range: IntRange,
        val toolCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
    )

    fun plan(blocks: List<Input>, enabled: Boolean): List<Group> {
        if (!enabled) return emptyList()
        val groups = mutableListOf<Group>()
        var start: Int? = null
        var leadThinking: Int? = null
        var lastMember = 0
        var tools = 0
        var failed = 0
        var cancelled = 0
        var softEnd: Group? = null

        fun reset() {
            start = null
            tools = 0; failed = 0; cancelled = 0
            softEnd = null
        }
        fun closeAtSoftEnd() {
            softEnd?.let { if (it.toolCount >= MINIMUM_TOOL_COUNT) groups.add(it) }
        }

        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is Input.Barrier -> {
                    closeAtSoftEnd(); reset(); leadThinking = null
                }
                is Input.Tool -> {
                    if (block.outcome == ToolOutcome.UNFINISHED) {
                        closeAtSoftEnd(); reset(); leadThinking = null
                        continue
                    }
                    if (start == null) start = leadThinking ?: i
                    leadThinking = null
                    lastMember = i
                    tools += 1
                    if (block.outcome == ToolOutcome.FAILED) failed += 1
                    if (block.outcome == ToolOutcome.CANCELLED) cancelled += 1
                }
                is Input.Note -> {
                    val s = start
                    if (s == null) {
                        leadThinking = null
                        continue
                    }
                    lastMember = i
                    softEnd = Group(s..i, tools, failed, cancelled)
                }
                is Input.Thinking -> if (start == null && leadThinking == null) leadThinking = i
                is Input.Text -> {
                    if (block.isEmpty) continue
                    val s = start
                    if (s != null && tools >= MINIMUM_TOOL_COUNT) {
                        groups.add(Group(s..lastMember, tools, failed, cancelled))
                    }
                    reset()
                    leadThinking = null
                }
            }
        }
        closeAtSoftEnd()
        return groups
    }

    fun markingNotes(inputs: List<Input>, isSingleLine: (Int) -> Boolean): List<Input> {
        val lastTool = inputs.indexOfLast { it is Input.Tool }
        if (lastTool < 0) return inputs
        val out = inputs.toMutableList()
        for (i in 0 until lastTool) {
            if (out[i] == Input.Text(isEmpty = false) && isSingleLine(i)) out[i] = Input.Note
        }
        return out
    }

    fun isSingleLine(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return true
        val last = text.indexOfLast { !it.isWhitespace() }
        for (i in first..last) if (text[i] == '\n' || text[i] == '\r') return false
        return true
    }

    private fun lines(text: String): Sequence<String> = text.splitToSequence("\r\n", "\n", "\r")

    fun nonEmptyLineCount(text: String): Int = lines(text).count { line -> line.any { !it.isWhitespace() } }

    fun isFoldSeparator(requestText: String): Boolean {
        var n = 0
        for (line in lines(requestText)) {
            if (line.any { !it.isWhitespace() }) {
                n += 1
                if (n >= SEPARATOR_MINIMUM_LINES) return true
            }
        }
        return false
    }

    fun <ID> foldHorizon(blockIds: List<ID>, separators: Set<ID>): Int {
        if (separators.isEmpty()) return 0
        val last = blockIds.indexOfLast { it in separators }
        return if (last < 0) 0 else last + 1
    }

    fun holdsBack(
        browsing: Boolean,
        alreadyFolded: Boolean,
        inRunningMessage: Boolean,
        shownAsRows: () -> Boolean,
    ): Boolean {
        if (!browsing || alreadyFolded) return false
        return inRunningMessage || shownAsRows()
    }


    fun input(block: AssistantBlock): Input = when (block.kind) {
        "text" -> Input.Text(isEmpty = block.content.none { !it.isWhitespace() })
        "thinking" -> Input.Thinking
        "info" -> Input.Barrier
        else -> when {
            com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(block.toolName) -> Input.Barrier
            block.toolName == com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> Input.Barrier
            else -> when (block.toolStatus) {
                ToolBlockStatus.SUCCESS -> Input.Tool(ToolOutcome.SUCCEEDED)
                ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> Input.Tool(ToolOutcome.FAILED)
                ToolBlockStatus.CANCELLED -> Input.Tool(ToolOutcome.CANCELLED)
                ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null ->
                    Input.Tool(ToolOutcome.UNFINISHED)
            }
        }
    }

    fun inputs(blocks: List<AssistantBlock>): List<Input> =
        markingNotes(blocks.map(::input)) { isSingleLine(blocks[it].content) }

    data class FoldedRun(
        val key: String,
        val firstBlockId: String,
        val memberIds: List<String>,
        val group: Group,
        val durationMs: Long?,
    )

    fun foldedRuns(blocks: List<AssistantBlock>): List<FoldedRun> =
        plan(inputs(blocks), enabled = true).map { g ->
            val members = blocks.subList(g.range.first, g.range.last + 1)
            val firstTool = members.firstOrNull { it.kind == "tool_use" } ?: members.first()
            val durations = members.filter { it.kind == "tool_use" && it.durationMs > 0 }.map { it.durationMs }
            FoldedRun(
                key = firstTool.id,
                firstBlockId = members.first().id,
                memberIds = members.map { it.id },
                group = g,
                durationMs = if (durations.isEmpty()) null else durations.sum(),
            )
        }

    fun liveHorizon(blocks: List<AssistantBlock>): Int {
        if (blocks.isEmpty()) return 0
        val separators = HashSet<String>()
        var start = 0
        var i = 0
        while (start < blocks.size) {
            i = start
            while (i < blocks.size && blocks[i].kind != "tool_use") i++
            while (i < blocks.size && blocks[i].kind == "tool_use") i++
            val end = i - 1
            val request = blocks.subList(start, end + 1)
            val isLast = i >= blocks.size
            val tools = request.filter { it.kind == "tool_use" }
            val ended = !isLast || (tools.isNotEmpty() && tools.none { it.toolStatus == ToolBlockStatus.STREAMING })
            if (ended) {
                val text = request.filter { it.kind == "text" }.joinToString("\n") { it.content }
                if (isFoldSeparator(text)) separators.add(blocks[end].id)
            }
            start = i
        }
        return foldHorizon(blocks.map { it.id }, separators)
    }

    fun foldedRuns(message: ChatMessage): List<FoldedRun> {
        val blocks = message.toolBlocks
        if (blocks.isEmpty()) return emptyList()
        val horizon = if (message.isStreaming) liveHorizon(blocks) else blocks.size
        if (horizon <= 0) return emptyList()
        return foldedRuns(if (horizon >= blocks.size) blocks else blocks.subList(0, horizon))
    }
}
