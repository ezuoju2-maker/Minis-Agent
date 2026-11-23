package com.openminis.app.speech

import org.json.JSONObject

object ToolSpeech {

    private const val DETAIL_MAX = 40

    private val HAN = Regex("\\p{IsHan}")

    fun announcement(name: String, argsJson: String?, title: String?): String {
        val args = parseArgs(argsJson)
        val zh = title?.let { HAN.containsMatchIn(it) } ?: false
        val detail = clip(title)

        return when (name) {
            "shell_execute" -> {
                val d = detail ?: clip(args?.optString("command")) ?: ""
                if (zh) "正在执行脚本:$d" else "Executing script: $d"
            }
            "file_read" -> {
                val d = detail ?: fileName(args?.optString("path"), zh)
                if (zh) "正在读取文件:$d" else "Reading file: $d"
            }
            "file_write" -> {
                val d = detail ?: fileName(args?.optString("path"), zh)
                if (zh) "正在写入文件:$d" else "Writing file: $d"
            }
            "file_edit" -> {
                val d = detail ?: fileName(args?.optString("path"), zh)
                if (zh) "正在编辑文件:$d" else "Editing file: $d"
            }
            "browser_use" -> {
                val d = detail ?: clip(args?.optString("action")) ?: ""
                if (zh) "正在操作浏览器:$d" else "Using browser: $d"
            }
            "read_image" -> {
                val d = detail ?: fileName(args?.optString("path"), zh)
                if (zh) "正在读取图片:$d" else "Reading image: $d"
            }
            else -> {
                val d = detail ?: name
                if (zh) "正在执行:$d" else "Running: $d"
            }
        }
    }

    private fun parseArgs(argsJson: String?): JSONObject? {
        if (argsJson.isNullOrBlank()) return null
        return runCatching { JSONObject(argsJson) }.getOrNull()
    }

    private fun clip(s: String?): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return if (t.length > DETAIL_MAX) t.take(DETAIL_MAX) + "…" else t
    }

    private fun fileName(path: String?, zh: Boolean): String {
        val p = path?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?: return if (zh) "文件" else "a file"
        return p.trimEnd('/').substringAfterLast('/').ifEmpty { p }
    }
}
