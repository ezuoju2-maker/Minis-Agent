package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal object TitleCandidates {

    const val MAX_TRIES = 6

    const val AUTO_PAUSE_MS = 1_500L

    sealed class PrimarySource {
        data class Group(val groupId: String, val preferredEntryId: String?) : PrimarySource()
        data class Entry(val entryId: String) : PrimarySource()

        companion object {
            fun parse(bindingJson: String?): PrimarySource? {
                if (bindingJson.isNullOrBlank()) return null
                return try {
                    val obj = org.json.JSONObject(bindingJson)
                    when (obj.optString("type")) {
                        "group" -> obj.optString("groupId").takeIf { it.isNotEmpty() }
                            ?.let { Group(it, obj.optString("lastEntryId").takeIf { e -> e.isNotEmpty() }) }
                        "entry" -> obj.optString("entryId").takeIf { it.isNotEmpty() }?.let { Entry(it) }
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun isTitleEligible(entry: ModelEntry): Boolean {
        val outs = entry.model.outputModalities
        val outputsText = outs == null || outs.isEmpty() || outs.contains("text")
        val idLower = entry.model.id.lowercase()
        val nonChatId = listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video")
            .any { idLower.contains(it) }
        return outputsText && !nonChatId
    }

    fun expandGroup(
        group: ModelGroup,
        available: List<ModelEntry>,
        sessionId: String,
        preferredEntryId: String? = null,
    ): List<ModelEntry> {
        if (available.isEmpty()) return emptyList()
        val start = available.indexOfFirst { it.id == preferredEntryId }.takeIf { it >= 0 }
            ?: when (group.strategy) {
                RoutingStrategy.loadBalance -> Math.floorMod(sessionId.hashCode(), available.size)
                RoutingStrategy.fallback -> 0
            }
        return List(available.size) { available[(start + it) % available.size] }
    }

    fun order(
        sessionId: String,
        subGroup: ModelGroup?,
        primary: PrimarySource?,
        sessionModelId: String?,
        defaultPrimaryGroup: ModelGroup?,
        pool: List<ModelEntry>,
        group: (String) -> ModelGroup?,
        entry: (String) -> ModelEntry?,
        availableMembers: (ModelGroup) -> List<ModelEntry>,
        isAvailable: (ModelEntry) -> Boolean,
    ): List<ModelEntry> {
        val ordered = mutableListOf<ModelEntry>()
        val seen = HashSet<String>()
        fun push(e: ModelEntry?) {
            if (e == null || !seen.add(e.id)) return
            if (!isAvailable(e) || !isTitleEligible(e)) return
            ordered += e
        }
        fun pushGroup(g: ModelGroup?, preferred: String? = null) {
            if (g == null) return
            expandGroup(g, availableMembers(g), sessionId, preferred).forEach(::push)
        }

        pushGroup(subGroup)
        when (primary) {
            is PrimarySource.Group -> pushGroup(group(primary.groupId), primary.preferredEntryId)
            is PrimarySource.Entry -> push(entry(primary.entryId))
            null -> Unit
        }
        if (ordered.isEmpty() && !sessionModelId.isNullOrEmpty()) {
            push(pool.firstOrNull { it.model.id == sessionModelId && isAvailable(it) })
        }
        pushGroup(defaultPrimaryGroup)
        pool.forEach(::push)
        return ordered
    }

    fun forSession(
        repo: ProviderRepository,
        sessionId: String,
        primary: PrimarySource?,
        sessionModelId: String?,
    ): List<ModelEntry> {
        val config = repo.config.value
        val instanceUsable = HashMap<String, Boolean>()
        fun isAvailable(e: ModelEntry): Boolean {
            if (e.isHidden) return false
            return instanceUsable.getOrPut(e.providerInstanceId) {
                val inst = config.instances.find { it.id == e.providerInstanceId }
                inst != null && inst.isEnabled && repo.hasAnyCredential(inst)
            }
        }
        val entriesById = config.modelEntries.associateBy { it.id }
        return order(
            sessionId = sessionId,
            subGroup = repo.defaultSubGroupId?.let { repo.group(it) },
            primary = primary,
            sessionModelId = sessionModelId,
            defaultPrimaryGroup = repo.defaultPrimaryGroupId?.let { repo.group(it) },
            pool = repo.allVisibleEntries(),
            group = { repo.group(it) },
            entry = { id -> entriesById[id] },
            availableMembers = { g -> g.memberEntryIds.mapNotNull { entriesById[it] }.filter(::isAvailable) },
            isAvailable = ::isAvailable,
        )
    }

    suspend fun providerFor(
        repo: ProviderRepository,
        context: Context,
        entry: ModelEntry,
        sessionId: String,
    ): LLMProvider? {
        val instance = repo.instance(entry.providerInstanceId) ?: return null
        val apiKey = repo.requestCredential(instance) ?: return null
        return try {
            ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = sessionId, overrides = entry.overrides)
        } catch (e: Exception) {
            Log.w("TitleGen", "provider creation failed for ${entry.model.id}: ${e.message}")
            null
        }
    }

    suspend fun isRealCancellation(e: kotlinx.coroutines.CancellationException): Boolean =
        e.cause == null || !kotlinx.coroutines.currentCoroutineContext().isActive

    suspend fun <T : Any> walk(
        candidates: List<ModelEntry>,
        origin: String,
        maxTries: Int = MAX_TRIES,
        pauseMs: Long = 0L,
        shouldStop: suspend () -> Boolean = { false },
        tryOne: suspend (ModelEntry) -> T?,
    ): T? {
        val tries = candidates.take(maxTries)
        for ((idx, entry) in tries.withIndex()) {
            if (shouldStop()) {
                AppLogger.info("TitleGen", "walk origin=$origin stopped before candidate ${idx + 1}/${tries.size}")
                return null
            }
            AppLogger.info("TitleGen", "walk origin=$origin candidate ${idx + 1}/${tries.size} model=${entry.model.id}")
            val result = try {
                tryOne(entry)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && isRealCancellation(e)) throw e
                AppLogger.warning(
                    "TitleGen",
                    "walk origin=$origin FAILED ${idx + 1}/${tries.size} model=${entry.model.id} " +
                        "${e.javaClass.simpleName}: ${e.message?.take(200)}",
                )
                null
            }
            if (result != null) {
                if (idx > 0) AppLogger.info("TitleGen", "walk origin=$origin succeeded on fallback candidate ${idx + 1} (${entry.model.id})")
                return result
            }
            if (pauseMs > 0 && idx + 1 < tries.size) delay(pauseMs)
        }
        AppLogger.warning("TitleGen", "walk origin=$origin all ${tries.size} candidate(s) failed (of ${candidates.size})")
        return null
    }
}
