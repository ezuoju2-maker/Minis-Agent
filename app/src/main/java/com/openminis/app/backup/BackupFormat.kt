package com.openminis.app.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object Iso8601MillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601Millis", PrimitiveKind.STRING)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(fmt().format(value))
    }

    override fun deserialize(decoder: Decoder): Long {
        val jd = decoder as? JsonDecoder
            ?: return runCatching { fmt().parse(decoder.decodeString())?.time }.getOrNull() ?: 0L
        val el = jd.decodeJsonElement()
        val prim = el as? JsonPrimitive ?: return 0L
        prim.longOrNull?.let { return it }
        val s = prim.content
        return runCatching { fmt().parse(s)?.time }.getOrNull() ?: 0L
    }
}

object Iso8601MillisNullableSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601MillisNullable", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull()
        else Iso8601MillisSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val jd = decoder as? JsonDecoder ?: return Iso8601MillisSerializer.deserialize(decoder)
        val el = jd.decodeJsonElement()
        if (el is kotlinx.serialization.json.JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        prim.longOrNull?.let { return it }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(prim.content)?.time
        }.getOrNull()
    }
}

object BackupFormat {
    const val CURRENT = "minisbak/1"

    const val FILE_EXTENSION = "minisbak"

    const val MIME_TYPE = "application/x-minisbak"

    const val MAX_SHARD_BYTES = 64 * 1024 * 1024

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val secretsJson = Json(json) { encodeDefaults = true }

    fun encodeSecrets(secrets: BackupSecrets): String =
        secretsJson.encodeToString(BackupSecrets.serializer(), secrets)
}

enum class BackupCategory(val key: String) {
    CHATS("chats"),
    SHARED_FILES("shared_files"),
    SKILLS("skills"),
    MEMORY("memory"),
    PROVIDERS("providers"),
    MCP_SERVERS("mcp_servers"),
    VOICE_CORRECTIONS("voice_corrections"),

    ENVIRONMENT_VARIABLES("environment_variables");

    val carriesFileTree: Boolean
        get() = when (this) {
            CHATS, SHARED_FILES, SKILLS -> true
            MEMORY, PROVIDERS, MCP_SERVERS, VOICE_CORRECTIONS,
            ENVIRONMENT_VARIABLES -> false
        }

    companion object {
        fun fromKey(key: String): BackupCategory? = entries.firstOrNull { it.key == key }

        val backupable: List<BackupCategory>
            get() = entries.filter { it != VOICE_CORRECTIONS }
    }
}

@Serializable
data class BackupManifest(
    val format: String = BackupFormat.CURRENT,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("snapshot_at") val snapshotAt: String? = null,
    val app: AppInfo = AppInfo(),
    @SerialName("device_name") val deviceName: String = "Unknown device",
    @SerialName("backup_id") val backupId: String = "",
    val categories: Map<String, CategoryStat> = emptyMap(),
    val limits: Limits = Limits(),
    val encryption: Encryption? = null,
    val integrity: Map<String, String> = emptyMap(),
    @SerialName("manifest_mac") val manifestMac: String? = null,
) {
    @Serializable
    data class AppInfo(
        val platform: String = "unknown",
        val version: String = "?",
        val build: String = "?",
    )

    @Serializable
    data class CategoryStat(
        val entries: Int = 0,
        val bytes: Long = 0,
        val encrypted: Boolean = false,
        val messages: Int? = null,
        val files: Int? = null,
        @SerialName("includes_credentials") val includesCredentials: Boolean? = null,
        @SerialName("thinking_rules") val thinkingRules: Int? = null,
    )

    @Serializable
    data class Limits(
        @SerialName("max_file_bytes") val maxFileBytes: Long? = null,
        @SerialName("skipped_files") val skippedFiles: Int = 0,
        @SerialName("skipped_bytes") val skippedBytes: Long = 0,
    )

    @Serializable
    data class Encryption(
        val scheme: String = "",
        val kdf: KDF,
        val verifier: String = "",
    ) {
        @Serializable
        data class KDF(
            val alg: String,
            val salt: String,
            @SerialName("m_kib") val mKib: Int? = null,
            val t: Int? = null,
            val p: Int? = null,
            val iterations: Int? = null,
        )
    }
}

@Serializable
data class BackupFileIndexEntry(
    val path: String,
    val size: Long = 0,
    val sha256: String? = null,
    val category: String = "",
    val skipped: String? = null,
    val isDirectory: Boolean? = null,
) {
    companion object {
        fun file(path: String, size: Long, sha256: String, category: BackupCategory) =
            BackupFileIndexEntry(path, size, sha256, category.key)

        fun sizeSkipped(path: String, size: Long, category: BackupCategory) =
            BackupFileIndexEntry(path, size, null, category.key, skipped = "size")

        fun unreadable(path: String, size: Long, category: BackupCategory) =
            BackupFileIndexEntry(path, size, null, category.key, skipped = "unreadable")

        fun directory(path: String, category: BackupCategory) =
            BackupFileIndexEntry(path, 0, null, category.key, isDirectory = true)
    }
}

@Serializable
data class BackupBlobIndexEntry(
    val sha256: String,
    val size: Long,
    val path: String,
    val sessionId: String? = null,
    val mime: String? = null,
)

@Serializable
data class BackupRecordEnvelope<T>(
    val t: String,
    val d: T,
)

@Serializable
data class BackupThinkingRuleRecord(
    val id: String,
    val instanceId: String,
    val sortOrder: Int = 0,
    val scopeKind: String = "allModels",
    val scopePattern: String? = null,
    val wireFormatJson: String = "{}",
    val echoField: String? = null,
    val echoTiming: String? = null,
    val label: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class BackupEnvVarMeta(
    val id: String,
    val key: String,
    val note: String = "",
    @Serializable(with = Iso8601MillisSerializer::class)
    val createdAt: Long = 0,
    val enabled: Boolean = true,
)

@Serializable
data class BackupSecrets(
    val v: Int = 1,
    val providers: List<ProviderSecret> = emptyList(),
    val envVars: List<EnvVarSecret> = emptyList(),
    val mcpOAuth: List<MCPOAuthSecret> = emptyList(),
) {
    @Serializable
    data class ProviderSecret(
        val instanceId: String,
        val label: String? = null,
        val providerType: String? = null,
        val apiKey: String? = null,
        val manualOAuthToken: String? = null,
        val oauthToken: String? = null,
        val oauthEmail: String? = null,
        val oauthGcpProject: String? = null,
    ) {
        val isEmpty: Boolean
            get() = apiKey == null && manualOAuthToken == null &&
                oauthToken == null && oauthEmail == null && oauthGcpProject == null
    }

    @Serializable
    data class EnvVarSecret(val name: String, val value: String)

    @Serializable
    data class MCPOAuthSecret(
        val serverId: String,
        val token: String,
        val clientSecret: String? = null,
    )
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
