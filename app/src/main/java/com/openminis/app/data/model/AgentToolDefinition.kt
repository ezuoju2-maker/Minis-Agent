package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    fun coerceNestedJsonStrings(args: JSONObject): JSONObject {
        var out: JSONObject? = null
        for ((key, p) in parameters) {
            val node = p.schema ?: continue
            if (!args.has(key)) continue
            val v = args.opt(key)
            val c = node.coerce(v)
            if (c !== v) {
                if (out == null) out = JSONObject(args.toString())
                out.put(key, c)
            }
        }
        return out ?: args
    }

    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
    }

    fun toGeminiJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toGeminiJson())
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    fun toOpenAIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }
    }
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
    val schema: AgentSchemaNode? = null,
) {
    fun toJson(): JSONObject {
        schema?.let { return it.toJsonSchema(gemini = false).apply { put("description", description) } }
        return JSONObject().apply {
            put("type", type)
            put("description", description)
            if (enumValues != null) put("enum", JSONArray(enumValues))
        }
    }

    fun toGeminiJson(): JSONObject {
        schema?.let { return it.toJsonSchema(gemini = true).apply { put("description", description) } }
        return JSONObject().apply {
            put("type", type.uppercase())
            put("description", description)
            if (enumValues != null) put("enum", JSONArray(enumValues))
        }
    }
}

sealed class AgentSchemaNode {
    abstract val description: String?

    data class Str(override val description: String? = null, val enumValues: List<String>? = null) : AgentSchemaNode()
    data class Integer(override val description: String? = null) : AgentSchemaNode()
    data class Bool(override val description: String? = null) : AgentSchemaNode()
    data class Arr(
        override val description: String? = null,
        val items: AgentSchemaNode,
        val minItems: Int? = null,
        val maxItems: Int? = null,
    ) : AgentSchemaNode()
    data class Obj(
        override val description: String? = null,
        val properties: List<Pair<String, AgentSchemaNode>>,
        val required: List<String> = emptyList(),
    ) : AgentSchemaNode()

    fun toJsonSchema(gemini: Boolean): JSONObject {
        fun t(s: String) = if (gemini) s.uppercase() else s
        val o = JSONObject()
        when (this) {
            is Str -> {
                o.put("type", t("string"))
                if (gemini && !enumValues.isNullOrEmpty()) {
                    o.put("description", "${description ?: ""} (values: ${enumValues.joinToString(", ")})")
                } else {
                    description?.let { o.put("description", it) }
                    enumValues?.let { o.put("enum", JSONArray(it)) }
                }
            }
            is Integer -> {
                o.put("type", t("integer"))
                description?.let { o.put("description", it) }
            }
            is Bool -> {
                o.put("type", t("boolean"))
                description?.let { o.put("description", it) }
            }
            is Arr -> {
                o.put("type", t("array"))
                description?.let { o.put("description", it) }
                o.put("items", items.toJsonSchema(gemini))
                if (!gemini) {
                    minItems?.let { o.put("minItems", it) }
                    maxItems?.let { o.put("maxItems", it) }
                }
            }
            is Obj -> {
                o.put("type", t("object"))
                description?.let { o.put("description", it) }
                val props = JSONObject()
                for ((name, node) in properties) props.put(name, node.toJsonSchema(gemini))
                o.put("properties", props)
                if (required.isNotEmpty()) o.put("required", JSONArray(required))
            }
        }
        return o
    }

    fun coerce(value: Any?): Any? = when (this) {
        is Arr -> {
            val arr = when (value) {
                is JSONArray -> value
                is String -> parseJsonOrNull(value) as? JSONArray
                else -> null
            }
            if (arr == null) value else {
                var changed = arr !== value
                val mapped = (0 until arr.length()).map { i ->
                    val item = arr.opt(i)
                    items.coerce(item).also { if (it !== item) changed = true }
                }
                if (changed) JSONArray(mapped) else value
            }
        }
        is Obj -> {
            val obj = when (value) {
                is JSONObject -> value
                is String -> parseJsonOrNull(value) as? JSONObject
                else -> null
            }
            if (obj == null) value else {
                var out: JSONObject? = if (obj !== value) obj else null
                for ((name, node) in properties) {
                    if (!obj.has(name)) continue
                    val v = obj.opt(name)
                    val c = node.coerce(v)
                    if (c !== v) {
                        if (out == null) out = JSONObject(obj.toString())
                        out.put(name, c)
                    }
                }
                out ?: value
            }
        }
        else -> value
    }

    private fun parseJsonOrNull(s: String): Any? {
        val t = s.trim()
        return when {
            t.startsWith("[") -> runCatching { JSONArray(t) }.getOrNull()
            t.startsWith("{") -> runCatching { JSONObject(t) }.getOrNull()
            else -> null
        }
    }

    val estimationText: String
        get() = when (this) {
            is Str -> listOfNotNull(description, enumValues?.joinToString(" ")).joinToString(" ")
            is Integer -> description ?: ""
            is Bool -> description ?: ""
            is Arr -> (description ?: "") + " " + items.estimationText
            is Obj -> (description ?: "") + " " +
                properties.joinToString(" ") { (name, node) -> name + " " + node.estimationText }
        }
}
