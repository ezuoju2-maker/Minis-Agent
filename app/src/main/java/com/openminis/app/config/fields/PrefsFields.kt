package com.openminis.app.config.fields

import android.content.SharedPreferences
import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.NumberUnit


class PrefsBoolField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: Boolean,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val valueSchema: ConfigSchema get() = ConfigSchema.Bool
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.Bool())

    override fun read(): ConfigValue = ConfigValue.Bool(
        if (prefs.contains(key)) prefs.getBoolean(key, defaultValue) else defaultValue
    )
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val b = (value as ConfigValue.Bool).value
        prefs.edit().putBoolean(key, b).apply()
    }
}

class PrefsIntField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: Int,
    private val minValue: Int? = null,
    private val maxValue: Int? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
    private val unit: NumberUnit = NumberUnit.PLAIN,
    private val zeroLabel: String? = null,
) : ConfigField {
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Int(min = minValue, max = maxValue)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.Number(unit, zeroLabel))

    override fun read(): ConfigValue = ConfigValue.Int(
        if (prefs.contains(key)) prefs.getInt(key, defaultValue) else defaultValue
    )
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val i = (value as ConfigValue.Int).value
        prefs.edit().putInt(key, i).apply()
    }
}

class PrefsLongField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: Long,
    private val minValue: Long? = null,
    private val maxValue: Long? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
    private val unit: NumberUnit = NumberUnit.PLAIN,
    private val zeroLabel: String? = null,
) : ConfigField {
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.Number(unit, zeroLabel))
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Int(
            min = minValue?.toInt(),
            max = maxValue?.toInt(),
        )
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val v = if (prefs.contains(key)) prefs.getLong(key, defaultValue) else defaultValue
        return ConfigValue.Int(v.toInt())
    }
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val i = (value as ConfigValue.Int).value
        prefs.edit().putLong(key, i.toLong()).apply()
    }
}

class PrefsDoubleField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: Double,
    private val minValue: Double? = null,
    private val maxValue: Double? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.Number())
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Double(min = minValue, max = maxValue)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val v = if (prefs.contains(key)) prefs.getFloat(key, defaultValue.toFloat()).toDouble() else defaultValue
        return ConfigValue.Double(v)
    }
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val d = when (value) {
            is ConfigValue.Double -> value.value
            is ConfigValue.Int -> value.value.toDouble()
            else -> throw ConfigError.TypeMismatch("double")
        }
        prefs.edit().putFloat(key, d.toFloat()).apply()
    }
}

class PrefsStringField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: String,
    private val maxLength: Int? = null,
    private val regex: String? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
    override val readable: ConfigReadable,
) : ConfigField {
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Str(maxLength = maxLength, regex = regex)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue =
        ConfigValue.Str(prefs.getString(key, null) ?: defaultValue)
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val s = (value as ConfigValue.Str).value
        prefs.edit().putString(key, s).apply()
    }
}

class PrefsEnumField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val cases: List<String>,
    private val valueLabels: Map<String, String>,
    private val defaultValue: String,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.EnumLabels(valueLabels))
    override val valueSchema: ConfigSchema get() = ConfigSchema.StrEnum(cases)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val raw = prefs.getString(key, null) ?: defaultValue
        return ConfigValue.Str(if (raw in cases) raw else defaultValue)
    }
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        prefs.edit().putString(key, (value as ConfigValue.Str).value).apply()
    }
}

class PrefsIntCodedEnumField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val cases: List<String>,
    private val valueLabels: Map<String, String>,
    private val defaultIndex: Int,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.EnumLabels(valueLabels))
    override val valueSchema: ConfigSchema get() = ConfigSchema.StrEnum(cases)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val idx = if (prefs.contains(key)) prefs.getInt(key, defaultIndex) else defaultIndex
        val safe = if (idx in 0..cases.lastIndex) idx else defaultIndex
        return ConfigValue.Str(cases[safe])
    }
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val s = (value as ConfigValue.Str).value
        val idx = cases.indexOf(s)
        if (idx < 0) throw ConfigError.InvalidValue("must be one of: ${cases.joinToString(", ")}")
        prefs.edit().putInt(key, idx).apply()
    }
}
