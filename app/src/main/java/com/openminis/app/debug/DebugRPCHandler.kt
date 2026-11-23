package com.openminis.app.debug

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class DebugRPCHandler(private val context: Context) {

    companion object {
        var currentActivity: WeakReference<Activity>? = null

        private val CRASH_NAME = Regex("""^(native-)?crash-.*\.log$""")
    }

    suspend fun handle(json: String): String {
        val parsed = try {
            JSONObject(json)
        } catch (_: Exception) {
            return errorResponse(-32700, "Parse error", null)
        }

        val id = parsed.opt("id")
        val jsonrpc = parsed.optString("jsonrpc")
        if (jsonrpc != "2.0") {
            return errorResponse(-32600, "Invalid Request: missing jsonrpc 2.0", id)
        }

        val method = parsed.optString("method")
        if (method.isEmpty()) {
            return errorResponse(-32600, "Invalid Request: missing method", id)
        }

        val params = parsed.optJSONObject("params") ?: JSONObject()

        return try {
            val result = dispatch(method, params)
            successResponse(result, id)
        } catch (e: RPCException) {
            errorResponse(e.code, e.message ?: "Unknown error", id)
        } catch (e: Exception) {
            errorResponse(-32000, e.message ?: "Internal error", id)
        }
    }

    private suspend fun dispatch(method: String, params: JSONObject): Any {
        return when (method) {
            "rpc.discover" -> DebugMethodRegistry.discover()
            "debug.appInfo" -> handleAppInfo()
            "debug.limits.get" -> LoadTestDebugMethods.limitsGet()
            "debug.limits.set" -> LoadTestDebugMethods.limitsSet(params)
            "debug.loadtest.snapshot" -> LoadTestDebugMethods.snapshot(context)
            "debug.loadtest.seedSession" -> LoadTestDebugMethods.seedSession(context, params)
            "debug.screenshot" -> handleScreenshot(params)
            "debug.ls" -> handleLS(params)
            "debug.rawLs" -> handleRawLS(params)
            "debug.readFile" -> handleReadFile(params)
            "debug.logs.list" -> handleLogsList()
            "debug.backup.export" -> BackupDebugMethods.backupExport(context, params)
            "debug.backup.restore" -> BackupDebugMethods.backupRestore(context, params)
            "debug.backup.upload" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BackupDebugMethods.backupUpload(context, params) }
            "debug.backup.remotes.addWebdav" -> BackupDebugMethods.remotesAddWebdav(context, params)
            "debug.backup.remotes.remove" -> BackupDebugMethods.remotesRemove(context, params)
            in NetDebugMethods.names -> NetDebugMethods.dispatch(context, method, params)
            "debug.subAgents.list" -> BackupDebugMethods.subAgentsList(context)
            "debug.subAgents.upsert" -> BackupDebugMethods.subAgentsUpsert(context, params)
            "debug.subAgents.delete" -> BackupDebugMethods.subAgentsDelete(context, params)
            "debug.logs.read" -> handleLogsRead(params)
            "debug.logs.setEnabled" -> handleLogsSetEnabled(params)
            "debug.crash.list" -> handleCrashList(params)
            "debug.crash.read" -> handleCrashRead(params)
            "debug.tap" -> handleTap(params)
            "debug.scroll" -> handleScroll(params)
            "debug.inputText" -> handleInputText(params)
            "debug.setClipboard" -> handleSetClipboard(params)
            "debug.voice.injectFailedAudio" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleVoiceInjectFailedAudio(params)
            }
            "debug.llmRequests" -> handleLLMRequests(params)
            "debug.llmRequests.clear" -> { LLMRequestLog.clear(); JSONObject().put("cleared", true) }
            "debug.agentTrace" -> handleAgentTrace(params)
            "debug.fetch" -> handleFetch(params)
            "debug.shellExecute" -> handleShellExecute(params)
            "debug.update.check" -> handleUpdateCheck()
            "debug.update.download" -> handleUpdateDownload(params)
            "debug.update.install" -> handleUpdateInstall(params)
            "debug.permissions.list" -> handlePermissionsList()

            "debug.viewTree" -> handleViewTree(params)
            "debug.search" -> handleSearch(params)
            "debug.inspect" -> handleInspect(params)
            "debug.highlight" -> handleHighlight(params)
            "debug.cloudSync" -> handleCloudSync()
            "debug.writeFile" -> handleWriteFile(params)
            "debug.screenshot.capture" -> handleScreenshotCapture(params)
            "debug.screenshot.list" -> DebugScreenshotRing.listJson()
            "debug.screenshot.get" -> handleScreenshotGet(params)
            "debug.screenshot.clear" -> JSONObject().put("cleared", DebugScreenshotRing.clearAll())

            "debug.browser.listTabs" -> BrowserDebugMethods.listTabs(context)
            "debug.browser.pageInfo" -> BrowserDebugMethods.pageInfo(context, params)
            "debug.browser.executeJS" -> BrowserDebugMethods.executeJS(context, params)
            "debug.browser.getReadable" -> BrowserDebugMethods.getReadable(context, params)
            "debug.browser.getText" -> BrowserDebugMethods.getText(context, params)
            "debug.browser.screenshot" -> BrowserDebugMethods.screenshot(context, params)
            "debug.browser.cookies" -> BrowserDebugMethods.cookies(params)

            "provider.types" -> ProviderDebugMethods.types()
            "provider.instances.list" -> ProviderDebugMethods.instancesList(context, params)
            "provider.models.list" -> ProviderDebugMethods.modelsList(context, params)
            "provider.groups.list" -> ProviderDebugMethods.groupsList(context, params)
            "provider.quickTest" -> ProviderDebugMethods.quickTest(context, params)
            "provider.export" -> ProviderDebugMethods.export(context, params)
            "provider.import" -> ProviderDebugMethods.import(context, params)

            "provider.instances.create" -> ProviderMutationMethods.instancesCreate(context, params)
            "provider.instances.update" -> ProviderMutationMethods.instancesUpdate(context, params)
            "provider.instances.delete" -> ProviderMutationMethods.instancesDelete(context, params)
            "provider.instances.test" -> ProviderMutationMethods.instancesTest(context, params)
            "provider.models.add" -> ProviderMutationMethods.modelsAdd(context, params)
            "provider.models.update" -> ProviderMutationMethods.modelsUpdate(context, params)
            "provider.models.delete" -> ProviderMutationMethods.modelsDelete(context, params)
            "provider.models.refresh" -> ProviderMutationMethods.modelsRefresh(context, params)
            "provider.models.setAgentLoop" -> ProviderMutationMethods.modelsSetAgentLoop(context, params)
            "provider.groups.create" -> ProviderMutationMethods.groupsCreate(context, params)
            "provider.groups.update" -> ProviderMutationMethods.groupsUpdate(context, params)
            "provider.groups.delete" -> ProviderMutationMethods.groupsDelete(context, params)
            "provider.groups.setDefault" -> ProviderMutationMethods.groupsSetDefault(context, params)
            "provider.groups.setAgentLoop" -> ProviderMutationMethods.groupsSetAgentLoop(context, params)

            "chat.sessions.list" -> ChatDebugMethods.sessionsList(context, params)
            "chat.sessions.get" -> ChatDebugMethods.sessionsGet(context, params)
            "chat.sessions.usage" -> ChatDebugMethods.sessionsUsage(context, params)
            "chat.messages.list" -> ChatDebugMethods.messagesList(context, params)
            "chat.models.list" -> ChatDebugMethods.modelsList(context, params)

            "chat.prompt" -> ChatMutationMethods.prompt(context, params)
            "chat.uiPrompt" -> ChatMutationMethods.uiPrompt(context, params)
            "debug.chat.usageHint" -> UsageHintDebugMethods.show(context, params)
            "chat.retry" -> ChatMutationMethods.retry(context, params)
            "chat.rerunFromToolBlock" -> ChatMutationMethods.rerunFromToolBlock(context, params)
            "chat.session.status" -> ChatMutationMethods.status(context, params)
            "chat.session.cancel" -> ChatMutationMethods.cancel(context, params)
            "chat.session.selectModel" -> ChatMutationMethods.selectModel(context, params)
            "chat.session.delete" -> ChatMutationMethods.delete(context, params)
            "scheduled.runAgentChild" -> ChatMutationMethods.runScheduledAgentChild(context, params)

            "chat.compact.before" -> ChatMutationMethods.compactBefore(context, params)
            "chat.compact.markers.list" -> ChatMutationMethods.compactMarkersList(context, params)
            "chat.compact.revert" -> ChatMutationMethods.compactRevert(context, params)

            "debug.shizuku.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleShizukuExec(params)
            }
            "debug.chatShare.openPicker" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                com.openminis.app.ui.chat.share.ChatShareDebugRpc.openPicker(params)
            }
            "debug.chatExport.render" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                com.openminis.app.ui.chat.share.ChatShareDebugRpc.render(params)
            }
            "debug.modelUse.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleModelUseExec(params)
            }
            "debug.sessions.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleSessionsExec(params)
            }
            "debug.calendar.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleCalendarExec(params)
            }
            "debug.minisConfig.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleMinisConfigExec(params)
            }

            else -> throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
        }
    }


    private fun handleAppInfo(): JSONObject {
        val filesDir = context.filesDir
        return JSONObject().apply {
            put("platform", "android")
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", Build.VERSION.RELEASE)
            put("prootBooted", PRootKernel.isBooted)
            put("filesDir", filesDir.absolutePath)
            put("logFiles", AppLogger.listLogFiles().size)
            put("totalLogSize", AppLogger.totalSize())
            put("diskUsage", JSONObject().apply {
                put("filesDir", dirSize(filesDir))
                put("sessions", dirSize(File(filesDir, "minis-sessions")))
                put("global", dirSize(File(filesDir, "minis-global")))
            })
        }
    }

    private fun dirSize(dir: File): Long =
        com.openminis.app.data.session.SessionStorage.directorySize(dir)


    private suspend fun handleScreenshot(params: JSONObject): JSONObject {
        val scale = params.optDouble("scale", 1.0).toFloat()

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity for screenshot")

        val bitmap = withContext(Dispatchers.Main) {
            captureActivityScreenshot(activity, scale)
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        val pngBytes = baos.toByteArray()
        val base64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP)

        return JSONObject().apply {
            put("base64", base64)
            put("size", pngBytes.size)
            put("encoding", "png")
        }
    }

    private suspend fun captureActivityScreenshot(activity: Activity, scale: Float): Bitmap =
        suspendCancellableCoroutine { cont ->
            val rootView = activity.window.decorView.rootView
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val width = (rootView.width * scale).toInt().coerceAtLeast(1)
                val height = (rootView.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(
                    activity.window, bitmap,
                    { result ->
                        if (result == android.view.PixelCopy.SUCCESS) {
                            cont.resume(bitmap)
                        } else {
                            cont.resume(canvasCapture(rootView, scale))
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } else {
                cont.resume(canvasCapture(rootView, scale))
            }
        }

    private fun canvasCapture(view: android.view.View, scale: Float): Bitmap {
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)
        view.draw(canvas)
        return bitmap
    }


    private fun handleLS(params: JSONObject): Any {
        val path = params.optString("path", "/")
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

        val recursive = params.optBoolean("recursive", false)
        val maxDepth = params.optInt("maxDepth", 3)

        val hostFile = PRootKernel.resolveHostPath(path)
            ?: throw RPCException(-32602, "Cannot resolve path: $path")

        if (!hostFile.isDirectory) throw RPCException(-32602, "Not a directory: $path")

        return if (recursive) {
            listRecursive(hostFile, path, maxDepth, 0)
        } else {
            listFlat(hostFile)
        }
    }

    private fun handleRawLS(params: JSONObject): Any {
        val subPath = params.optString("path", "minis-sessions")
        if (subPath.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")
        val recursive = params.optBoolean("recursive", true)
        val maxDepth = params.optInt("maxDepth", 4)

        val base = context.filesDir
        val target = if (subPath.isEmpty() || subPath == "/") base else File(base, subPath)
        if (!target.exists()) throw RPCException(-32602, "Not found: ${target.absolutePath}")
        if (!target.isDirectory) throw RPCException(-32602, "Not a directory: ${target.absolutePath}")

        val root = JSONObject()
        root.put("root", target.absolutePath)
        root.put("entries",
            if (recursive) listRecursive(target, target.absolutePath, maxDepth, 0)
            else listFlat(target)
        )
        return root
    }

    private fun listFlat(dir: File): JSONArray {
        val files = dir.listFiles() ?: return JSONArray()
        val array = JSONArray()
        for (file in files.sortedBy { it.name }) {
            array.put(JSONObject().apply {
                put("name", file.name)
                put("type", if (file.isDirectory) "directory" else "file")
                put("size", file.length())
                put("modified", file.lastModified())
            })
        }
        return array
    }

    private fun listRecursive(dir: File, virtualPath: String, maxDepth: Int, depth: Int): JSONArray {
        val array = JSONArray()
        val files = dir.listFiles()?.sortedBy { it.name } ?: return array
        for (file in files) {
            val childPath = if (virtualPath == "/") "/${file.name}" else "$virtualPath/${file.name}"
            val entry = JSONObject().apply {
                put("name", file.name)
                put("path", childPath)
                put("type", if (file.isDirectory) "directory" else "file")
                put("size", file.length())
                put("modified", file.lastModified())
            }
            if (file.isDirectory && depth < maxDepth) {
                entry.put("children", listRecursive(file, childPath, maxDepth, depth + 1))
            }
            array.put(entry)
        }
        return array
    }

    private fun handleReadFile(params: JSONObject): JSONObject {
        val path = params.optString("path")
        if (path.isEmpty()) throw RPCException(-32602, "Invalid params: 'path' is required")
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

        val hostFile = PRootKernel.resolveHostPath(path)
            ?: throw RPCException(-32602, "Cannot resolve path: $path")

        if (!hostFile.exists()) throw RPCException(-32602, "File not found: $path")

        val fileSize = hostFile.length()
        val offset = params.optLong("offset", 0)
        val limit = params.optInt("limit", 524_288)
        val forceBase64 = params.optBoolean("base64", false)

        val bytes = hostFile.inputStream().use { stream ->
            if (offset > 0) stream.skip(offset)
            stream.readNBytes(limit)
        }

        val isText = !forceBase64 && bytes.all {
            it in 0x09..0x0D || it in 0x20..0x7E || it.toInt() and 0xFF > 0x7F
        }

        return JSONObject().apply {
            put("size", fileSize)
            if (isText) {
                put("content", String(bytes, Charsets.UTF_8))
                put("encoding", "utf8")
            } else {
                put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                put("encoding", "base64")
            }
            put("bytesRead", bytes.size)
            if (offset + bytes.size < fileSize) put("truncated", true)
        }
    }


    private fun handleLogsList(): JSONObject {
        val files = AppLogger.listLogFiles()
        val array = JSONArray()
        for (file in files) {
            array.put(JSONObject().apply {
                put("name", file.name)
                put("size", file.length())
                put("modified", file.lastModified())
            })
        }
        return JSONObject().apply {
            put("totalSize", AppLogger.totalSize())
            put("files", array)
        }
    }

    private fun handleLogsRead(params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name.isEmpty()) throw RPCException(-32602, "Invalid params: 'name' is required")
        if (name.contains("/") || name.contains("..")) {
            throw RPCException(-32602, "Invalid params: 'name' must be a filename")
        }

        val content = AppLogger.readLog(name)
            ?: throw RPCException(-32602, "Log file not found: $name")

        val offset = params.optInt("offset", 0)
        val limit = params.optInt("limit", 524_288)
        val sliced = content.substring(
            offset.coerceAtMost(content.length),
            (offset + limit).coerceAtMost(content.length),
        )

        return JSONObject().apply {
            put("name", name)
            put("size", content.length)
            put("content", sliced)
            put("bytesRead", sliced.length)
            if (sliced.length < content.length - offset) put("truncated", true)
        }
    }


    private fun crashFiles(): List<File> {
        val dir = File(context.filesDir, "logs")
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && CRASH_NAME.matches(it.name) }
            .sortedByDescending { it.lastModified() }
    }

    private fun handleCrashList(params: JSONObject): JSONObject {
        val limit = params.optInt("limit", 20).coerceIn(1, 200)
        val files = crashFiles()
        val array = JSONArray()
        for (f in files.take(limit)) {
            array.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("modified", f.lastModified())
                put("native", f.name.startsWith("native-crash-"))
                put("summary", crashSummary(f))
            })
        }
        return JSONObject().apply {
            put("count", files.size)
            put("returned", array.length())
            put("crashes", array)
        }
    }

    private fun crashSummary(f: File): String = try {
        val lines = f.bufferedReader().useLines { seq -> seq.take(400).toList() }
        val i = lines.indexOfFirst { it.startsWith("--- Stack Trace ---") }
        val body = if (i >= 0) lines.drop(i + 1) else lines
        val head = body.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val frame = body.firstOrNull { it.trimStart().startsWith("at com.openminis") }
            ?.trim()?.removePrefix("at ").orEmpty()
        listOf(head, frame).filter { it.isNotEmpty() }.joinToString("  |  ")
    } catch (e: Exception) {
        "(unreadable: ${e.message})"
    }

    private fun handleCrashRead(params: JSONObject): JSONObject {
        val files = crashFiles()
        if (files.isEmpty()) throw RPCException(-32000, "No crash reports on device")
        val name = params.optString("name").takeIf { it.isNotEmpty() }
        if (name != null && (name.contains("/") || name.contains(".."))) {
            throw RPCException(-32602, "Invalid params: 'name' must be a filename")
        }
        val file = if (name == null) files.first()
        else files.firstOrNull { it.name == name }
            ?: throw RPCException(-32602, "Crash report not found: $name")

        val full = file.readText()
        val stackOnly = params.optBoolean("stackOnly", true)
        val content = if (!stackOnly) full else {
            val start = full.indexOf("--- Stack Trace ---")
            val end = full.indexOf("--- Logcat")
            when {
                start < 0 -> full
                end > start -> full.substring(0, end).trimEnd()
                else -> full.substring(0, minOf(full.length, start + 4000))
            }
        }
        val limit = params.optInt("limit", 262_144)
        val sliced = content.take(limit)
        return JSONObject().apply {
            put("name", file.name)
            put("modified", file.lastModified())
            put("native", file.name.startsWith("native-crash-"))
            put("fileSize", file.length())
            put("stackOnly", stackOnly)
            put("content", sliced)
            if (sliced.length < content.length) put("truncated", true)
        }
    }


    private suspend fun handleTap(params: JSONObject): JSONObject {
        val x = params.optDouble("x", -1.0).toFloat()
        val y = params.optDouble("y", -1.0).toFloat()
        if (x < 0 || y < 0) throw RPCException(-32602, "Invalid params: 'x' and 'y' are required")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        withContext(Dispatchers.Main) {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(down)
            down.recycle()

            val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(up)
            up.recycle()
        }

        return JSONObject().apply {
            put("ok", true)
            put("point", JSONObject().put("x", x).put("y", y))
        }
    }

    private suspend fun handleScroll(params: JSONObject): JSONObject {
        val x = params.optDouble("x", -1.0).toFloat()
        val y = params.optDouble("y", -1.0).toFloat()
        val deltaX = params.optDouble("deltaX", 0.0).toFloat()
        val deltaY = params.optDouble("deltaY", 0.0).toFloat()

        if (x < 0 || y < 0) throw RPCException(-32602, "Invalid params: 'x' and 'y' are required")
        if (deltaX == 0f && deltaY == 0f) throw RPCException(-32602, "At least one of deltaX/deltaY must be non-zero")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        withContext(Dispatchers.Main) {
            val downTime = SystemClock.uptimeMillis()
            val steps = 10
            val stepDuration = 16L

            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(down)
            down.recycle()

            for (i in 1..steps) {
                val frac = i.toFloat() / steps
                val moveX = x + deltaX * frac
                val moveY = y + deltaY * frac
                val move = MotionEvent.obtain(downTime, downTime + i * stepDuration, MotionEvent.ACTION_MOVE, moveX, moveY, 0)
                move.source = InputDevice.SOURCE_TOUCHSCREEN
                activity.dispatchTouchEvent(move)
                move.recycle()
            }

            val up = MotionEvent.obtain(downTime, downTime + (steps + 1) * stepDuration, MotionEvent.ACTION_UP, x + deltaX, y + deltaY, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(up)
            up.recycle()
        }

        return JSONObject().apply {
            put("ok", true)
            put("start", JSONObject().put("x", x).put("y", y))
            put("delta", JSONObject().put("x", deltaX).put("y", deltaY))
        }
    }

    private suspend fun handleInputText(params: JSONObject): JSONObject {
        val text = params.optString("text")
        if (text.isEmpty()) throw RPCException(-32602, "Invalid params: 'text' is required")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        withContext(Dispatchers.IO) {
            val instrumentation = android.app.Instrumentation()
            instrumentation.sendStringSync(text)
        }

        return JSONObject().apply {
            put("ok", true)
            put("length", text.length)
        }
    }

    private fun handleVoiceInjectFailedAudio(params: JSONObject): JSONObject {
        val b64 = params.optString("wavBase64", "").ifEmpty { throw RPCException(-32602, "Missing 'wavBase64' param") }
        val wav = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val seconds = ((wav.size - 44).coerceAtLeast(0) / 32000.0)
        com.openminis.app.ui.chat.voice.VoiceSendGate.recordFailure(
            com.openminis.app.speech.SpeechRecognitionManager.FailedAudio(
                wav = wav,
                seconds = seconds,
                error = com.openminis.app.speech.RecognitionError.NETWORK,
                message = "injected by debug.voice.injectFailedAudio",
                engineId = params.optString("engineId", "provider"),
            ),
            com.openminis.app.ui.chat.voice.VoiceSendGate.generation,
        )
        return JSONObject().put("ok", true).put("bytes", wav.size).put("seconds", seconds)
    }

    private suspend fun handleSetClipboard(params: JSONObject): JSONObject {
        val text = params.optString("text")
        if (text.isEmpty()) throw RPCException(-32602, "Invalid params: 'text' is required")
        val label = params.optString("label", "minis-debug")

        withContext(Dispatchers.Main) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        }

        val readBack = withContext(Dispatchers.Main) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.length ?: -1
        }

        return JSONObject().apply {
            put("ok", readBack == text.length)
            put("length", text.length)
            put("clipboardLength", readBack)
        }
    }


    private fun handleLLMRequests(params: JSONObject): JSONObject {
        if (params.optBoolean("fullLast", false)) {
            return LLMRequestLog.lastFullJSON() ?: JSONObject().put("error", "no request captured yet")
        }
        val last = if (params.has("last") && !params.isNull("last")) params.optInt("last", -1).takeIf { it > 0 } else null
        val raw = LLMRequestLog.toJSON(last)
        if (!params.optBoolean("formatted", false)) return raw
        return JSONObject().apply {
            put("count", raw.optInt("count", 0))
            put("text", formatLLMRequestsText(raw))
        }
    }

    private fun formatLLMRequestsText(raw: JSONObject): String {
        val arr = raw.optJSONArray("requests") ?: return ""
        val sb = StringBuilder()
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val provider = e.optString("provider")
            val timestamp = e.optLong("timestamp", 0)
            val durMs = e.optLong("durationMs", 0)
            val status = e.optInt("responseStatusCode", 0)
            val usage = e.optJSONObject("usage")
            val usageStr = if (usage != null) {
                buildString {
                    append("in:").append(usage.optInt("inputTokens", usage.optInt("input_tokens", 0)))
                    append(" out:").append(usage.optInt("outputTokens", usage.optInt("output_tokens", 0)))
                    val cr = usage.optInt("cacheReadTokens", usage.optInt("cache_read_tokens", 0))
                    if (cr > 0) append(" cache_read:").append(cr)
                }
            } else ""
            sb.append("// --- #").append(i + 1).append(' ')
                .append(provider).append(' ')
                .append(if (timestamp > 0) ts.format(java.util.Date(timestamp)) else "")
                .append(' ').append(durMs).append("ms")
                .append(" HTTP ").append(status)
                .append(if (usageStr.isNotEmpty()) " | $usageStr" else "")
                .append(" ---\n")
            sb.append("// ").append(e.optString("requestMethod"))
                .append(' ').append(e.optString("requestURL")).append('\n')
            val body = e.opt("requestBody")
            if (body != null) sb.append(body.toString()).append('\n')
            val resp = e.optString("responseBody", "")
            if (resp.isNotEmpty()) {
                sb.append("// --- Response (").append(resp.length).append(" chars) ---\n")
                sb.append(resp).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun handleAgentTrace(params: JSONObject): JSONObject {
        val onlyLast = params.optBoolean("last", false)
        if (onlyLast) {
            val tail = LLMRequestLog.getLast(1)
            if (tail.isEmpty()) return JSONObject().put("traces", org.json.JSONArray())
            val full = LLMRequestLog.toJSON(1)
            val arr = full.optJSONArray("requests")
            return arr?.optJSONObject(0) ?: JSONObject().put("traces", org.json.JSONArray())
        }
        val full = LLMRequestLog.toJSON(null)
        return JSONObject().put("traces", full.optJSONArray("requests") ?: org.json.JSONArray())
    }

    private fun handleLogsSetEnabled(params: JSONObject): JSONObject {
        if (!params.has("enabled")) throw RPCException(-32602, "Missing 'enabled' param")
        val enabled = params.optBoolean("enabled", false)
        AppLogger.setEnabled(context, enabled)
        return JSONObject().put("enabled", AppLogger.isEnabled(context))
    }


    private fun metaObject(): JSONObject {
        return JSONObject().apply {
            put("app", "MinisApp")
            put("version", BuildConfig.VERSION_NAME)
            put("build", BuildConfig.VERSION_CODE)
            put("buildDate", com.openminis.app.AppBuildInfo.buildDate(context))
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("platform", "android")
        }
    }

    private fun successResponse(result: Any, id: Any?): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
            put("_meta", metaObject())
        }.toString()
    }

    fun errorJSON(code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
            put("_meta", metaObject())
        }.toString()
    }

    private fun errorResponse(code: Int, message: String, id: Any?): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
            put("_meta", metaObject())
        }.toString()
    }

    private fun handleFetch(params: JSONObject): JSONObject {
        val url = params.optString("url").ifEmpty { throw RPCException(-32602, "Missing 'url' param") }
        val result = JSONObject()

        try {
            val host = java.net.URI(url).host
            val addrs = java.net.InetAddress.getAllByName(host)
            result.put("dns", addrs.joinToString(", ") { it.hostAddress })
        } catch (e: Exception) {
            result.put("dns_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            result.put("httpurlconn_status", conn.responseCode)
            conn.disconnect()
        } catch (e: Exception) {
            result.put("httpurlconn_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            result.put("okhttp_status", resp.code)
            resp.close()
        } catch (e: Exception) {
            result.put("okhttp_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val selector = java.net.ProxySelector.getDefault()
            val proxies = selector.select(java.net.URI(url))
            result.put("proxies", proxies.joinToString(", ") { it.toString() })
        } catch (e: Exception) {
            result.put("proxy_error", e.message)
        }

        return result
    }


    private suspend fun handleShellExecute(params: JSONObject): JSONObject {
        val command = params.optString("command").ifEmpty {
            throw RPCException(-32602, "Missing 'command' param")
        }
        val session = params.optString("session", "debug-rpc").ifEmpty { "debug-rpc" }
        val timeoutSec = params.optInt("timeout", 60).coerceIn(1, 900)
        val requested = params.optString("strategy", "").lowercase()
        val previousStrategy = ExecutionCoordinator.executionStrategy
        when (requested) {
            "fresh" -> ExecutionCoordinator.executionStrategy =
                com.openminis.app.sandbox.ShellExecutionStrategy.FRESH_PROCESS
            "warm" -> ExecutionCoordinator.executionStrategy =
                com.openminis.app.sandbox.ShellExecutionStrategy.WARM_SHELL
            "" -> Unit
            else -> throw RPCException(-32602, "strategy must be 'warm' or 'fresh'")
        }

        val capturedUrls = mutableListOf<String>()
        val result = try {
            ExecutionCoordinator.execute(
                sessionId = session,
                command = command,
                timeout = timeoutSec * 1000L,
                lineCallback = { rawLine ->
                    val (_, urls) = com.openminis.app.terminal.MinisUrlMarker.extract(rawLine)
                    capturedUrls.addAll(urls)
                },
            )
        } catch (e: Exception) {
            throw RPCException(-32000, "Shell execute failed: ${e.message}")
        } finally {
            ExecutionCoordinator.executionStrategy = previousStrategy
        }
        for (raw in capturedUrls) {
            com.openminis.app.terminal.MinisOpenUrlBroker.offer(raw)
        }
        return JSONObject()
            .put("output", result.output)
            .put("exit_code", result.exitCode)
            .put("session", session)
            .put("strategy", requested.ifEmpty { previousStrategy.name.lowercase() })
            .put("duration_ms", result.durationMs)
            .put("queue_note", result.queueNote ?: JSONObject.NULL)
    }

    private suspend fun handleUpdateCheck(): JSONObject {
        return when (val r = com.openminis.app.data.UpdateChecker.check(context, reason = "rpc")) {
            is com.openminis.app.data.UpdateChecker.CheckResult.UpdateAvailable -> JSONObject()
                .put("status", "update_available")
                .put("tag_name", r.tagName)
                .put("version_name", r.versionName)
                .put("version_code", r.versionCode ?: JSONObject.NULL)
                .put("sha256", r.sha256 ?: JSONObject.NULL)
                .put("release_name", r.releaseName)
                .put("apk_url", r.apkUrl)
                .put("apk_size", r.apkSizeBytes)
                .put("changelog", r.changelog)
            com.openminis.app.data.UpdateChecker.CheckResult.UpToDate ->
                JSONObject().put("status", "up_to_date")
            com.openminis.app.data.UpdateChecker.CheckResult.NoReleaseAvailable ->
                JSONObject().put("status", "no_release")
            is com.openminis.app.data.UpdateChecker.CheckResult.NoApkAsset ->
                JSONObject().put("status", "no_apk_asset").put("tag_name", r.tagName)
            com.openminis.app.data.UpdateChecker.CheckResult.Forbidden ->
                JSONObject().put("status", "forbidden")
            com.openminis.app.data.UpdateChecker.CheckResult.NetworkUnreachable ->
                JSONObject().put("status", "network_unreachable")
            is com.openminis.app.data.UpdateChecker.CheckResult.Error ->
                JSONObject().put("status", "error").put("message", r.message)
        }
    }

    private suspend fun handleUpdateDownload(params: JSONObject): JSONObject {
        val url = params.optString("url").ifEmpty {
            throw RPCException(-32602, "Missing 'url' parameter")
        }
        return when (val r = com.openminis.app.data.UpdateChecker.download(context, url)) {
            is com.openminis.app.data.UpdateChecker.DownloadResult.Success -> JSONObject()
                .put("status", "ok")
                .put("path", r.file.absolutePath)
                .put("size", r.file.length())
            is com.openminis.app.data.UpdateChecker.DownloadResult.Error -> JSONObject()
                .put("status", "error")
                .put("message", r.message)
        }
    }

    private fun handleUpdateInstall(params: JSONObject): JSONObject {
        val path = params.optString("path").ifEmpty {
            throw RPCException(-32602, "Missing 'path' parameter")
        }
        val file = java.io.File(path)
        if (!file.exists()) throw RPCException(-32000, "APK not found: $path")
        val canInstall = com.openminis.app.data.UpdateChecker.canInstall(context)
        return try {
            com.openminis.app.data.UpdateChecker.installApk(context, file)
            JSONObject().put("status", "launched").put("can_install", canInstall)
        } catch (e: Exception) {
            JSONObject()
                .put("status", "error")
                .put("can_install", canInstall)
                .put("message", e.message ?: e.javaClass.simpleName)
        }
    }


    private suspend fun handleViewTree(params: JSONObject): Any {
        val maxDepth = params.optInt("maxDepth", 50).coerceIn(1, 200)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val trees = DebugInspectorMethods.viewTree(activity, maxDepth)
        return if (trees.length() == 1) trees.get(0) else trees
    }

    private suspend fun handleSearch(params: JSONObject): JSONArray {
        val keyword = params.optString("keyword").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'keyword' is required")
        }
        val scope = params.optString("scope", "all").ifEmpty { "all" }
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        return DebugInspectorMethods.search(activity, keyword, scope)
    }

    private suspend fun handleInspect(params: JSONObject): JSONObject {
        val address = params.optString("address").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'address' is required")
        }
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        return DebugInspectorMethods.inspect(activity, address)
            ?: JSONObject().put("error", "Node not found at address $address (call viewTree/search first)")
    }

    private suspend fun handleHighlight(params: JSONObject): JSONObject {
        val address = params.optString("address").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'address' is required")
        }
        val color = params.optString("color", "red").ifEmpty { "red" }
        val duration = params.optDouble("duration", 2.0)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val ok = DebugInspectorMethods.highlight(activity, address, color, duration)
        return JSONObject().put("ok", ok)
    }

    private fun handleCloudSync(): JSONObject {
        return JSONObject().apply {
            put("enabled", false)
            put("status", "unsupported")
            put("reason", "Android build does not include iCloud sync")
            put("devices", JSONArray())
            put("deviceCount", 0)
        }
    }

    private fun handleWriteFile(params: JSONObject): JSONObject {
        val path = params.optString("path").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'path' is required")
        }
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")
        if (!params.has("content")) throw RPCException(-32602, "Invalid params: 'content' is required")
        val content = params.optString("content")
        val encoding = params.optString("encoding", "utf8").lowercase().ifEmpty { "utf8" }
        val overwrite = params.optBoolean("overwrite", true)
        val modeStr = params.optString("mode", "0644")

        val hostFile = PRootKernel.resolveHostPath(path) ?: run {
            val rootfsDir = com.openminis.app.sandbox.RootfsManager.getInstance(context).rootfsDir
            File(rootfsDir, path.removePrefix("/"))
        }

        if (hostFile.exists() && !overwrite) {
            throw RPCException(-32000, "File exists and overwrite=false: $path")
        }

        val bytes = when (encoding) {
            "utf8" -> content.toByteArray(Charsets.UTF_8)
            "base64" -> try {
                android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw RPCException(-32602, "Invalid base64 content: ${e.message}")
            }
            else -> throw RPCException(-32602, "Invalid encoding: '$encoding' (must be utf8 or base64)")
        }

        hostFile.parentFile?.mkdirs()
        hostFile.writeBytes(bytes)

        runCatching {
            val modeOct = if (modeStr.startsWith("0")) modeStr.toInt(8) else modeStr.toInt(10)
            val ownerExec = (modeOct and 0b001_000_000) != 0
            val ownerWrite = (modeOct and 0b010_000_000) != 0
            val ownerRead = (modeOct and 0b100_000_000) != 0
            hostFile.setReadable(ownerRead, true)
            hostFile.setWritable(ownerWrite, true)
            hostFile.setExecutable(ownerExec, true)
        }

        return JSONObject().apply {
            put("ok", true)
            put("path", path)
            put("hostPath", hostFile.absolutePath)
            put("size", bytes.size)
        }
    }

    private suspend fun handleScreenshotCapture(params: JSONObject): JSONObject {
        val label = params.optString("label", "")
        val scale = params.optDouble("scale", 0.5).toFloat().coerceIn(0.05f, 1.0f)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val entry = DebugScreenshotRing.capture(activity, label, scale)
        return DebugScreenshotRing.summary(entry)
    }

    private fun handleScreenshotGet(params: JSONObject): JSONObject {
        val id = if (params.has("id") && !params.isNull("id")) params.optInt("id") else null
        val entry = DebugScreenshotRing.get(id)
            ?: throw RPCException(-32602, "Screenshot not found${if (id != null) " for id=$id" else " (buffer empty)"}")
        return JSONObject().apply {
            put("id", entry.id)
            put("label", entry.label)
            put("timestamp", entry.timestamp)
            put("base64", android.util.Base64.encodeToString(entry.pngBytes, android.util.Base64.NO_WRAP))
            put("size", entry.pngBytes.size)
            put("encoding", "png")
        }
    }

    private fun handlePermissionsList(): JSONObject {
        val arr = org.json.JSONArray()
        for (tool in com.openminis.app.offload.OffloadPermissionManager.toolRegistry) {
            arr.put(JSONObject()
                .put("toolName", tool.toolName)
                .put("displayName", tool.displayName)
                .put("category", tool.category.name)
                .put("defaultLevel", tool.defaultLevel.name)
                .put("currentLevel", com.openminis.app.offload.OffloadPermissionManager.getLevel(tool.toolName).name)
                .put("showInSettings", tool.showInSettings))
        }
        return JSONObject().put("tools", arr).put("count", arr.length())
    }

    private fun handleShizukuExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }
        AppLogger.info("DebugRPC", "debug.shizuku.exec argv=${argvTail.joinToString(" ")}")
        val handler = com.openminis.app.sandbox.offload.ShizukuOffloadHandler(context)
        val request = com.openminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("android-shizuku-cli") + argvTail,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(argvTail))
        }
    }

    private fun handleModelUseExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }

        val finalArgv: List<String> = if (params.has("input")) {
            val inputBlob = params.optString("input", "")
            val linuxPath = "/tmp/.debug-modeluse-input-${System.currentTimeMillis()}.json"
            val hostFile = com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
                ?: throw RPCException(-32603, "cannot resolve $linuxPath under rootfs")
            hostFile.parentFile?.mkdirs()
            hostFile.writeText(inputBlob)
            argvTail + listOf("--input", linuxPath)
        } else argvTail

        AppLogger.info("DebugRPC", "debug.modelUse.exec argv=${finalArgv.joinToString(" ")}")
        val app = context.applicationContext as com.openminis.app.MinisApp
        val handler = com.openminis.app.sandbox.offload.ModelUseOffloadHandler(context, app.providerRepository)
        val request = com.openminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("minis-model-use") + finalArgv,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(finalArgv))
        }
    }

    private fun handleCalendarExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }
        AppLogger.info("DebugRPC", "debug.calendar.exec argv=${argvTail.joinToString(" ")}")
        if (argvTail.firstOrNull() == "debug-local") return calendarDebugLocal(argvTail.drop(1))
        val handler = com.openminis.app.sandbox.offload.CalendarOffloadHandler(context.applicationContext)
        val request = com.openminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("android-calendar") + argvTail,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(argvTail))
        }
    }

    private fun calendarDebugLocal(argv: List<String>): JSONObject {
        val cr = context.contentResolver
        val account = "minis-debug"
        val syncUri = android.provider.CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val out = JSONObject().put("argv", JSONArray(argv))
        when (argv.firstOrNull()) {
            "create" -> {
                val v = android.content.ContentValues().apply {
                    put(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, account)
                    put(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(android.provider.CalendarContract.Calendars.NAME, "minis_debug_local")
                    put(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Minis Debug (local)")
                    put(android.provider.CalendarContract.Calendars.CALENDAR_COLOR, 0xFF888888.toInt())
                    put(android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, android.provider.CalendarContract.Calendars.CAL_ACCESS_OWNER)
                    put(android.provider.CalendarContract.Calendars.OWNER_ACCOUNT, account)
                    put(android.provider.CalendarContract.Calendars.VISIBLE, 1)
                    put(android.provider.CalendarContract.Calendars.SYNC_EVENTS, 1)
                    put(android.provider.CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
                }
                val uri = cr.insert(syncUri, v)
                out.put("exitCode", if (uri != null) 0 else 1).put("calendar_id", uri?.lastPathSegment ?: JSONObject.NULL)
            }
            "delete" -> {
                val n = cr.delete(
                    syncUri,
                    "${android.provider.CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${android.provider.CalendarContract.Calendars.ACCOUNT_TYPE}=?",
                    arrayOf(account, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL),
                )
                out.put("exitCode", 0).put("deleted_calendars", n)
            }
            "raw" -> {
                val id = argv.getOrNull(1)?.toLongOrNull() ?: throw RPCException(-32602, "raw <event_id>")
                cr.query(
                    android.provider.CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(id.toString()).build(),
                    arrayOf(
                        android.provider.CalendarContract.Events.DTSTART,
                        android.provider.CalendarContract.Events.DTEND,
                        android.provider.CalendarContract.Events.ALL_DAY,
                        android.provider.CalendarContract.Events.EVENT_TIMEZONE,
                        android.provider.CalendarContract.Events.RRULE,
                        android.provider.CalendarContract.Events.TITLE,
                        android.provider.CalendarContract.Events.CALENDAR_ID,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val utc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        out.put("exitCode", 0)
                            .put("dtstart", c.getLong(0)).put("dtstart_utc", utc.format(java.util.Date(c.getLong(0))))
                            .put("dtend", if (c.isNull(1)) JSONObject.NULL else c.getLong(1))
                            .put("dtend_utc", if (c.isNull(1)) JSONObject.NULL else utc.format(java.util.Date(c.getLong(1))))
                            .put("all_day", c.getInt(2)).put("event_timezone", c.getString(3) ?: JSONObject.NULL)
                            .put("rrule", c.getString(4) ?: JSONObject.NULL).put("title", c.getString(5) ?: "")
                            .put("calendar_id", c.getLong(6))
                    } else out.put("exitCode", 1).put("error", "not found")
                }
            }
            "legacy-allday" -> {
                val calId = argv.getOrNull(1)?.toLongOrNull() ?: throw RPCException(-32602, "legacy-allday <calendar_id> <YYYY-MM-DD>")
                val day = com.openminis.app.sandbox.offload.CalendarOffloadHandler.parseDate(argv.getOrNull(2) ?: "")
                    ?: throw RPCException(-32602, "legacy-allday <calendar_id> <YYYY-MM-DD>")
                val (s0, e0) = com.openminis.app.sandbox.offload.CalendarOffloadHandler.allDayBounds(day, day)
                val v = android.content.ContentValues().apply {
                    put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                    put(android.provider.CalendarContract.Events.TITLE, "Minis legacy all-day probe")
                    put(android.provider.CalendarContract.Events.DTSTART, s0)
                    put(android.provider.CalendarContract.Events.DTEND, e0)
                    put(android.provider.CalendarContract.Events.ALL_DAY, 1)
                    put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                }
                val uri = cr.insert(android.provider.CalendarContract.Events.CONTENT_URI, v)
                out.put("exitCode", if (uri != null) 0 else 1).put("event_id", uri?.lastPathSegment ?: JSONObject.NULL)
            }
            else -> throw RPCException(-32602, "debug-local create | delete | raw <id> | legacy-allday <cal> <date>")
        }
        return out
    }

    private fun handleSessionsExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }

        AppLogger.info("DebugRPC", "debug.sessions.exec argv=${argvTail.joinToString(" ")}")
        val app = context.applicationContext as com.openminis.app.MinisApp
        val handler = com.openminis.app.sandbox.offload.SessionsOffloadHandler(app.chatRepository)
        val request = com.openminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("minis-sessions-cli") + argvTail,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(argvTail))
        }
    }

    private fun handleMinisConfigExec(params: JSONObject): JSONObject {
        val sub = params.optString("subcommand", "").takeIf { it.isNotEmpty() }
            ?: throw RPCException(
                -32602,
                "Missing 'subcommand' — one of: set, get, topics, topic-help, audit-list",
            )

        return when (sub) {
            "set" -> {
                val items = params.optJSONArray("items")?.also { arr ->
                    if (arr.length() == 0) {
                        throw RPCException(-32602, "'items' must not be empty for subcommand=set")
                    }
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i)
                            ?: throw RPCException(-32602, "items[$i] must be an object")
                        if (it.optString("path", "").isEmpty()) {
                            throw RPCException(-32602, "Missing 'path' in items[$i]")
                        }
                        if (it.optString("value_json", "").isEmpty()) {
                            throw RPCException(-32602, "Missing 'value_json' in items[$i]")
                        }
                    }
                } ?: run {
                    val path = params.optString("path", "").takeIf { it.isNotEmpty() }
                        ?: throw RPCException(
                            -32602,
                            "Missing 'path' (or 'items') for subcommand=set",
                        )
                    val valueJson = params.optString("value_json", "").takeIf { it.isNotEmpty() }
                        ?: throw RPCException(-32602, "Missing 'value_json' for subcommand=set")
                    JSONArray().put(
                        JSONObject().apply {
                            put("path", path)
                            put("value_json", valueJson)
                        },
                    )
                }
                val skip = params.optBoolean("skipConfirmation", true)
                AppLogger.info(
                    "DebugRPC",
                    "debug.minisConfig.exec set items=${items.length()} skipConfirmation=$skip",
                )
                kotlinx.coroutines.runBlocking {
                    com.openminis.app.config.ConfigBridge.performWriteBatch(
                        items = items,
                        caption = "debug.minisConfig.exec",
                        actorRaw = "debug-rpc",
                        sessionId = null,
                        skipConfirmation = skip,
                    )
                }
            }
            "get" -> {
                val path = params.optString("path", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(-32602, "Missing 'path' for subcommand=get")
                val filter = params.optString("filter", "").takeIf { it.isNotEmpty() }
                val page = params.optInt("page", 0)
                val pageSize = params.optInt("pageSize", 0)
                AppLogger.info("DebugRPC", "debug.minisConfig.exec get path=$path")
                com.openminis.app.config.ConfigBridge.readField(
                    path = path,
                    filter = filter,
                    page = page,
                    pageSize = pageSize,
                )
            }
            "topics" -> JSONObject().apply {
                put("ok", true)
                put("topics", com.openminis.app.config.ConfigBridge.allTopics())
            }
            "topic-help" -> {
                val topic = params.optString("topic", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(-32602, "Missing 'topic' for subcommand=topic-help")
                val known = com.openminis.app.config.ConfigBridge.allTopics()
                    .let { arr -> (0 until arr.length()).any { arr.optString(it) == topic } }
                if (!known) {
                    throw RPCException(-32602, "Unknown topic '$topic' — call subcommand=topics")
                }
                val fields = com.openminis.app.config.ConfigBridge.fieldsForTopic(topic)
                JSONObject().apply {
                    put("ok", true)
                    put("topic", topic)
                    put("empty", fields.length() == 0)
                    put("fields", fields)
                }
            }
            "audit-list" -> {
                val limit = params.optInt("limit", 100).coerceIn(1, 1000)
                val scope = params.optString("scope", "").takeIf { it.isNotEmpty() }
                com.openminis.app.config.ConfigBridge.auditList(limit, scope)
            }
            else -> throw RPCException(
                -32602,
                "Unknown subcommand '$sub' — one of: set, get, topics, topic-help, audit-list",
            )
        }
    }
}

internal class RPCException(val code: Int, override val message: String) : Exception(message)
