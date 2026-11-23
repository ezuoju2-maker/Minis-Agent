package com.openminis.app.config

import com.openminis.app.config.readable.ConfigReadable

interface ConfigField {
    val path: String

    val displayName: String

    val description: String

    val valueSchema: ConfigSchema
    val access: ConfigAccess
    val risk: ConfigRisk

    val revertable: Boolean

    val readable: ConfigReadable

    val unavailableReason: String?
        get() = null

    fun normalize(value: ConfigValue): ConfigValue = value

    fun validate(value: ConfigValue) {}

    fun read(): ConfigValue

    fun write(value: ConfigValue)

    val scope: String
        get() = path.substringBefore('.', missingDelimiterValue = "unknown")
}

class UnavailableField(
    private val delegate: ConfigField,
    private val reason: String,
) : ConfigField by delegate {
    override val unavailableReason: String = reason
}
