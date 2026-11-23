package com.openminis.app.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossPlatformSessionShapeTest {

    private fun JsonObject.unwrapNested(key: String): JsonObject {
        val inner = (this[key] as? JsonObject) ?: return this
        return JsonObject(this.filterKeys { it != key } + inner)
    }

    private fun payload(line: String): JsonObject =
        BackupFormat.json.parseToJsonElement(line).jsonObject["d"]!!.jsonObject

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it.toString() != "null" }
            ?.runCatching { jsonPrimitive.content }?.getOrNull()

    @Test
    fun `an iOS session is readable once unwrapped`() {
        val ios = """
            {"t":"SessionV2","v":1,"d":{"memoryEnabled":true,"session":{
            "category":"support","createdAt":"2026-07-22T08:15:58Z",
            "id":"0006B985-A43A-48C3-A489-4189F792ED0F","modelId":"claude-sonnet-5",
            "title":"理想超充站充电流程","updatedAt":"2026-07-22T08:43:38Z"}}}
        """.trimIndent().replace("\n", "")

        val raw = payload(ios)
        assertNull("precondition: the id is NOT at the top level", raw.str("id"))

        val s = raw.unwrapNested("session")
        assertEquals("0006B985-A43A-48C3-A489-4189F792ED0F", s.str("id"))
        assertEquals("理想超充站充电流程", s.str("title"))
        assertEquals("claude-sonnet-5", s.str("modelId"))
        assertEquals("true", s.str("memoryEnabled"))
    }

    @Test
    fun `an Android session is unchanged by unwrapping`() {
        val android = """
            {"t":"SessionV2","v":1,"d":{"id":"A1","title":"local","modelId":"m",
            "memoryEnabled":true,"createdAt":"2026-08-01T00:00:00Z",
            "updatedAt":"2026-08-01T00:00:00Z"}}
        """.trimIndent().replace("\n", "")

        val s = payload(android).unwrapNested("session")
        assertEquals("A1", s.str("id"))
        assertEquals("local", s.str("title"))
        assertEquals("true", s.str("memoryEnabled"))
    }

    @Test
    fun `iOS names a folder's description desc`() {
        val ios = """{"t":"FolderV2","v":1,"d":{"id":"F1","name":"Work","desc":"from iPhone"}}"""
        val f = payload(ios)
        assertNull(f.str("description"))
        assertEquals("from iPhone", f.str("description") ?: f.str("desc"))
    }

    @Test
    fun `messages are flat on both platforms`() {
        val ios = """
            {"t":"MessageV2","v":1,"d":{"createdAt":"2026-07-22T08:15:58Z",
            "id":"E1AF8DBD-F647-4A41-ADDF-D255EF27D396","role":"user",
            "sessionId":"0006B985-A43A-48C3-A489-4189F792ED0F","sortOrder":0}}
        """.trimIndent().replace("\n", "")
        val m = payload(ios)
        assertEquals("E1AF8DBD-F647-4A41-ADDF-D255EF27D396", m.str("id"))
        assertEquals("0006B985-A43A-48C3-A489-4189F792ED0F", m.str("sessionId"))
    }
}
