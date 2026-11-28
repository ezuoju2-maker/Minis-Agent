package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.SubAgentDefinition

object AgentTools {

    private const val TOOL_CACHE_CAPACITY = 16
    private val toolsCache = object : LinkedHashMap<List<Any?>, List<AgentToolDefinition>>(
        TOOL_CACHE_CAPACITY, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<List<Any?>, List<AgentToolDefinition>>?,
        ): Boolean = size > TOOL_CACHE_CAPACITY
    }

    @Synchronized
    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        visionGroupConfigured: Boolean = false,
        memoryEnabled: Boolean = true,
        isHelper: Boolean = false,
        delegateEnabled: Boolean = true,
        browserEnabled: Boolean = true,
        rosterNames: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME),
        userAskExposed: Boolean = false,
        skillsAvailable: Boolean = false,
        mcpTools: List<AgentToolDefinition> = emptyList(),
    ): List<AgentToolDefinition> {
        val key = listOf<Any?>(
            supportsImageInput, visionGroupConfigured, memoryEnabled, isHelper,
            delegateEnabled, browserEnabled, rosterNames, userAskExposed, skillsAvailable,
            mcpTools.map { it.name + "|" + it.description },
        )
        toolsCache[key]?.let { return it }
        val built = buildTools(
            supportsImageInput, visionGroupConfigured, memoryEnabled, isHelper,
            delegateEnabled, browserEnabled, rosterNames, userAskExposed, skillsAvailable,
            mcpTools,
        )
        toolsCache[key] = built
        return built
    }

    private fun buildTools(
        supportsImageInput: Boolean,
        visionGroupConfigured: Boolean,
        memoryEnabled: Boolean,
        isHelper: Boolean,
        delegateEnabled: Boolean,
        browserEnabled: Boolean,
        rosterNames: List<String>,
        userAskExposed: Boolean,
        skillsAvailable: Boolean,
        mcpTools: List<AgentToolDefinition>,
    ): List<AgentToolDefinition> = buildList {
        add(shellExecuteDefinition())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        add(ApplyPatchTool.definition())
        add(RepoMapTool.definition())
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        if (browserEnabled) {
            add(browserUseDefinition())
            add(com.openminis.app.browser.lightpanda.LightPandaTool.definition())
        }
        if (memoryEnabled) {
            if (!isHelper) add(memoryWriteDefinition())
            add(memoryGetDefinition())
            if (!isHelper) add(memoryFactDefinition())
            else add(memoryFactDefinition(helperReadOnly = true))
        }
        if (!isHelper && delegateEnabled) {
            add(subAgentTaskDefinition(rosterNames))
            add(AgentForgeTool.definition())
        }
        if (!isHelper) add(TodoTools.definition())
        if (!isHelper) add(SandboxModeTool.definition())
        add(ContinuityTools.searchDefinition())
        add(ContinuityTools.readDefinition())
        if (!isHelper) {
            add(ContinuityTools.goalDefinition())
            add(ContinuityTools.runStatusDefinition())
        }
        if (skillsAvailable) {
            add(FindSkillsTool.definition())
            add(SkillLoadTool.definition())
        }
        if (!isHelper) add(SkillSaveTool.definition())
        add(McpNativeTools.gatewayDefinition())
        mcpTools.forEach { add(it) }
        if (!isHelper) add(com.openminis.app.tools.CheckpointRestoreTool.definition())
        if (userAskExposed && !isHelper) add(com.openminis.app.agent.userask.UserAskPromptText.definition())
    }

    private fun subAgentTaskDefinition(rosterNames: List<String>): AgentToolDefinition =
        AgentToolDefinition(
            name = SubAgentDefinition.TOOL_NAME,
            description = "Delegate a self-contained task to a sub agent — its own isolated context and tool loop, in a hidden child session running concurrently with you — and inspect or stop the ones you started. `action` defaults to `delegate`.\n\nDELEGATE work needing many rounds of exploration (reading lots of files or pages, trial-and-error), producing bulk output you only need a conclusion from, or splitting into independent sub-problems you can run in parallel (several calls in one turn). DO NOT delegate what you can finish in one or two tool calls, what needs the user's confirmation mid-way, or work depending on nuances of this conversation you cannot restate. A sub agent cannot see this conversation and has no memory: write `task` as a complete brief for a capable colleague who just walked in — goal, constraints, where things are, what exactly to return. It costs a full model run, so nothing trivial. Only ${com.openminis.app.agent.jobs.AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT} run at once in this conversation, but delegate everything you need anyway: extras return status=queued and start as slots free, so never re-delegate a queued task or wait for a slot.\n\nwait=false (default) returns at once with status=running and a job_id; the result arrives later as a NEW MESSAGE prefixed [Background task finished …] (also on cancel/timeout/failure). End your turn when you have nothing else to do — never poll in a loop, never promise to report back. You do NOT need action=status to receive results.",
            parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user on the block in the tool bar and in the transcript (e.g. 'Survey repo test layout', 'Check on the research agent'). Use the same language as the user."),
            "action" to AgentToolParam("string", "\"delegate\" (default): start a sub agent on `task`. \"status\": report this conversation's sub agents — state (queued/running/done/cancelled/failed/interrupted), current tool, elapsed, model, finished results; `job_id` for one, omit for all. \"steer\": course-correct a RUNNING one without stopping it (see `message`). \"cancel\": stop the one named by `job_id`; its partial result is still posted back. \"resume\": restart runs the app lost when it was killed (they report `interrupted`); `job_id`/`child_session_id` for one, omit both for all.", enumValues = listOf("delegate", "status", "steer", "cancel", "resume")),
            "task" to AgentToolParam("string", "action=delegate only, required. The complete, self-contained brief: goal, success criteria, relevant paths/URLs, constraints, and exactly what to return. The sub agent sees nothing else."),
            "agent" to AgentToolParam("string", "action=delegate only. Which sub agent runs this task. Pick the one whose description matches the work; omit it to use the general one.", enumValues = rosterNames),
            "model_choice" to AgentToolParam("string", "action=delegate only, and only when the chosen sub agent is set to Auto — one the user pinned to a group ignores it. DEFAULT TO \"same_as_me\". The user picked the model this conversation runs on, and that choice covers the work you delegate from it: a sub agent on a different model can cost far more, or be far weaker, than what they chose, and they never see it happen. Only depart from it when the task itself gives you a specific reason, judged by what the task demands and not by how long it will take. \"same_as_me\" (default): this conversation's model — anything continuing the work at hand, and every case where you are unsure. \"default_model\": the user's strongest group — only when this task clearly needs more capability than the current model, e.g. multi-step reasoning, design judgment, ambiguous requirements where a wrong answer is expensive. \"sub_model\": the user's light group — only when the task is clearly mechanical and well-bounded, verifiable at a glance (collecting files against a list, format conversion, fixed commands, lookups).", enumValues = listOf("same_as_me", "default_model", "sub_model")),
            "context" to AgentToolParam("string", "action=delegate only. Optional raw material to hand over verbatim (file excerpts, error output, a list of paths). Appended to the task."),
            "max_turns" to AgentToolParam("integer", "action=delegate only. Tool-round budget for the sub agent (default 60, range 8-200). It is warned before tools are withdrawn so it can finish writing. Raise it only when the task genuinely needs long exploration; every round costs tokens."),
            "max_minutes" to AgentToolParam("integer", "action=delegate only. Wall-clock budget in minutes (default 10, maximum 60). The sub agent is stopped when it runs out and whatever it produced so far is returned with status=timeout."),
            "wait" to AgentToolParam("boolean", "action=delegate only. false (default): return at once with status=running; the result is posted here as a new message when done. true: block until it finishes and return the result here — only when the next step cannot proceed without it. If the user sends a message while you wait, the run moves to the background and the call returns status=running."),
            "progress_report" to AgentToolParam("string", "action=delegate only. Mid-run [Background task progress …] messages (status, current tool, elapsed, latest message). \"none\" (default): final result only. \"frequent\": every 15s when something changed. \"moderate\": once a minute. Each costs you a turn — leave at none unless the user asked to follow along or you must react mid-way. Answer one with at most a short sentence, or just carry on; never re-delegate or poll because of one. Ignored when wait=true.", enumValues = listOf("none", "frequent", "moderate")),
            "job_id" to AgentToolParam("string", "action=status/steer/cancel. The job_id this tool returned when it started the sub agent (a prefix is accepted). Required for steer and cancel; omit on status to list every sub agent of this conversation."),
            "message" to AgentToolParam("string", "action=steer only, required. The correction, phrased as an instruction to the running sub agent (e.g. 'focus on pricing, skip the migration notes'). Use when new information changes what it should do — it keeps the work already done, unlike cancelling and re-delegating. Read at its next turn, so a running tool call is not interrupted; if the run finishes first the result reports the steer as missed."),
            "child_session_id" to AgentToolParam("string", "action=resume only, optional. The child_session_id of one interrupted sub agent to restart. Omit to resume every interrupted sub agent in this conversation."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf(
                "tool_title", "action", "task", "agent", "model_choice", "context",
                "max_turns", "max_minutes", "wait", "progress_report", "job_id", "message",
                "child_session_id",
            ),
        )

    private fun shellExecuteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Execute a command in an isolated Linux process (Ubuntu 24.04 via PRoot). " +
            "The command runs via /bin/bash -c with stdout and stderr merged, so bash syntax " +
            "(arrays, [[ ]], pipes, process substitution) works directly. " +
            "Each invocation spawns a fresh process — there is no shared terminal session. " +
            "Timeout is chosen automatically by command type (quick commands 1 min, network 3 min, " +
            "package installs 10 min, builds 20 min) unless you set the timeout parameter explicitly.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Install Python data analysis packages', 'List files in home directory'). Use the same language as the user."),
            "command" to AgentToolParam("string", "The shell command to execute. Supports multi-line commands directly — no special escaping needed. Keep under 1000 chars; for longer scripts, write to a file with file_write first, then run it."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds. Omit it and the app picks by command type: quick commands ~60s, network ~180s, package installs ~600s, builds ~1200s. If you set it explicitly, 1–3600 (larger values are capped). Set a larger value only for genuinely long-running commands."),
            "delay" to AgentToolParam("integer", "Delay in seconds before execution begins. The tool blocks the agent flow during this wait WITHOUT occupying the shell, so other concurrent tasks can use it. Use this instead of sleep commands to avoid resource contention."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay"),
    )

    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        description = "Control a web browser with a few tabs (list_tabs shows the ones you may use). " +
            "Do NOT use this tool for minis:// action URLs (open_terminal, views, settings) — those are app deep links, use Markdown links in chat instead. " +
            "The browser supports both web URLs and minis:// resource URLs. Use minis:// URLs to preview session files (e.g. navigate to minis://workspace/index.html). " +
            "Sub-resources (JS, CSS, images, fonts) referenced via minis:// absolute paths or relative paths within HTML pages resolve correctly. " +
            "Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +
            "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages (like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
            "find_elements to discover interactive elements, " +
            "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
            "fetch to download files/resources using the page's session (returns metadata and a minis:// URL), " +
            "new_tab to open an additional tab, close_tab to close a tab, and list_tabs to see all open tabs. " +
            "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session (e.g. before screenshotting a 1920×1080 HTML composition that would otherwise be cropped to the phone viewport); pass reset=true to drop the session override and fall back to the global browser setting. " +
            "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). get_cookies supports optional 'keywords' (filter by cookie name) and 'fuzzy' (true=contains match, false=exact match, default true). It returns only a summary and an offload env file path — raw cookie values are NOT included in the tool response. To reuse cookies in shell commands: `. /var/minis/offloads/env_cookies_xxx.sh && command`. You may define alias variables when needed. " +
            "Use set_cookies to write cookies into the current page's cookie store via the native cookie store (so even HttpOnly cookies, which JS cannot set, land). Pass a 'cookies' array of objects, each with name + value (required) and optional domain (defaults to the current page host), path (defaults to '/'), secure, http_only, and expires (Unix timestamp in seconds; omit for a session cookie). " +
            "Use wait_for_dom_stable to wait until the page DOM stops changing (useful after navigation or interactions that trigger async data loading — polls every 0.5s, resolves when mutation rate gradient is stable for 3+ intervals, default timeout 10s). " +
            "Use tab_id to target a specific tab (defaults to the most recently used tab).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The browser action to perform",
                enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "URL to navigate to (for navigate action) or resource to download (for fetch action)"),
            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default: 500)"),
            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`)."),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to your most recently used tab). Use list_tabs to see the tabs you may use; ids you did not receive from list_tabs/new_tab are rejected."),
            "keywords" to AgentToolParam("string", "Filter cookies by name (for get_cookies). A space-separated string or array of strings. With fuzzy=true (default), ALL keywords must appear in the cookie name (case-insensitive). With fuzzy=false, cookie name must exactly equal any one of the provided keywords (case-insensitive). Omit to return all cookies for the current site."),
            "fuzzy" to AgentToolParam("boolean", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)."),
            "cookies" to AgentToolParam("string", "For set_cookies: a JSON array of cookie objects to write. Pass it as a JSON array (a JSON-encoded string of the array is also accepted). Each object: {\"name\": str (required), \"value\": str (required), \"domain\": str (optional, defaults to current page host), \"path\": str (optional, defaults to \"/\"), \"secure\": bool (optional), \"http_only\": bool (optional — sets an HttpOnly cookie that JS cannot read/set), \"expires\": int (optional, Unix timestamp in seconds; omit for a session cookie)}. Field-name variants from common cookie exports are accepted: httpOnly (=http_only), expirationDate (=expires), sameSite, and case/camel variants — so you can paste cookies verbatim from browser extensions (EditThisCookie / Cookie-Editor) or Playwright/Puppeteer storage."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds for wait_for_dom_stable (default: 10). The action polls every 0.5s and resolves when DOM mutation rate stabilizes."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
            "full_page" to AgentToolParam("boolean", "For screenshot: capture the entire scrollable page by temporarily stretching the browser viewport to document.documentElement.scrollHeight. Default false captures the visible viewport only. Capped at 32768px tall; when capped, the result text includes 'Truncated: true' and the original height, so you can scroll and capture the remainder separately."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset", "full_page"),
    )

    private fun memoryWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_write",
        description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. " +
            "Each entry is prepended with a timestamp. " +
            "Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. " +
            "Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. " +
            "Keep entries concise and general-purpose. GLOBAL.md is writable but not through this tool; update it when the user asks.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user."),
            "content" to AgentToolParam("string", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned."),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content"),
    )

    private fun memoryFactDefinition(helperReadOnly: Boolean = false): AgentToolDefinition =
        AgentToolDefinition(
            name = "memory_fact",
            description = "Structured long-term facts that strengthen with repetition (shared across all sessions). " +
                if (helperReadOnly) {
                    "Use action 'search' to recall facts learned in past sessions."
                } else {
                    "Use action 'remember' to store a durable fact (user preferences, project conventions, recurring decisions) as subject · predicate = value; repeating the same fact raises its confidence, so record facts as you learn them. Use 'search' to recall. Do NOT store passwords, tokens or secrets."
                },
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
                "action" to AgentToolParam("string", if (helperReadOnly) "Only 'search' is available to helpers." else "'remember' to store a fact, 'search' to recall facts.", enumValues = if (helperReadOnly) listOf("search") else listOf("remember", "search")),
                "subject" to AgentToolParam("string", "Who/what the fact is about, e.g. 'user', 'project Alpha', 'the API'. Required for 'remember'."),
                "predicate" to AgentToolParam("string", "The aspect, e.g. 'prefers editor', 'uses database', 'deadline'. Required for 'remember'."),
                "value" to AgentToolParam("string", "The fact value, e.g. 'vim', 'PostgreSQL', '2026-12-01'. Required for 'remember'."),
                "keywords" to AgentToolParam("string", "Space-separated keywords for 'search'. Leave empty to list the strongest facts."),
            ),
            required = if (helperReadOnly) listOf("tool_title", "action") else listOf("tool_title", "action"),
            propertyOrdering = listOf("tool_title", "action", "subject", "predicate", "value", "keywords"),
        )

    private fun memoryGetDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_get",
        description = "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. " +
            "Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user."),
            "scope" to AgentToolParam("string", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.", enumValues = listOf("daily", "all")),
            "keywords" to AgentToolParam("string", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "scope", "keywords"),
    )
}
