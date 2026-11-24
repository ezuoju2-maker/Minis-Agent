package com.openminis.app.config

import com.openminis.app.config.audit.ConfigAuditActor
import com.openminis.app.config.audit.ConfigAuditEntry
import com.openminis.app.config.audit.ConfigAuditLog
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ReadableSummary
import com.openminis.app.config.readable.RedactedConfigValue
import com.openminis.app.config.audit.ConfigAuditStatus
import com.openminis.app.config.confirm.ConfigConfirmationGate
import com.openminis.app.config.confirm.ConfirmOutcome
import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.config.confirm.PendingConfigChangeItem
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object ConfigBridge {
    private const val TAG = "ConfigBridge"
    private const val DEFAULT_PAGE_SIZE = 20
    private const val MAX_PAGE_SIZE = 100

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun isEnabled(): Boolean = MinisConfigPermissionStore.isEnabled

    fun unavailableErrorEnvelope(path: String, reason: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", "feature_unavailable")
        put("reason", reason)
        put("user_message", "The setting '$path' isn't available on this device: $reason")
    }

    fun disabledErrorEnvelope(): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", "permission_denied")
        put("reason", "minis-config is disabled in Settings → Permissions.")
        put(
            "user_message",
            "I tried to change a setting but minis-config is currently disabled. " +
                "You can enable it at [Settings → Permissions](minis://settings/permissions), " +
                "then ask me again. Or change the setting yourself directly through the relevant Settings screen."
        )
    }


    fun pathNoteForTopic(topic: String): String? {
        if (ConfigRegistry.get().collection(topic) == null) return null
        return "Child paths are $topic.<entry_id>.<field>. Use entry_id exactly as `minis-config get $topic` prints it — dots and slashes need no escaping."
    }

    fun allTopics(): JSONArray =
        JSONArray().also { arr -> for (t in ConfigRegistry.get().topics()) arr.put(t) }

    fun fieldsForTopic(topic: String): JSONArray =
        JSONArray().also { arr ->
            for (f in ConfigRegistry.get().fields(topic = topic)) {
                arr.put(JSONObject().apply {
                    put("path", f.path)
                    put("display_name", f.displayName)
                    put("description", f.description)
                    put("schema", f.valueSchema.helpDescription)
                    put("access", f.access.name.lowercase())
                    put("risk", f.risk.name.lowercase())
                    put("revertable", f.revertable)
                })
            }
        }


    fun readField(
        path: String,
        filter: String? = null,
        page: Int = 0,
        pageSize: Int = 0,
    ): JSONObject {
        val base = readFieldRaw(path)
        if (base.optBoolean("ok", false) != true) return base

        val filterText = filter?.trim()?.takeIf { it.isNotEmpty() }
        val terms: List<String> = filterText
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val valueJSON = base.optString("value", "null")
        val parsed = ConfigValue.decode(valueJSON) ?: return base

        fun matches(candidate: ConfigValue): Boolean {
            if (terms.isEmpty()) return true
            val hay = candidate.jsonString().lowercase()
            return terms.all { hay.contains(it) }
        }

        val out = JSONObject()
        val keys = base.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, base.opt(k))
        }

        if (parsed is ConfigValue.Arr) {
            val totalRaw = parsed.value.size
            val postFilter = if (terms.isEmpty()) parsed.value else parsed.value.filter(::matches)
            val postFilterCount = postFilter.size

            if (terms.isNotEmpty()) {
                out.put("filtered", true)
                out.put("filter", filterText ?: "")
                out.put("total", totalRaw)
                out.put("matched", postFilterCount)
            }

            val wantsPagination = page > 0 || pageSize > 0
            if (wantsPagination) {
                val size = (if (pageSize > 0) pageSize else DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
                val totalPages = maxOf(1, (postFilterCount + size - 1) / size)
                val requestedPage = if (page > 0) page else 1
                val pageSlice: List<ConfigValue> =
                    if (requestedPage < 1 || requestedPage > totalPages || postFilterCount == 0) {
                        emptyList()
                    } else {
                        val start = (requestedPage - 1) * size
                        val end = minOf(start + size, postFilterCount)
                        postFilter.subList(start, end)
                    }
                out.put("value", ConfigValue.Arr(pageSlice).jsonString())
                val hasNext = requestedPage < totalPages && postFilterCount > 0
                val hasPrev = requestedPage > 1
                out.put("pagination", JSONObject().apply {
                    put("page", requestedPage)
                    put("page_size", size)
                    put("total", postFilterCount)
                    put("total_pages", totalPages)
                    put("has_next", hasNext)
                    put("has_prev", hasPrev)
                })
                out.put(
                    "agent_hint",
                    paginationHint(
                        path = path, filter = filterText,
                        page = requestedPage, pageSize = size,
                        totalPages = totalPages, total = postFilterCount,
                        pageCount = pageSlice.size,
                    )
                )
            } else if (terms.isNotEmpty()) {
                out.put("value", ConfigValue.Arr(postFilter).jsonString())
            }
        } else if (terms.isNotEmpty()) {
            val kept: ConfigValue = if (matches(parsed)) parsed else ConfigValue.Null
            out.put("value", kept.jsonString())
            out.put("filtered", true)
            out.put("filter", filterText ?: "")
            out.put("total", 1)
            out.put("matched", if (kept === ConfigValue.Null) 0 else 1)
        }

        return out
    }

    private fun readFieldRaw(path: String): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val field = ConfigRegistry.get().resolveField(path) ?: return JSONObject().apply {
            put("ok", false)
            put("error", "unknown_path")
            put("reason", ConfigRegistry.get().explainUnknownPath(path))
        }
        if (field.access == ConfigAccess.HIDDEN) return JSONObject().apply {
            put("ok", false)
            put("error", "permission_denied")
            put("reason", "'$path' is intentionally not exposed to minis-config.")
        }
        field.unavailableReason?.let { return unavailableErrorEnvelope(path, it) }
        return try {
            val v = field.read()
            JSONObject().apply {
                put("ok", true)
                put("value", v.jsonString())
                put("schema", field.valueSchema.helpDescription)
                put("display_name", field.displayName)
            }
        } catch (e: ConfigError.PermissionDenied) {
            JSONObject().apply {
                put("ok", false)
                put("error", "permission_denied")
                put("reason", e.message ?: "Read access denied.")
            }
        } catch (e: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("error", "read_failed")
                put("reason", e.message ?: e.toString())
            }
        }
    }

    private fun paginationHint(
        path: String,
        filter: String?,
        page: Int,
        pageSize: Int,
        totalPages: Int,
        total: Int,
        pageCount: Int,
    ): String {
        val filterFragment = if (filter.isNullOrEmpty()) "" else " --filter \"$filter\""
        if (total == 0) {
            return if (!filter.isNullOrEmpty()) {
                "No items match filter '$filter' under '$path'."
            } else {
                "No items under '$path'."
            }
        }
        if (page > totalPages) {
            val pgWord = if (totalPages == 1) "page" else "pages"
            return "Page $page is out of range (only $totalPages $pgWord available). " +
                "Try: minis-config get $path$filterFragment --page $totalPages"
        }
        if (totalPages <= 1) {
            val itemWord = if (total == 1) "item" else "items"
            return "Showing all $total $itemWord (1 page)."
        }
        if (page < totalPages) {
            return "Showing page $page of $totalPages ($pageCount of $total items). " +
                "To get more, use: minis-config get $path$filterFragment --page ${page + 1} --page-size $pageSize"
        }
        return "Showing page $page of $totalPages ($pageCount of $total items). This is the last page."
    }


    fun writeFields(
        items: JSONArray,
        caption: String?,
        actorRaw: String,
        sessionId: String?,
    ): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        return runBlocking {
            performWriteBatch(
                items = items,
                caption = caption,
                actorRaw = actorRaw,
                sessionId = sessionId,
                skipConfirmation = false,
            )
        }
    }

    suspend fun performWriteBatch(
        items: JSONArray,
        caption: String?,
        actorRaw: String,
        sessionId: String?,
        skipConfirmation: Boolean,
    ): JSONObject {
        val resolved = ArrayList<Resolved>(items.length())
        val resolvedItems = ArrayList<PendingConfigChangeItem>(items.length())
        val readables = ArrayList<ReadableSummary>(items.length())
        for (i in 0 until items.length()) {
            val raw = items.optJSONObject(i) ?: continue
            val rawPath = raw.optString("path", "")
            val valueJSON = raw.optString("value_json", "null")

            val isAppend = rawPath.endsWith(".append")
            val isRemove = !isAppend && rawPath.endsWith(".remove")
            val resolvePath = when {
                isAppend -> rawPath.removeSuffix(".append")
                isRemove -> rawPath.removeSuffix(".remove")
                else -> rawPath
            }

            if ((isAppend || isRemove) && ConfigRegistry.get().collection(resolvePath) != null) {
                val coll = ConfigRegistry.get().collection(resolvePath)!!
                val parsedValue = ConfigValue.decode(valueJSON) ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "invalid_value")
                    put("reason", "Value JSON is not parseable for '$rawPath'.")
                }
                if (isAppend && !coll.addable) {
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", "permission_denied")
                        put("reason", "Collection '${coll.basePath}' does not allow .append.")
                    }
                }
                if (isRemove && !coll.removable) {
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", "permission_denied")
                        put("reason", "Collection '${coll.basePath}' does not allow .remove.")
                    }
                }
                val redactedNewValue = if (isAppend) parsedValue.redactingSecrets() else parsedValue
                resolved.add(
                    Resolved(
                        field = null,
                        collection = coll,
                        collectionVerb = if (isAppend) CollectionVerb.APPEND else CollectionVerb.REMOVE,
                        rawPath = rawPath,
                        scope = coll.basePath,
                        oldValue = ConfigValue.Null,
                        newValue = parsedValue,
                        auditNewValue = redactedNewValue,
                    )
                )
                val readable = if (isAppend) {
                    ConfigReadableRenderer.forCollectionAdd(coll, RedactedConfigValue.payload(parsedValue))
                } else {
                    ConfigReadableRenderer.forCollectionRemove(
                        coll,
                        (parsedValue as? ConfigValue.Str)?.value ?: parsedValue.displayString,
                    )
                }
                readables.add(readable)
                resolvedItems.add(
                    PendingConfigChangeItem(
                        displayName = coll.displayName,
                        path = rawPath,
                        oldDisplay = if (isAppend) "" else parsedValue.displayString,
                        newDisplay = if (isAppend) redactedNewValue.displayString else "",
                        verb = if (isAppend) "add" else "remove",
                        risk = coll.risk,
                        readable = readable,
                    )
                )
                continue
            }

            val field = ConfigRegistry.get().resolveField(resolvePath)
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "unknown_path")
                    put("reason", ConfigRegistry.get().explainUnknownPath(resolvePath))
                }

            field.unavailableReason?.let { return unavailableErrorEnvelope(rawPath, it) }

            if (field.access != ConfigAccess.READWRITE) {
                val reason = if (field.access == ConfigAccess.HIDDEN) {
                    "'$rawPath' is intentionally not exposed to minis-config."
                } else {
                    "'$rawPath' is read-only."
                }
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "permission_denied")
                    put("reason", reason)
                }
            }

            val parsedValue = ConfigValue.decode(valueJSON) ?: return JSONObject().apply {
                put("ok", false)
                put("error", "invalid_value")
                put("reason", "Value JSON is not parseable for '$rawPath'.")
            }

            val oldValue: ConfigValue = try {
                field.read()
            } catch (_: Throwable) {
                ConfigValue.Null
            }

            val newValue: ConfigValue
            val displayVerb: String
            val displayPath: String
            val displayOld: String
            val displayNew: String

            when {
                isAppend -> {
                    val arrSchema = field.valueSchema as? ConfigSchema.Array
                        ?: return JSONObject().apply {
                            put("ok", false)
                            put("error", "invalid_value")
                            put("reason", "'.append' is only valid on array-typed fields; '${field.path}' is not an array.")
                        }
                    try {
                        arrSchema.inner.validate(parsedValue)
                    } catch (e: ConfigError) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "validation_failed")
                            put("reason", e.message ?: e.toString())
                        }
                    }
                    val existing = (oldValue as? ConfigValue.Arr)?.value ?: emptyList()
                    newValue = ConfigValue.Arr(existing + parsedValue)
                    displayVerb = "append"
                    displayPath = rawPath
                    displayOld = ""
                    displayNew = parsedValue.displayString
                }
                isRemove -> {
                    if (field.valueSchema !is ConfigSchema.Array) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "invalid_value")
                            put("reason", "'.remove' is only valid on array-typed fields; '${field.path}' is not an array.")
                        }
                    }
                    val existing = (oldValue as? ConfigValue.Arr)?.value ?: emptyList()
                    val filtered = existing.filter { it != parsedValue }
                    if (filtered.size == existing.size) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "not_found")
                            put("reason", "No occurrence of value ${parsedValue.displayString} in '${field.path}'.")
                        }
                    }
                    newValue = ConfigValue.Arr(filtered)
                    displayVerb = "remove"
                    displayPath = rawPath
                    displayOld = parsedValue.displayString
                    displayNew = ""
                }
                else -> {
                    // [FEAT-261021-03] normalize -> schema -> field checks,
                    val normalized = try {
                        field.normalize(parsedValue).also {
                            field.valueSchema.validate(it)
                            field.validate(it)
                        }
                    } catch (e: ConfigError) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "validation_failed")
                            put("reason", e.message ?: e.toString())
                        }
                    }
                    newValue = normalized
                    displayVerb = "set"
                    displayPath = field.path
                    displayOld = oldValue.displayString
                    displayNew = newValue.displayString
                }
            }

            val isSecretField = field.path.substringAfterLast('.') in ConfigValue.SECRET_KEYS
            val redactedNewValue = if (isSecretField) maskScalarSecret(newValue) else newValue
            resolved.add(
                Resolved(
                    field = field,
                    collection = null,
                    collectionVerb = null,
                    rawPath = field.path,
                    scope = field.scope,
                    oldValue = oldValue,
                    newValue = newValue,
                    auditNewValue = if (isSecretField) redactedNewValue else null,
                )
            )
            val fieldLeaf = field.path.substringAfterLast('.')
            val readable = ConfigReadableRenderer.forFieldWrite(
                field,
                old = RedactedConfigValue.field(fieldLeaf, oldValue),
                new = RedactedConfigValue.field(fieldLeaf, newValue),
            )
            readables.add(readable)
            resolvedItems.add(
                PendingConfigChangeItem(
                    displayName = field.displayName,
                    path = displayPath,
                    oldDisplay = displayOld,
                    newDisplay = if (isSecretField) redactedNewValue.displayString else displayNew,
                    verb = displayVerb,
                    risk = field.risk,
                    readable = readable,
                )
            )
        }

        val outcome: ConfirmOutcome = if (skipConfirmation) {
            ConfirmOutcome.Approved(resolvedItems)
        } else {
            val pending = PendingConfigChange(items = resolvedItems, caption = caption)
            ConfigConfirmationGate.requestConfirmation(pending)
        }

        val actor = ConfigAuditActor.fromRaw(actorRaw)
        val audit = ConfigAuditLog.get()
        return when (outcome) {
            is ConfirmOutcome.TimedOut -> {
                val now = System.currentTimeMillis()
                for ((i, r) in resolved.withIndex()) {
                    audit.append(
                        ConfigAuditEntry(
                            id = UUID.randomUUID().toString(),
                            at = now, actor = actor, sessionId = sessionId,
                            scope = r.scope, key = r.rawPath,
                            oldValueJSON = r.oldValue.jsonString(),
                            newValueJSON = r.newValue.jsonString(),
                            confirmedAt = null, status = ConfigAuditStatus.TIMEOUT,
                            revertOf = null, caption = caption,
                            readable = readables.getOrNull(i),
                        )
                    )
                }
                JSONObject().apply {
                    put("ok", false)
                    put("error", "timeout")
                    put("reason", "Confirmation timed out after ${ConfigConfirmationGate.TIMEOUT_MS / 1000}s.")
                    put("user_message", "I waited but you didn't confirm the change. Tap me again if you want me to retry.")
                }
            }
            is ConfirmOutcome.Rejected -> {
                val now = System.currentTimeMillis()
                for ((i, r) in resolved.withIndex()) {
                    audit.append(
                        ConfigAuditEntry(
                            id = UUID.randomUUID().toString(),
                            at = now, actor = actor, sessionId = sessionId,
                            scope = r.scope, key = r.rawPath,
                            oldValueJSON = r.oldValue.jsonString(),
                            newValueJSON = r.newValue.jsonString(),
                            confirmedAt = now, status = ConfigAuditStatus.REJECTED,
                            revertOf = null, caption = caption,
                            readable = readables.getOrNull(i),
                        )
                    )
                }
                JSONObject().apply {
                    put("ok", false)
                    put("error", "user_rejected")
                    put("reason", "User cancelled the change.")
                    put("user_message", "Change cancelled.")
                }
            }
            is ConfirmOutcome.Approved -> ConfigWriteWarnings.collect {
                val now = System.currentTimeMillis()
                val applied = JSONArray()
                val auditIds = JSONArray()
                var earlyError: JSONObject? = null
                for ((idx, row) in outcome.items.withIndex()) {
                    if (idx >= resolved.size) continue
                    val r = resolved[idx]
                    val readable = readables.getOrNull(idx)
                    if (!row.isApproved) {
                        val auditId = UUID.randomUUID().toString()
                        audit.append(
                            ConfigAuditEntry(
                                id = auditId, at = now, actor = actor, sessionId = sessionId,
                                scope = r.scope, key = r.rawPath,
                                oldValueJSON = r.oldValue.jsonString(),
                                newValueJSON = r.newValue.jsonString(),
                                confirmedAt = now, status = ConfigAuditStatus.REJECTED,
                                revertOf = null, caption = caption,
                                readable = readable,
                            )
                        )
                        continue
                    }
                    try {
                        var effectiveOldJSON = r.oldValue.jsonString()
                        var effectiveNewJSON = r.newValue.jsonString()
                        var effectiveOldDisplay = r.oldValue.displayString
                        var effectiveNewDisplay = r.newValue.displayString
                        val displayName: String
                        val displayPath: String
                        if (r.collection != null && r.collectionVerb != null) {
                            when (r.collectionVerb) {
                                CollectionVerb.APPEND -> {
                                    val newId = r.collection.add(r.newValue)
                                    val auditPayload = r.auditNewValue ?: r.newValue
                                    val withId = augmentWithEntryId(auditPayload, newId)
                                    effectiveNewJSON = withId.jsonString()
                                    effectiveNewDisplay = withId.displayString
                                }
                                CollectionVerb.REMOVE -> {
                                    val entryId = (r.newValue as? ConfigValue.Str)?.value
                                        ?: throw ConfigError.InvalidValue(
                                            "${r.collection.basePath}.remove expects a string entry id."
                                        )
                                    val snapshot = snapshotCollectionChild(r.collection, entryId)
                                    r.collection.remove(entryId)
                                    effectiveOldJSON = snapshot.jsonString()
                                    effectiveOldDisplay = snapshot.displayString
                                    effectiveNewJSON = ConfigValue.Null.jsonString()
                                    effectiveNewDisplay = ""
                                }
                            }
                            displayName = r.collection.displayName
                            displayPath = r.rawPath
                        } else if (r.field != null) {
                            r.field.write(r.newValue)
                            displayName = r.field.displayName
                            displayPath = r.field.path
                            if (r.auditNewValue != null) {
                                effectiveNewJSON = r.auditNewValue.jsonString()
                                effectiveNewDisplay = r.auditNewValue.displayString
                            }
                        } else {
                            continue
                        }
                        val auditId = UUID.randomUUID().toString()
                        audit.append(
                            ConfigAuditEntry(
                                id = auditId, at = now, actor = actor, sessionId = sessionId,
                                scope = r.scope, key = r.rawPath,
                                oldValueJSON = effectiveOldJSON,
                                newValueJSON = effectiveNewJSON,
                                confirmedAt = now, status = ConfigAuditStatus.APPLIED,
                                revertOf = null, caption = caption,
                                readable = readable,
                            )
                        )
                        auditIds.put(auditId)
                        applied.put(JSONObject().apply {
                            put("path", displayPath)
                            put("display_name", displayName)
                            put("old", effectiveOldDisplay)
                            put("new", effectiveNewDisplay)
                        })
                    } catch (e: ConfigError.AlreadyExists) {
                        if (earlyError == null) {
                            earlyError = JSONObject().apply {
                                put("ok", false)
                                put("error", "already_exists")
                                put("reason", e.message ?: "already exists")
                            }
                        }
                        AppLogger.warning(TAG, "${r.rawPath}: ${e.message}")
                    } catch (e: ConfigError.PermissionDenied) {
                        if (earlyError == null) {
                            earlyError = JSONObject().apply {
                                put("ok", false)
                                put("error", "permission_denied")
                                put("reason", e.message ?: "permission denied")
                            }
                        }
                        AppLogger.warning(TAG, "${r.rawPath}: ${e.message}")
                    } catch (e: ConfigError) {
                        if (earlyError == null) {
                            earlyError = JSONObject().apply {
                                put("ok", false)
                                put("error", "invalid_value")
                                put("reason", e.message ?: "invalid value")
                            }
                        }
                        AppLogger.warning(TAG, "${r.rawPath}: ${e.message}")
                    } catch (e: Throwable) {
                        AppLogger.error(TAG, "write failed for ${r.rawPath}: ${e.message}")
                    }
                }
                if (applied.length() == 0 && earlyError != null) {
                    earlyError!!
                } else if (applied.length() == 0) {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "all_rejected")
                        put("reason", "User rejected every row.")
                        put("user_message", "No changes applied.")
                    }
                } else {
                    JSONObject().apply {
                        put("ok", true)
                        put("applied", applied)
                        put("audit_ids", auditIds)
                        put("audit_url", "minis://settings/logs?tab=config-audit")
                        put("user_message", "Settings updated. Review or revert at [Logs → Config Changes](minis://settings/logs?tab=config-audit).")
                    }
                }
            }.let { (response, warnings) ->
                if (warnings.isNotEmpty()) response.put("warnings", JSONArray(warnings))
                response
            }
        }
    }

    private data class Resolved(
        val field: ConfigField?,
        val collection: ConfigCollection?,
        val collectionVerb: CollectionVerb?,
        val rawPath: String,
        val scope: String,
        val oldValue: ConfigValue,
        val newValue: ConfigValue,
        val auditNewValue: ConfigValue? = null,
    )

    private enum class CollectionVerb { APPEND, REMOVE }

    private fun maskScalarSecret(v: ConfigValue): ConfigValue {
        if (v !is ConfigValue.Str) return v
        if (v.value.startsWith("\$\$")) return v
        if (v.value.isEmpty()) return v
        return ConfigValue.Str("••• (hidden)")
    }

    private fun augmentWithEntryId(payload: ConfigValue, entryId: String): ConfigValue {
        val baseMap = (payload as? ConfigValue.Obj)?.value?.toMutableMap()
            ?: LinkedHashMap()
        baseMap["entry_id"] = ConfigValue.Str(entryId)
        return ConfigValue.Obj(baseMap)
    }

    private fun snapshotCollectionChild(coll: ConfigCollection, childId: String): ConfigValue {
        val out = LinkedHashMap<String, ConfigValue>()
        out["entry_id"] = ConfigValue.Str(childId)
        for (field in coll.fields(forId = childId)) {
            val leaf = field.path.substringAfterLast('.', missingDelimiterValue = field.path)
            val v = try { field.read() } catch (_: Throwable) { ConfigValue.Null }
            out[leaf] = v
        }
        return ConfigValue.Obj(out)
    }


    fun auditList(limit: Int, scope: String?): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val entries = ConfigAuditLog.get().recent(limit, scope)
        val usage = ConfigAuditLog.get().usage()
        return JSONObject().apply {
            put("ok", true)
            put("count", entries.size)
            put("capacity", usage.capacity)
            put("total_used", usage.count)
            put("entries", JSONArray().also { arr -> for (e in entries) arr.put(auditEntryDict(e)) })
        }
    }

    fun auditGet(id: String): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val e = ConfigAuditLog.get().get(id) ?: return JSONObject().apply {
            put("ok", false)
            put("error", "not_found")
            put("reason", "No audit entry with id '$id'.")
        }
        return JSONObject().apply {
            put("ok", true)
            put("entry", auditEntryDict(e))
        }
    }

    private fun auditEntryDict(e: ConfigAuditEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("at", isoFormatter.format(Date(e.at)))
        put("actor", e.actor.raw)
        put("scope", e.scope)
        put("key", e.key)
        put("old", e.oldValueJSON)
        put("new", e.newValueJSON)
        put("status", e.status.raw)
        if (e.sessionId != null) put("session_id", e.sessionId)
        if (e.confirmedAt != null) put("confirmed_at", isoFormatter.format(Date(e.confirmedAt)))
        if (e.revertOf != null) put("revert_of", e.revertOf)
        if (!e.caption.isNullOrEmpty()) put("caption", e.caption)
    }

    fun auditRevert(
        id: String,
        actorRaw: String,
        sessionId: String?,
        skipConfirmation: Boolean,
    ): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val entry = ConfigAuditLog.get().get(id) ?: return JSONObject().apply {
            put("ok", false)
            put("error", "not_found")
            put("reason", "No audit entry with id '$id'.")
        }
        if (entry.status != ConfigAuditStatus.APPLIED) return JSONObject().apply {
            put("ok", false)
            put("error", "not_revertable")
            put("reason", "Entry is ${entry.status.raw}; only applied entries can be reverted.")
        }

        val items: JSONArray = if (entry.key.endsWith(".append") || entry.key.endsWith(".remove")) {
            val basePath = entry.key.substringBeforeLast('.')
            val coll = ConfigRegistry.get().collection(basePath)
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "not_revertable")
                    put("reason", "Collection '$basePath' no longer registered.")
                }
            if (entry.key.endsWith(".append")) {
                val newVal = ConfigValue.decode(entry.newValueJSON) as? ConfigValue.Obj
                val entryId = (newVal?.value?.get("entry_id") as? ConfigValue.Str)?.value
                    ?: return JSONObject().apply {
                        put("ok", false)
                        put("error", "not_revertable")
                        put("reason", "Audit entry missing entry_id; cannot reverse the add.")
                    }
                JSONArray().put(JSONObject().apply {
                    put("path", "${coll.basePath}.remove")
                    put("value_json", ConfigValue.Str(entryId).jsonString())
                })
            } else {
                val oldVal = (ConfigValue.decode(entry.oldValueJSON) as? ConfigValue.Obj)?.value
                    ?: return JSONObject().apply {
                        put("ok", false)
                        put("error", "not_revertable")
                        put("reason", "Audit entry missing old-value snapshot; cannot reverse the remove.")
                    }
                val replay = LinkedHashMap<String, ConfigValue>()
                oldVal["providerInstanceId"]?.let { replay["instance_id"] = it }
                oldVal["modelId"]?.let { replay["model_id"] = it }
                oldVal["displayName"]?.let { replay["display_name"] = it }
                JSONArray().put(JSONObject().apply {
                    put("path", "${coll.basePath}.append")
                    put("value_json", ConfigValue.Obj(replay).jsonString())
                })
            }
        } else {
            val field = ConfigRegistry.get().resolveField(entry.key)
            if (field == null || !field.revertable) return JSONObject().apply {
                put("ok", false)
                put("error", "not_revertable")
                put("reason", "Field '${entry.key}' no longer registered, or doesn't support revert.")
            }
            JSONArray().put(JSONObject().apply {
                put("path", entry.key)
                put("value_json", entry.oldValueJSON)
            })
        }
        val res = runBlocking {
            performWriteBatch(
                items = items,
                caption = "minis-config audit revert ${entry.id.take(8)}",
                actorRaw = actorRaw,
                sessionId = sessionId,
                skipConfirmation = skipConfirmation,
            )
        }
        if (res.optBoolean("ok", false)) {
            ConfigAuditLog.get().markReverted(entry.id)
        }
        return res
    }
}
