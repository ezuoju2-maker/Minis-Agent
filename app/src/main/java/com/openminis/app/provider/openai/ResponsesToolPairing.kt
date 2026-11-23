package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject

internal object ResponsesToolPairing {

    const val PLACEHOLDER_OUTPUT =
        "Tool execution result is unavailable (history was truncated or interrupted)."

    class Result(
        val items: JSONArray,
        val droppedOrphanOutputs: List<String>,
        val droppedDuplicateOutputs: List<String>,
        val placeholderCalls: List<String>,
    ) {
        val changed: Boolean
            get() = droppedOrphanOutputs.isNotEmpty() || droppedDuplicateOutputs.isNotEmpty() ||
                placeholderCalls.isNotEmpty()
    }

    fun sanitize(items: JSONArray): Result {
        val callIds = HashSet<String>()
        val outputCounts = HashMap<String, Int>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "function_call" -> callIds.add(item.optString("call_id"))
                "function_call_output" -> {
                    val id = item.optString("call_id")
                    outputCounts[id] = (outputCounts[id] ?: 0) + 1
                }
            }
        }

        val trailing = HashSet<String>()
        for (i in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(i) ?: break
            when (item.optString("type")) {
                "function_call" -> trailing.add(item.optString("call_id"))
                "reasoning" -> {}
                else -> break
            }
        }

        val orphanOutputs = outputCounts.keys - callIds
        val duplicated = outputCounts.filter { (id, n) -> n > 1 && id in callIds }.keys
        val unanswered = callIds - outputCounts.keys - trailing
        if (orphanOutputs.isEmpty() && duplicated.isEmpty() && unanswered.isEmpty()) {
            return Result(items, emptyList(), emptyList(), emptyList())
        }

        val out = JSONArray()
        val seenOutputs = HashSet<String>()
        val droppedOrphans = mutableListOf<String>()
        val droppedDuplicates = mutableListOf<String>()
        val placeholders = mutableListOf<String>()
        val pending = mutableListOf<JSONObject>()
        fun flush() {
            for (p in pending) out.put(p)
            pending.clear()
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)
            if (item == null) {
                out.put(items.get(i))
                continue
            }
            val type = item.optString("type")
            if (type == "function_call_output") {
                val id = item.optString("call_id")
                if (id in orphanOutputs) { droppedOrphans += id; continue }
                if (!seenOutputs.add(id)) { droppedDuplicates += id; continue }
            }
            if (type != "function_call") flush()
            out.put(item)
            if (type == "function_call") {
                val id = item.optString("call_id")
                if (id in unanswered) {
                    placeholders += id
                    pending += JSONObject().apply {
                        put("type", "function_call_output")
                        put("call_id", id)
                        put("output", PLACEHOLDER_OUTPUT)
                    }
                }
            }
        }
        flush()
        return Result(out, droppedOrphans, droppedDuplicates, placeholders)
    }
}
