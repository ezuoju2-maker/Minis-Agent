package com.openminis.app.sandbox.offload

import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BrowserUseOffloadHandler(private val app: MinisApp) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = BOOLEAN_FLAGS,
        )
        val compact = args.hasFlag("compact")
        val quiet = args.hasFlag("q", "quiet")
        val withBase64 = args.hasFlag("with-base64", "with_base64")

        if (request.argv.size <= 1 || args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }

        if (!com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(app)) {
            return emitError(
                action = "execute", code = ERR_DISABLED,
                message = "The browser tool is turned off in Settings > Tools.",
                compact = compact, quiet = quiet, exit = 2,
            )
        }

        val inputJson = try {
            buildInputJson(args)
        } catch (e: IllegalArgumentException) {
            return emitError(action = "execute", code = ERR_INVALID_ARGS,
                message = e.message ?: "invalid arguments",
                compact = compact, quiet = quiet, exit = 2)
        }

        val input = BrowserActionInput.parse(inputJson.toString())
            ?: return emitError(action = "execute", code = ERR_INVALID_ARGS,
                message = "Invalid browser_use input. Required: 'action' (one of ${BrowserAction.allValues.joinToString(", ")})",
                compact = compact, quiet = quiet, exit = 2)

        val actionName = input.action.value

        val pool = when (val r = BrowserCliPools.resolve(app, request.sessionId)) {
            is BrowserCliPools.Resolution.Found -> {
                Log.i(TAG, "action=$actionName sid=${request.sessionId?.take(8)} pool=${r.via}")
                r.pool
            }
            is BrowserCliPools.Resolution.SessionGone -> return emitError(
                action = actionName, code = ERR_INTERNAL,
                message = "session ${r.sessionId} no longer exists; cannot run browser_use.",
                compact = compact, quiet = quiet, exit = 1,
            )
        }
        val filesSid = request.sessionId?.let {
            com.openminis.app.sandbox.ExecutionCoordinator.mountedSessionIdFor(it)
        }

        com.openminis.app.service.SessionActivityTracker.updateToolStatus(
            statusForBrowserAction(actionName, inputJson),
        )

        val result: BrowserActionResult = try {
            runBlocking {
                withTimeout(EXECUTE_TIMEOUT_MS) {
                    pool.execute(input, singleTab = true)
                }
            }
        } catch (_: TimeoutCancellationException) {
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = "browser action timed out after ${EXECUTE_TIMEOUT_MS / 1000}s",
                compact = compact, quiet = quiet, exit = 1)
        } catch (e: Throwable) {
            Log.w(TAG, "execute failed: ${e.message}", e)
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = e.message ?: "browser action failed",
                compact = compact, quiet = quiet, exit = 1)
        }

        if (!result.success) {
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = result.text.ifEmpty { "browser action failed" },
                compact = compact, quiet = quiet, exit = 1)
        }

        val data = encodeData(result, withBase64 = withBase64, filesSid = filesSid)
        val envelope = envelope(actionName, data)
        return NativeOffloadResult(0, emit(envelope, data, compact = compact, quiet = quiet))
    }


    private fun buildInputJson(args: OffloadArgs): JSONObject {
        args.get("json")?.let { raw ->
            return try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("--json is not valid JSON: ${e.message}")
            }
        }

        val action = args.positional.firstOrNull()
            ?: throw IllegalArgumentException("missing <action>. Use --help for usage.")

        val obj = JSONObject().put("action", action)
        args.get("url")?.let { obj.put("url", it) }
        args.get("selector")?.let { obj.put("selector", it) }
        args.get("text")?.let { obj.put("text", it) }
        args.getInt("coordinate-x", "coordinate_x", "x")?.let { obj.put("coordinate_x", it) }
        args.getInt("coordinate-y", "coordinate_y", "y")?.let { obj.put("coordinate_y", it) }
        args.get("direction")?.let { obj.put("direction", it) }
        args.getInt("amount")?.let { obj.put("amount", it) }
        args.get("script")?.let { obj.put("script", it) }
        args.get("user-agent", "user_agent", "ua")?.let { obj.put("user_agent", it) }
        args.getInt("max-depth", "max_depth")?.let { obj.put("max_depth", it) }
        args.getInt("tab-id", "tab_id")?.let { obj.put("tab_id", it) }
        args.getInt("width")?.let { obj.put("viewport_width", it) }
        args.getInt("height")?.let { obj.put("viewport_height", it) }
        if (args.hasFlag("reset")) obj.put("reset", true)
        args.get("item-selector", "item_selector")?.let { obj.put("item_selector", it) }
        args.getInt("scroll-count", "scroll_count")?.let { obj.put("scroll_count", it) }
        args.getInt("timeout")?.let { obj.put("timeout", it) }
        if (args.hasFlag("fuzzy")) obj.put("fuzzy", true)
        args.get("keywords")?.let { raw ->
            val tokens = raw.split(Regex("[,\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isNotEmpty()) obj.put("keywords", JSONArray(tokens))
        }
        val cookiesFile = args.get("cookies-file", "cookies_file")
        val cookiesRaw: String? = if (cookiesFile != null) {
            val host = PRootKernel.resolveHostPath(cookiesFile)
                ?: throw IllegalArgumentException("--cookies-file: cannot resolve path '$cookiesFile'")
            runCatching { host.readText() }.getOrNull()
                ?: throw IllegalArgumentException("--cookies-file: could not read '$cookiesFile'")
        } else {
            args.get("cookies")
        }
        cookiesRaw?.let { raw ->
            val arr = runCatching { JSONArray(raw) }.getOrNull()
                ?: parseNetscapeCookies(raw)
                ?: throw IllegalArgumentException(
                    "--cookies (or --cookies-file content) must be a JSON ARRAY of cookie " +
                        "objects, or a Netscape cookies.txt file. JSON object fields: name + " +
                        "value (required); optional domain (defaults to current page host), " +
                        "path (defaults to \"/\"), secure (bool), http_only (bool, alias " +
                        "httpOnly), expires (unix seconds, alias expirationDate), sameSite. " +
                        "e.g. '[{\"name\":\"sid\",\"value\":\"abc\",\"domain\":\".x.com\",\"secure\":true}]'. " +
                        "If the shell is mangling the quotes, write the JSON array to a file " +
                        "and pass --cookies-file <path>.",
                )
            obj.put("cookies", arr)
        }
        return obj
    }

    private fun parseNetscapeCookies(text: String): JSONArray? {
        val out = JSONArray()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            var httpOnly = false
            var work = t
            when {
                work.startsWith("#HttpOnly_") -> { httpOnly = true; work = work.removePrefix("#HttpOnly_") }
                work.startsWith("#") -> continue
            }
            val f = work.split("\t")
            if (f.size < 7) continue
            val name = f[5]
            if (name.isEmpty()) continue
            val c = JSONObject().apply {
                put("name", name)
                put("value", f[6])
                put("domain", f[0])
                put("path", f[2])
                if (f[3].equals("TRUE", ignoreCase = true)) put("secure", true)
                if (httpOnly) put("http_only", true)
                f[4].toLongOrNull()?.takeIf { it > 0 }?.let { put("expires", it) }
            }
            out.put(c)
        }
        return if (out.length() > 0) out else null
    }


    private fun encodeData(r: BrowserActionResult, withBase64: Boolean, filesSid: String?): JSONObject {
        val out = JSONObject()
        out.put("text", r.text)
        out.put("success", r.success)
        r.pageURL?.takeIf { it.isNotEmpty() }?.let { out.put("page_url", it) }

        val browserHostDir: File? = (
            filesSid?.let { com.openminis.app.sandbox.SessionMounts.sessionDir(app.filesDir, it, "browser") }
                ?: PRootKernel.resolveHostPath(VAR_MINIS_BROWSER)
            )?.also {
            try { it.mkdirs() } catch (_: Throwable) {  }
        }

        var persistedImagePath: String? = null
        val base64 = r.base64Image
        if (!base64.isNullOrEmpty() && browserHostDir != null) {
            val bytes = try { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }
                catch (_: Exception) { null }
            if (bytes != null) {
                val filename = "screenshot_${System.currentTimeMillis()}.jpg"
                val dest = File(browserHostDir, filename)
                if (runCatching { dest.writeBytes(bytes) }.isSuccess) {
                    val linuxPath = "$VAR_MINIS_BROWSER/$filename"
                    persistedImagePath = linuxPath
                    out.put("image_path", linuxPath)
                    out.put("minis_url", "minis://browser/$filename")
                } else {
                    Log.w(TAG, "Failed to persist screenshot to ${dest.absolutePath}")
                }
            }
        } else if (!base64.isNullOrEmpty()) {
            Log.w(TAG, "No /var/minis/browser mount — falling back to base64-only output")
        }

        if (persistedImagePath == null) {
            r.imageFilePath?.takeIf { it.isNotEmpty() }?.let { out.put("image_path", it) }
        }

        if (withBase64 && !base64.isNullOrEmpty()) {
            out.put("image_base64", base64)
        }

        val fname = r.fetchedFileName
        if (!fname.isNullOrEmpty()) {
            out.put("fetched_file", fname)
            val data = r.fetchedFileData
            if (data != null) {
                out.put("fetched_bytes", data.size)
                if (browserHostDir != null) {
                    val dest = File(browserHostDir, fname)
                    if (runCatching { dest.writeBytes(data) }.isSuccess) {
                        out.put("fetched_path", "$VAR_MINIS_BROWSER/$fname")
                        out.put("fetched_minis_url", "minis://browser/$fname")
                    } else {
                        Log.w(TAG, "Failed to persist fetched file to ${dest.absolutePath}")
                    }
                }
            }
        }

        return out
    }


    private fun envelope(action: String, data: JSONObject): JSONObject = JSONObject()
        .put("ok", true)
        .put("tool", TOOL_NAME)
        .put("action", action)
        .put("data", data)
        .put("timestamp", isoTimestamp())

    private fun errorEnvelope(action: String, code: String, message: String): JSONObject {
        val err = JSONObject().put("code", code).put("message", message)
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("error", err)
            .put("timestamp", isoTimestamp())
    }

    private fun emitError(
        action: String,
        code: String,
        message: String,
        compact: Boolean,
        quiet: Boolean,
        exit: Int,
    ): NativeOffloadResult {
        val env = errorEnvelope(action, code, message)
        val payload = if (quiet) env.optJSONObject("error") ?: env else env
        return NativeOffloadResult(exit, serialize(payload, compact) + "\n")
    }

    private fun emit(envelope: JSONObject, data: JSONObject, compact: Boolean, quiet: Boolean): String {
        val payload: Any = if (quiet) data else envelope
        return serialize(payload, compact) + "\n"
    }

    private fun serialize(payload: Any, compact: Boolean): String {
        if (payload !is JSONObject && payload !is JSONArray) return payload.toString()
        return if (compact) payload.toString() else (payload as? JSONObject)?.toString(2)
            ?: (payload as JSONArray).toString(2)
    }

    private fun isoTimestamp(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date())
    }

    private fun statusForBrowserAction(action: String, input: org.json.JSONObject): String {
        val url = input.optString("url", "").takeIf { it.isNotBlank() }
        val sel = input.optString("selector", "").takeIf { it.isNotBlank() }
        val txt = input.optString("text", "").takeIf { it.isNotBlank() }
        val dir = input.optString("direction", "").takeIf { it.isNotBlank() }
        return when (action) {
            "navigate"           -> "browser: navigate ${url ?: ""}".trim()
            "screenshot"         -> "browser: screenshot"
            "click"              -> "browser: click ${sel ?: ""}".trim()
            "type"               -> "browser: type"
            "scroll"             -> "browser: scroll${dir?.let { " $it" } ?: ""}"
            "scroll_and_collect" -> "browser: scroll & collect"
            "get_text"           -> "browser: read text"
            "get_readable"       -> "browser: extract readable"
            "get_backbone"       -> "browser: extract structure"
            "fetch"              -> "browser: fetch ${url ?: ""}".trim()
            "execute_js"         -> "browser: run JS"
            "set_viewport"       -> "browser: set viewport"
            "get_cookies"        -> "browser: read cookies"
            "set_cookies"        -> "browser: write cookies"
            "go_back", "back"    -> "browser: go back"
            "reload"             -> "browser: reload"
            "find"               -> "browser: find ${txt ?: sel ?: ""}".trim()
            else                 -> "browser: $action"
        }
    }

    companion object {
        private const val TAG = "BrowserUseOffload"
        private const val TOOL_NAME = "minis-browser-use"
        private const val VAR_MINIS_BROWSER = "/var/minis/browser"
        private const val EXECUTE_TIMEOUT_MS = 90_000L

        private val BOOLEAN_FLAGS = setOf(
            "compact", "quiet", "with-base64", "with_base64",
            "reset", "fuzzy", "help",
        )

        private const val ERR_INVALID_ARGS = "invalid_args"
        private const val ERR_DISABLED = "tool_disabled"
        private const val ERR_INTERNAL = "internal_error"

        private val HELP = """
minis-browser-use - Drive the in-app WebView from the shell

USAGE:
  minis-browser-use <action> [options]
  minis-browser-use --json '<json>'
  minis-browser-use --help

ACTIONS:
  navigate        --url <url>
  screenshot
  click           --selector <css> | --coordinate-x <n> --coordinate-y <n>
  type            --selector <css> --text <text>
  get_text        --selector <css>
  scroll          [--selector <css>] [--direction up|down] [--amount <px>]
  get_page_info
  execute_js      --script <js>
  find_elements   --selector <css>
  hover           --selector <css>
  get_readable
  set_user_agent  --user-agent mobile_chrome|desktop_chrome   (this tab only; aliases mobile, desktop)
  set_viewport    --width <n> --height <n> | --reset
  get_backbone    [--max-depth <n>]
  fetch           --url <url>
  new_tab         [--url <url>]
  close_tab       [--tab-id <n>]
  list_tabs
  get_cookies     --keywords <list> [--fuzzy]
  set_cookies     --cookies <json-array> | --cookies-file <path>
                  Content (for both: the file holds the SAME JSON array):
                    [{"name":"n","value":"v",            // required
                      "domain":".x.com","path":"/",       // optional
                      "secure":true,"http_only":true,     // optional
                      "expires":1893456000}]              // optional unix secs
                  domain defaults to the current page host, path to "/".
                  Aliases accepted: httpOnly, expirationDate, sameSite.
                  --cookies-file also accepts a Netscape cookies.txt
                  (curl/wget/browser export, TAB-separated).
                  Prefer --cookies-file: the JSON's quotes/braces survive
                  the shell intact — write the array to a temp file first.
  scroll_and_collect --scroll-count <n> --item-selector <css> [--keywords <list>]
  wait_for_dom_stable [--timeout <ms>]

COMMON OPTIONS:
  --tab-id <n>     Route the action to a specific tab (default: active tab)
  --json '<s>'     Pass the full input object as JSON (matches browser_use schema)
  --with-base64    Also include image_base64 in the screenshot output. Off by
                   default — screenshots are persisted to /var/minis/browser/
                   and referenced via image_path + minis_url.
  --compact        Minimize JSON output
  -q, --quiet      Output only the data field
  -h, --help       Show this help message

OUTPUT:
  JSON envelope with a `data` object containing:
    text              Human-readable result the agent would see
    success           true / false
    page_url          URL after the action (when applicable)
    image_path        Linux path of the persisted JPEG under /var/minis/browser/
    minis_url         minis://browser/<filename> — stable reference for
                      read_image / downstream tools
    image_base64      Base64 JPEG (only when --with-base64 is set)
    fetched_file      Filename of the downloaded resource (fetch action)
    fetched_path      Linux path of the persisted download under /var/minis/browser/
    fetched_minis_url minis://browser/<filename> for the download

EXAMPLES:
  minis-browser-use navigate --url https://example.com
  minis-browser-use screenshot
  minis-browser-use click --selector '.btn-primary'
  minis-browser-use type --selector 'input[name=q]' --text 'hello'
  minis-browser-use execute_js --script 'return document.title'
  minis-browser-use --json '{"action":"navigate","url":"https://x.com"}'
""".trimIndent() + "\n"
    }
}
