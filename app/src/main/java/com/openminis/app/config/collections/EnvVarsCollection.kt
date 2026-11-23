package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.RedactedConfigValue
import com.openminis.app.config.fields.HiddenField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.data.repository.EnvVarRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EnvVarsCollection(
    private val repo: EnvVarRepository,
) : ConfigCollection {
    override val basePath: String get() = "envvars"
    override val displayName: String get() = "Environment variables"
    override val description: String get() =
        "Metadata only — values are stored in encrypted prefs and never exposed."
    override val addable: Boolean get() = false
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun childIds(): List<String> = repo.entries.value.map { it.key }

    override fun fields(forId: String): List<ConfigField> {
        val entry = repo.entries.value.firstOrNull { it.key == forId } ?: return emptyList()
        return listOf(
            createdAtField(forId),
            noteField(forId),
            enabledField(forId),
            HiddenField(
                path = "envvars.$forId.value",
                displayName = "Value",
                description = "Hidden — manage via Settings → Environment Variables.",
                reason = "Env var values are stored in encrypted prefs and never exposed to minis-config",
            ),
        )
    }

    override fun add(payload: ConfigValue): String {
        throw ConfigError.PermissionDenied(
            "Use the deep link `minis://settings/environments?create_key=…` so the user enters the value directly"
        )
    }

    override fun remove(id: String) {
        val entry = repo.entries.value.firstOrNull { it.key == id }
            ?: throw ConfigError.UnknownPath("envvars.$id")
        repo.delete(entry.id)
    }


    override val readableSubjectFormat: String get() = "Environment variable $%1\$@ – %2\$@"

    override fun entityLabel(id: String): String? = repo.entries.value.firstOrNull { it.key == id }?.key

    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> = emptyList()

    override fun readableForRemove(id: String): List<ReadableLine> {
        val e = repo.entries.value.firstOrNull { it.key == id } ?: return emptyList()
        return listOf(
            ReadableLine("Delete environment variable $%@ (its value is removed from Keychain)", listOf(ReadableArg.Text(e.key))),
        )
    }

    private fun createdAtField(key: String): ConfigField =
        ReadOnlyField(
            path = "envvars.$key.createdAt",
            displayName = "Created at",
            description = "ISO-8601 timestamp.",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null
                else ConfigValue.Str(isoFormatter.format(Date(e.createdAt)))
            },
        )

    private fun enabledField(key: String): ConfigField =
        ClosureField(
            path = "envvars.$key.enabled",
            displayName = "Enabled",
            description = "Whether the variable is injected into shells, terminals and MCP servers. " +
                "A disabled variable keeps its value and note.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Bool(on = "Enabled", off = "Disabled"), label = "Status"),
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null else ConfigValue.Bool(e.enabled)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                val e = repo.entries.value.firstOrNull { it.key == key }
                    ?: throw ConfigError.UnknownPath("envvars.$key")
                repo.setEnabled(e.id, b)
            },
        )

    private fun noteField(key: String): ConfigField =
        ClosureField(
            path = "envvars.$key.note",
            displayName = "Note",
            description = "Free-form description (not secret). Empty string clears it.",
            valueSchema = ConfigSchema.Str(maxLength = 500),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text(preview = 60)),
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null
                else ConfigValue.Str(e.note)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val e = repo.entries.value.firstOrNull { it.key == key }
                    ?: throw ConfigError.UnknownPath("envvars.$key")
                val currentValue = repo.getValue(e.key) ?: ""
                repo.update(e.id, e.key, currentValue, s)
            },
        )
}
