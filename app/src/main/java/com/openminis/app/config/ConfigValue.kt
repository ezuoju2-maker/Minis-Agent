package com.openminis.app.config

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

sealed class ConfigValue {
    data class Bool(val value: Boolean) : ConfigValue()
    data class Int(val value: kotlin.Int) : ConfigValue()
    data class Double(val value: kotlin.Double) : ConfigValue()
    data class Str(val value: String) : ConfigValue()
    data class Arr(val value: List<ConfigValue>) : ConfigValue()
    data class Obj(val value: Map<String, ConfigValue>) : ConfigValue()
    object Null : ConfigValue()

    val displayString: String
        get() = when (this) {
            is Bool -> if (value) "true" else "false"
            is Int -> value.toString()
            is Double -> value.toString()
            is Str -> {
                if (value.length > 80) {
                    "\"${value.take(60)}…${value.takeLast(15)}\""
                } else {
                    "\"$value\""
                }
            }
            is Arr, is Obj -> jsonString()
            Null -> "null"
        }

    fun redactingSecrets(): ConfigValue = when (this) {
        is Obj -> {
            val out = LinkedHashMap<String, ConfigValue>(value.size)
            for ((k, v) in value) {
                if (k in SECRET_KEYS && v is Str &&
                    !v.value.startsWith("\$\$") && v.value.isNotEmpty()) {
                    out[k] = Str("••• (hidden)")
                } else {
                    out[k] = v.redactingSecrets()
                }
            }
            Obj(out)
        }
        is Arr -> Arr(value.map { it.redactingSecrets() })
        else -> this
    }

    fun jsonString(): String = toJsonAny().let { any ->
        when (any) {
            JSONObject.NULL -> "null"
            is JSONObject -> any.toString()
            is JSONArray -> any.toString()
            is String -> JSONObject.quote(any)
            else -> any.toString()
        }
    }

    private fun toJsonAny(): Any = when (this) {
        is Bool -> value
        is Int -> value
        is Double -> value
        is Str -> value
        is Arr -> JSONArray().also { arr ->
            for (v in value) {
                val a = v.toJsonAny()
                if (a === JSONObject.NULL) arr.put(JSONObject.NULL) else arr.put(a)
            }
        }
        is Obj -> JSONObject().also { obj ->
            for ((k, v) in value) {
                val a = v.toJsonAny()
                obj.put(k, if (a === JSONObject.NULL) JSONObject.NULL else a)
            }
        }
        Null -> JSONObject.NULL
    }

    companion object {
        val SECRET_KEYS: Set<String> = setOf(
            "apiKey", "oauthToken", "manualOAuthToken",
            "password", "authKey",
        )

        fun decode(json: String): ConfigValue? = try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) null else fromJsonAny(value)
        } catch (_: Throwable) {
            null
        }

        private fun fromJsonAny(any: Any?): ConfigValue = when (any) {
            null, JSONObject.NULL -> Null
            is Boolean -> Bool(any)
            is kotlin.Int -> Int(any)
            is Long -> {
                if (any in kotlin.Int.MIN_VALUE.toLong()..kotlin.Int.MAX_VALUE.toLong()) Int(any.toInt())
                else Double(any.toDouble())
            }
            is kotlin.Double, is Float -> Double((any as Number).toDouble())
            is String -> Str(any)
            is JSONArray -> {
                val out = ArrayList<ConfigValue>(any.length())
                for (i in 0 until any.length()) out.add(fromJsonAny(any.opt(i)))
                Arr(out)
            }
            is JSONObject -> {
                val out = LinkedHashMap<String, ConfigValue>(any.length())
                val keys = any.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    out[k] = fromJsonAny(any.opt(k))
                }
                Obj(out)
            }
            else -> Null
        }
    }
}
