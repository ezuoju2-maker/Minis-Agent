package com.openminis.app.backup

import com.openminis.app.IosSources
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCompactMarkerVersionParityTest {

    private data class SwiftProp(val name: String, val optional: Boolean)

    private fun storedProps(header: String): List<SwiftProp> {
        val chatStore = IosSources.declaring(header)
        val start = chatStore.indexOf(header)
        require(start >= 0) { "declaration not found: $header" }
        var depth = 0
        var i = chatStore.indexOf('{', start)
        val bodyStart = i
        while (i < chatStore.length) {
            when (chatStore[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        val body = chatStore.substring(bodyStart + 1, i)
        val prop = Regex("""^    (?:let|var) (\w+): ([^=/{\n]+?)\s*(?:=[^{]*)?(?://.*)?$""")
        return body.lines().mapNotNull { line ->
            if (line.contains("{")) return@mapNotNull null
            val m = prop.find(line) ?: return@mapNotNull null
            SwiftProp(m.groupValues[1], m.groupValues[2].trim().endsWith("?"))
        }
    }

    private fun requiredKeys(header: String): Set<String> =
        storedProps(header).filterNot { it.optional }.map { it.name }.toSet()

    private fun hasCustomDecoder(typeName: String): Boolean =
        IosSources.anyMatches(Regex("""extension $typeName\b""")) ||
            IosSources.declaring("struct $typeName:").substringAfter("struct $typeName:").substringBefore("\n}\n").contains("init(from decoder")

    private val v2Marker = CompactMarkerEntity(
        id = "C-2", sessionId = "S-1", summary = "sum", firstKeptSortOrder = Int.MAX_VALUE,
        compactedCount = 12, createdAt = 1_758_000_000_000, uiBoundarySortOrder = null,
        boundaryMessageId = null, firstKeptMessageId = null, lastCompactedMessageId = "M-12",
        version = 2,
    )

    private val message = MessageEntity(
        id = "M-1", sessionId = "S-1", role = "assistant",
        partsJson = """[{"type":"text","text":"hi"}]""",
        createdAt = 1_758_000_000_000, sortOrder = 3,
    )


    @Test
    fun `the Swift declarations parse, including defaulted vars`() {
        val marker = storedProps("struct CompactMarker: ").map { it.name }
        assertTrue("CompactMarker.version must be seen: $marker", "version" in marker)
        val raw = storedProps("struct RawMessage: ").map { it.name }
        assertTrue("RawMessage.sortOrder must be seen: $raw", "sortOrder" in raw)
        assertTrue("RawMessage.streamInterruptCount must be seen: $raw", "streamInterruptCount" in raw)
        assertTrue("CompactMarker grew a custom decoder; revisit this test", !hasCustomDecoder("CompactMarker"))
    }


    @Test
    fun `BUG - a compact marker carries every key iOS's synthesized Codable requires`() {
        val rec = BackupExporter.markerRecord(v2Marker).jsonObject
        val missing = requiredKeys("struct CompactMarker: ").filter { k -> rec[k] == null || rec[k] is JsonNull }
        assertTrue(
            "Android markerRecord omits required iOS CompactMarker keys $missing — every marker " +
                "line fails decode on iPhone and is dropped by readJSONL's try?",
            missing.isEmpty(),
        )
    }

    @Test
    fun `a message carries every key iOS's synthesized Codable requires`() {
        val rec = BackupExporter.messageRecord(message).jsonObject
        val missing = requiredKeys("struct RawMessage: ").filter { k -> rec[k] == null || rec[k] is JsonNull }
        assertTrue("messageRecord omits required iOS RawMessage keys $missing", missing.isEmpty())
    }

    @Test
    fun `message keys are camelCase Swift property names, never snake_case`() {
        val iosNames = storedProps("struct RawMessage: ").map { it.name }.toSet()
        val rec = BackupExporter.messageRecord(
            message.copy(modelId = "m", modelDisplayName = "M", providerType = "openAI", providerInstanceId = "P"),
        ).jsonObject
        val foreign = rec.keys.filter { it !in iosNames }
        assertTrue("keys iOS does not know: $foreign", foreign.isEmpty())
    }


    @Test
    fun `BUG - a v2 marker keeps version 2 through Android export then import`() {
        val back = BackupRecordMapper.compactMarker(BackupExporter.markerRecord(v2Marker).jsonObject)
        assertEquals(
            "v2 marker restored as v${back?.version} — it would resolve through the legacy " +
                "firstKeptSortOrder (=Int.MAX_VALUE) chain instead of lastCompactedMessageId",
            2, back?.version,
        )
    }

    @Test
    fun `BUG - an iOS v2 marker line restores as version 2 on Android`() {
        val ios = Json.parseToJsonElement(
            """{"id":"C-9","sessionId":"S-1","summary":"s","firstKeptSortOrder":41,""" +
                """"compactedCount":40,"createdAt":"2026-09-20T10:00:00Z","uiBoundarySortOrder":41,""" +
                """"lastCompactedMessageId":"M-40","version":2}""",
        ).jsonObject
        assertEquals(2, ios["version"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, BackupRecordMapper.compactMarker(ios)?.version)
    }

    @Test
    fun `a marker line with no version still restores as v1 (older packages)`() {
        val old = JsonObject(
            BackupExporter.markerRecord(v2Marker.copy(version = 1)).jsonObject.filterKeys { it != "version" },
        )
        assertEquals(1, BackupRecordMapper.compactMarker(old)?.version)
    }
}
