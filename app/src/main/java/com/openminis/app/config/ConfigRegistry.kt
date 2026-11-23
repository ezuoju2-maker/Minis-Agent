package com.openminis.app.config

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository
import java.util.concurrent.atomic.AtomicBoolean

class ConfigRegistry internal constructor() {
    private val fields = LinkedHashMap<String, ConfigField>()
    private val collections = LinkedHashMap<String, ConfigCollection>()
    private val initialized = AtomicBoolean(false)

    fun register(field: ConfigField) {
        fields[field.path] = field
    }

    fun register(collection: ConfigCollection) {
        collections[collection.basePath] = collection
    }

    fun resolveField(path: String): ConfigField? {
        fields[path]?.let { return it }
        val (base, id, leaf) = splitCollectionPath(path) ?: return null
        val coll = collections[base] ?: return null
        return coll.fields(forId = id).firstOrNull { leafOf(it.path) == leaf }
    }

    fun explainUnknownPath(path: String): String {
        val dot = path.indexOf('.')
        val base = if (dot >= 0) path.substring(0, dot) else path
        val topicKnown = collections[base] != null ||
            fields.values.any { it.access != ConfigAccess.HIDDEN && (it.path == base || it.path.startsWith("$base.")) }
        if (base.isEmpty() || !topicKnown) {
            return "No topic '$base'. Run `minis-config list-topics`."
        }
        val coll = collections[base]
        if (coll == null || dot < 0) {
            return "No registered field at '$path'."
        }
        val rest = path.substring(dot + 1)
        fun leaves(id: String): List<String> =
            coll.fields(forId = id).filter { it.access != ConfigAccess.HIDDEN }.map { leafOf(it.path) }
        val entryLeaves = leaves(rest)
        if (entryLeaves.isNotEmpty()) {
            return "'$path' names an entry, not a field. Append a field, e.g. $base.$rest.${entryLeaves[0]}. Fields: ${entryLeaves.joinToString(", ")}."
        }
        val split = splitCollectionPath(path)
        if (split != null) {
            val (_, id, leaf) = split
            val idLeaves = leaves(id)
            if (idLeaves.isNotEmpty()) {
                return "Unknown field '$leaf' for $base entry '$id'. Fields: ${idLeaves.joinToString(", ")}."
            }
            return noEntryReason(base, id)
        }
        return noEntryReason(base, rest)
    }

    fun collection(basePath: String): ConfigCollection? = collections[basePath]

    fun allFieldsForValidation(): List<ConfigField> {
        val out = ArrayList<ConfigField>(fields.values)
        for (c in collections.values) {
            val first = runCatching { c.childIds().firstOrNull() }.getOrNull() ?: continue
            out.addAll(runCatching { c.fields(forId = first) }.getOrDefault(emptyList()))
        }
        return out.sortedBy { it.path }
    }

    fun allVisibleFieldPaths(): List<String> =
        fields.values
            .filter { it.access != ConfigAccess.HIDDEN }
            .map { it.path }
            .sorted()

    fun topics(): List<String> {
        val set = LinkedHashSet<String>()
        for (f in fields.values) {
            if (f.access == ConfigAccess.HIDDEN) continue
            val head = f.path.substringBefore('.', missingDelimiterValue = "")
            if (head.isNotEmpty()) set.add(head)
        }
        for (c in collections.values) set.add(c.basePath)
        return set.sorted()
    }

    fun fields(topic: String): List<ConfigField> {
        val out = ArrayList<ConfigField>()
        for (f in fields.values) {
            if (f.access == ConfigAccess.HIDDEN) continue
            if (f.path == topic || f.path.startsWith("$topic.")) out.add(f)
        }
        val coll = collections[topic]
        if (coll != null) {
            val firstId = coll.childIds().firstOrNull()
            if (firstId != null) {
                out.addAll(coll.fields(forId = firstId).filter { it.access != ConfigAccess.HIDDEN })
            }
        }
        return out.sortedBy { it.path }
    }

    companion object {
        fun splitCollectionPath(path: String): Triple<String, String, String>? {
            val first = path.indexOf('.')
            val last = path.lastIndexOf('.')
            if (first < 0 || first >= last) return null
            val base = path.substring(0, first)
            val id = path.substring(first + 1, last)
            val leaf = path.substring(last + 1)
            if (base.isEmpty() || id.isEmpty() || leaf.isEmpty()) return null
            return Triple(base, id, leaf)
        }

        fun leafOf(path: String): String = path.substringAfterLast('.')

        internal fun noEntryReason(base: String, id: String): String =
            "No entry '$id' under '$base'. Run `minis-config get $base` and use an entry_id verbatim — ids may contain dots and slashes, no escaping needed: $base.<entry_id>.<field>."

        @Volatile private var INSTANCE: ConfigRegistry? = null

        fun get(): ConfigRegistry =
            INSTANCE ?: error("ConfigRegistry not initialized; call init() from Application.onCreate")

        fun init(
            context: Context,
            providerRepository: ProviderRepository,
            envVarRepository: EnvVarRepository,
            chatRepository: ChatRepository,
        ): ConfigRegistry {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val r = ConfigRegistry()
                if (r.initialized.compareAndSet(false, true)) {
                    ConfigBuiltins.registerInto(
                        r, context, providerRepository, envVarRepository, chatRepository,
                    )
                    Thread({
                        runCatching {
                            com.openminis.app.config.readable.ConfigReadableRenderer
                                .validateRegistry(r.allFieldsForValidation())
                        }
                    }, "ConfigReadableValidate").apply {
                        isDaemon = true
                        priority = Thread.MIN_PRIORITY
                    }.start()
                }
                INSTANCE = r
                return r
            }
        }
    }
}
