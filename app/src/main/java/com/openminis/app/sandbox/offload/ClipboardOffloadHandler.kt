package com.openminis.app.sandbox.offload

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONArray
import org.json.JSONObject

class ClipboardOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        OffloadGate.enforce("clipboard", "android-clipboard", args, request)?.let { return it }

        val cm = try {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (_: Throwable) { null }
            ?: run {
                val body = JSONObject().put("error", "service_unavailable")
                    .put("message", "ClipboardManager not available on this device.")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
            }

        return try {
            when (val sub = args.positional.firstOrNull() ?: "get") {
                "get" -> doGet(cm, args)
                "set" -> doSet(cm, args)
                "clear" -> doClear(cm, args)
                "status" -> doStatus(cm, args)
                else -> NativeOffloadResult(2, "android-clipboard: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException: ${e.message}")
            val body = JSONObject().put("error", "clipboard_denied")
                .put("message", "Clipboard access was denied by the system. On MIUI/HyperOS the user may have revoked clipboard permission in the privacy panel.")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            val body = JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }


    private fun doGet(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return NativeOffloadResult(0, "")
        }
        val text = try { clip.getItemAt(0).coerceToText(context).toString() } catch (_: Throwable) { "" }
        val body = OffloadOutput.formatBody(text.trimEnd('\n'), args)
        return NativeOffloadResult(0, "$body\n")
    }

    private fun doSet(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        val text = args.get("text") ?: args.positional.drop(1).joinToString(" ")
        if (text.isBlank()) {
            return NativeOffloadResult(
                2,
                "android-clipboard set: missing <text> (use --text \"...\" or pass positional args)\n",
            )
        }
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
        val label = args.get("label") ?: "DeepSeek"
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody("Copied ${text.length} characters to clipboard.", args) + "\n",
        )
    }

    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground()) return backgroundError(args)
        cm.setPrimaryClip(ClipData.newPlainText("", ""))
        return NativeOffloadResult(0, OffloadOutput.formatBody("Clipboard cleared.", args) + "\n")
    }

    private fun doStatus(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
        val clip = cm.primaryClip
        val description = cm.primaryClipDescription
        val itemCount = clip?.itemCount ?: 0
        val mimeTypes = JSONArray()
        val categories = JSONArray()
        var hasStrings = false
        var hasUrls = false
        var hasImages = false
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                val mime = description.getMimeType(i)
                mimeTypes.put(mime)
                when {
                    mime.startsWith("text/") || mime == ClipDescription_MIMETYPE_TEXT_PLAIN -> hasStrings = true
                    mime == ClipDescription_MIMETYPE_TEXT_URILIST -> hasUrls = true
                    mime.startsWith("image/") -> hasImages = true
                }
            }
        }
        if (hasStrings) categories.put("strings")
        if (hasUrls) categories.put("urls")
        if (hasImages) categories.put("images")
        val hasContent = itemCount > 0 && (hasStrings || hasUrls || hasImages || mimeTypes.length() > 0)
        val body = JSONObject()
            .put("has_content", hasContent)
            .put("has_strings", hasStrings)
            .put("has_urls", hasUrls)
            .put("has_images", hasImages)
            .put("types", categories)
            .put("mime_types", mimeTypes)
            .put("item_count", itemCount)
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }


    private fun isAppForeground(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true
            val procs = am.runningAppProcesses.orEmpty()
            val pid = android.os.Process.myPid()
            procs.any { it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
        } catch (_: Throwable) {
            true
        }
    }

    private fun backgroundError(args: OffloadArgs) = NativeOffloadResult(
        1,
        OffloadOutput.formatBody(
            JSONObject().put("error", "clipboard_requires_foreground")
                .put("message", "Android 10+ blocks clipboard access when the app is not in the foreground. Ask the user to bring Minis to the foreground and retry.")
                .toString(),
            args,
        ) + "\n",
    )

    companion object {
        private const val TAG = "ClipboardOffload"
        private const val ClipDescription_MIMETYPE_TEXT_PLAIN = "text/plain"
        private const val ClipDescription_MIMETYPE_TEXT_URILIST = "text/uri-list"
        private const val HELP = """android-clipboard — access the device clipboard

Usage:
  android-clipboard <command> [options]

Running without a subcommand is the same as `get` (default subcommand).

COMMANDS:
  get                  Print current clipboard text
  set --text "<text>"  Copy text to clipboard (preferred form)
  set <text...>        Copy text to clipboard (legacy positional form)
  clear                Empty the clipboard
  status               Print clipboard content types + item count

OPTIONS:
  --text <value>       Text to write (for `set`)
  --label <name>       Optional ClipData label (default: DeepSeek)
  --help, -h           Show this help message
  --compact            Minimize JSON output
  -q, --quiet          Output only data field

EXAMPLES:
  android-clipboard get
  android-clipboard set --text "Hello world"
  android-clipboard set Hello world
  android-clipboard clear
  android-clipboard status

Android 10+ blocks clipboard access in the background — errors include
{"error":"clipboard_requires_foreground"} when the app is not focused.
"""
    }
}
