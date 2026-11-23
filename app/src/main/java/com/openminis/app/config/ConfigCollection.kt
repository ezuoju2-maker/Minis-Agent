package com.openminis.app.config

import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.RedactedConfigValue

interface ConfigCollection {
    val basePath: String
    val displayName: String
    val description: String

    val addable: Boolean get() = true
    val removable: Boolean get() = true

    val risk: ConfigRisk get() = ConfigRisk.SENSITIVE

    fun childIds(): List<String>

    fun fields(forId: String): List<ConfigField>

    val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    fun add(payload: ConfigValue): String

    fun remove(id: String)


    val readableSubjectFormat: String

    fun entityLabel(id: String): String?

    fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine>

    fun readableForRemove(id: String): List<ReadableLine>
}
