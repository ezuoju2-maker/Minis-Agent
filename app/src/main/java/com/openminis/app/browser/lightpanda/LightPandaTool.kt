package com.openminis.app.browser.lightpanda

import android.content.Context
import com.openminis.app.browser.cdp.CdpCodec
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

object LightPandaTool {

    const val NAME = "browser_headless"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Drive a real headless browser (Lightpanda, CDP) inside the Linux sandbox. " +
            "Unlike browser_use — which automates the on-device WebView through the accessibility tree — " +
            "this runs an independent Chromium-compatible engine with no visible window, so it keeps working " +
            "while the app is in the background and handles pages that need real script execution. " +
            "It never shares cookies or logins with the on-device browser.\n\n" +
            "First use downloads a 74 MB arm64 build from the Lightpanda project (AGPL-3.0), checks " +
            "its SHA-256, and installs it inside the sandbox, so the first call needs network.\n\n" +
            "Use `open` to load a URL (returns title plus the first part of the page text), `read` for more " +
            "text, `click`/`type` with a CSS selector, `eval` to run JavaScript and get the value back, " +
            "and `screenshot` to see the rendered page as an image. `start`/`stop` control the engine; " +
            "any browsing action starts it automatically, so you rarely need `start`. " +
            "Prefer this for scraping, JS-heavy pages, and anything you want to run without showing the user a browser.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this call, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam(
                "string",
                "open: load `url`. read: return the visible text of the current page (`max_chars`). " +
                    "click: click the element matched by `selector`. type: put `text` into `selector`. " +
                    "eval: evaluate `expression` and return its value. screenshot: capture the page as PNG. " +
                    "start / stop: control the engine. status: report install and connection state.",
                enumValues = listOf("open", "read", "click", "type", "eval", "screenshot", "start", "stop", "status"),
            ),
            "url" to AgentToolParam("string", "action=open. Absolute http/https URL to load."),
            "selector" to AgentToolParam("string", "action=click/type. CSS selector, e.g. `#submit` or `button[type=submit]`. The first match is used."),
            "text" to AgentToolParam("string", "action=type. Text to write into the matched field. It is set directly, then input/change events are dispatched so frameworks notice."),
            "expression" to AgentToolParam("string", "action=eval. JavaScript expression; the awaited value is returned. Wrap statements in an IIFE, e.g. `(() => document.querySelectorAll('a').length)()`."),
            "max_chars" to AgentToolParam("integer", "action=read/open. Cap on returned characters (default 6000, max 20000). Long pages are truncated with a marker."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "url", "selector", "text", "expression", "max_chars"),
    )

    suspend fun execute(context: Context, argsJson: String, toolTitle: String): ToolExecutionResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runInner(context, argsJson, toolTitle)
        }

    private suspend fun runInner(context: Context, argsJson: String, toolTitle: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val action = args.optString("action", "open").lowercase()
        val maxChars = args.optInt("max_chars", 6000).coerceIn(500, 20_000)
        val browser = LightPandaBrowser.getInstance(context)

        fun fail(message: String) = ToolExecutionResult(message, false, toolTitle = toolTitle)

        if (action == "status") {
            return ToolExecutionResult(browser.status(), true, toolTitle = toolTitle)
        }
        if (action == "stop") {
            browser.stop()
            return ToolExecutionResult("Lightpanda stopped.", true, toolTitle = toolTitle)
        }

        return try {
            val client = browser.client()
            when (action) {
                "start" -> ToolExecutionResult(browser.status(), true, toolTitle = toolTitle)

                "open" -> {
                    var url = args.optString("url", "").trim()
                    if (url.isEmpty()) return fail("action=open needs `url`")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                    client.navigate(url)
                    val title = client.evaluateString("document.title")
                    val text = client.evaluateString("document.body ? document.body.innerText : ''")
                    val head = buildString {
                        append("Loaded ").append(url)
                        if (title.isNotBlank()) append(" — \"").append(title.trim().take(120)).append('"')
                        append('\n')
                        append(CdpCodec.clamp(text.trim(), maxChars))
                    }
                    ToolExecutionResult(head, true, toolTitle = toolTitle, pageURL = url)
                }

                "read" -> {
                    val title = client.evaluateString("document.title")
                    val href = client.evaluateString("location.href")
                    val text = client.evaluateString("document.body ? document.body.innerText : ''")
                    val head = buildString {
                        append(href)
                        if (title.isNotBlank()) append(" — \"").append(title.trim().take(120)).append('"')
                        append('\n')
                        append(CdpCodec.clamp(text.trim(), maxChars))
                    }
                    ToolExecutionResult(head, true, toolTitle = toolTitle, pageURL = href)
                }

                "click" -> {
                    val selector = args.optString("selector", "").trim()
                    if (selector.isEmpty()) return fail("action=click needs `selector`")
                    val js = """
                        (() => {
                          const el = document.querySelector(${JSONObject.quote(selector)});
                          if (!el) return 'no-match';
                          el.scrollIntoView({block: 'center'});
                          el.focus && el.focus();
                          el.click();
                          return 'clicked';
                        })()
                    """.trimIndent()
                    when (val r = client.evaluateString(js)) {
                        "clicked" -> ToolExecutionResult("Clicked $selector.", true, toolTitle = toolTitle)
                        "no-match" -> fail("No element matches $selector")
                        else -> fail("Click failed: $r")
                    }
                }

                "type" -> {
                    val selector = args.optString("selector", "").trim()
                    val value = args.optString("text", "")
                    if (selector.isEmpty()) return fail("action=type needs `selector`")
                    val js = """
                        (() => {
                          const el = document.querySelector(${JSONObject.quote(selector)});
                          if (!el) return 'no-match';
                          el.focus && el.focus();
                          if (el.isContentEditable) { el.textContent = ${JSONObject.quote(value)}; }
                          else { el.value = ${JSONObject.quote(value)}; }
                          el.dispatchEvent(new Event('input', {bubbles: true}));
                          el.dispatchEvent(new Event('change', {bubbles: true}));
                          return 'typed';
                        })()
                    """.trimIndent()
                    when (val r = client.evaluateString(js)) {
                        "typed" -> ToolExecutionResult("Typed ${value.length} characters into $selector.", true, toolTitle = toolTitle)
                        "no-match" -> fail("No element matches $selector")
                        else -> fail("Type failed: $r")
                    }
                }

                "eval" -> {
                    val expr = args.optString("expression", "").trim()
                    if (expr.isEmpty()) return fail("action=eval needs `expression`")
                    val value = client.evaluateString(expr, awaitPromise = true)
                    ToolExecutionResult(CdpCodec.clamp(value, maxChars), true, toolTitle = toolTitle)
                }

                "screenshot" -> {
                    val png = client.screenshotPng()
                    val href = runCatching { client.evaluateString("location.href") }.getOrDefault("")
                    val kb = png.size / 1024
                    ToolExecutionResult(
                        "Captured ${humanSize(png.size)} PNG (${kb} KB) at $href",
                        true,
                        imageData = png,
                        imageMimeType = "image/png",
                        toolTitle = toolTitle,
                        pageURL = href.ifEmpty { null },
                    )
                }

                else -> fail("Unknown action: $action")
            }
        } catch (e: Exception) {
            fail("Lightpanda error: ${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun humanSize(bytes: Int): String = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"
}
