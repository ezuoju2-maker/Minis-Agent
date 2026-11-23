package com.openminis.app.quota

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.auth.OAuthManager
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


private const val TAG = "Quota"

sealed class QuotaInstanceDisplay {
    data object Hidden : QuotaInstanceDisplay()
    data class Unsupported(val reason: QuotaSubject.UnsupportedReason) : QuotaInstanceDisplay()
    data object Loading : QuotaInstanceDisplay()

    data class Empty(val subjectId: QuotaSubjectID) : QuotaInstanceDisplay()

    data class Failed(val error: QuotaError) : QuotaInstanceDisplay()
    data class Pools(val subjectId: QuotaSubjectID) : QuotaInstanceDisplay()
}

sealed class QuotaRefreshResult {
    data object Refreshed : QuotaRefreshResult()
    data class Failed(val error: QuotaError) : QuotaRefreshResult()
    data class Blocked(val decision: QuotaRefreshDecision) : QuotaRefreshResult()
    data object Unsupported : QuotaRefreshResult()
    data class NotConfigured(val reason: QuotaSubject.NotConfiguredReason) : QuotaRefreshResult()
    data object CapabilityFailed : QuotaRefreshResult()
}

data class QuotaState(
    val subjects: Map<QuotaSubjectID, QuotaSubject> = emptyMap(),
    val pools: Map<QuotaPoolKey, QuotaPool> = emptyMap(),
    val bindings: List<QuotaPoolBinding> = emptyList(),
    val capabilities: Map<QuotaCapabilityKey, QuotaCapabilityRecord> = emptyMap(),
    val summaries: Map<String, QuotaSnapshotSummary> = emptyMap(),
    val instanceSubjects: Map<String, QuotaSubjectID> = emptyMap(),
    val instanceNotes: Map<String, QuotaSubject.NotConfiguredReason> = emptyMap(),
    val refreshingInstanceIds: Set<String> = emptySet(),
) {
    fun poolsOf(subject: QuotaSubjectID): List<QuotaPool> =
        pools.values.filter { it.subjectId == subject }
            .sortedWith(compareBy<QuotaPool> { kindOrder(it.kind) }.thenBy { it.id.rawValue })

    fun bindingsOf(subject: QuotaSubjectID): List<QuotaPoolBinding> =
        bindings.filter { pools[it.poolKey]?.subjectId == subject }

    fun isRefreshing(instanceId: String): Boolean = instanceId in refreshingInstanceIds

    fun sharingInstanceIds(instanceId: String): List<String> {
        val sid = instanceSubjects[instanceId] ?: return emptyList()
        val s = subjects[sid] ?: return emptyList()
        return s.instanceIds.filter { it != instanceId }.sorted()
    }

    fun poolStatus(pool: QuotaPool, now: Long = System.currentTimeMillis()): PoolStatus =
        QuotaStatusRules.poolStatus(pool, now, QuotaStore.freshnessPolicy, QuotaStore.thresholds)

    fun modelView(instanceId: String, modelId: String, now: Long = System.currentTimeMillis()): ModelQuotaView =
        QuotaModelResolver.view(
            instanceId = instanceId, modelId = modelId, subjectId = instanceSubjects[instanceId],
            pools = pools, bindings = bindings, manualBindings = emptyList(), now = now,
            freshness = QuotaStore.freshnessPolicy, thresholds = QuotaStore.thresholds,
        )

    fun subjectLevel(instanceId: String, now: Long = System.currentTimeMillis()): QuotaLevel {
        val sid = instanceSubjects[instanceId] ?: return QuotaLevel.normal
        return QuotaLevelRules.level(poolsOf(sid).map { poolStatus(it, now) to it })
    }

    fun headline(instanceId: String, now: Long = System.currentTimeMillis()): Pair<QuotaPool, PoolStatus>? {
        val sid = instanceSubjects[instanceId] ?: return null
        return QuotaHeadline.pick(poolsOf(sid), now, QuotaStore.freshnessPolicy, QuotaStore.thresholds)
    }

    data class RelayDetection(val text: String, val found: Boolean, val at: Long)

    fun relayDetection(d: QuotaInstanceDescriptor): RelayDetection? {
        if (QuotaVendorDetector.vendor(d) != QuotaVendor.customRelay) return null
        val rec = capabilities[d.capabilityKey] ?: return null
        return when (rec.status) {
            QuotaCapabilityRecord.Status.supported -> {
                val kind = RelayKind.fromAdapterId(rec.adapterId) ?: return null
                RelayDetection("${kind.displayName} · ${kind.path}", true, rec.probedAt)
            }
            QuotaCapabilityRecord.Status.failed, QuotaCapabilityRecord.Status.unsupported ->
                RelayDetection(rec.detail.orEmpty(), false, rec.probedAt)
        }
    }

    fun display(d: QuotaInstanceDescriptor): QuotaInstanceDisplay {
        val cap = QuotaAdapterRegistry.adapter(d).capability(d)
        if (!cap.active) {
            return QuotaInstanceDisplay.Unsupported(
                cap.unsupportedReason ?: QuotaSubject.UnsupportedReason.providerTypeUnsupported,
            )
        }
        if (!d.isEnabled) return QuotaInstanceDisplay.Hidden
        if (instanceNotes[d.instanceId] != null) return QuotaInstanceDisplay.Hidden
        val sid = instanceSubjects[d.instanceId]
        val rec = capabilities[d.capabilityKey]
        if (rec?.status == QuotaCapabilityRecord.Status.failed && (sid == null || poolsOf(sid).isEmpty())) {
            return QuotaInstanceDisplay.Unsupported(QuotaSubject.UnsupportedReason.capabilityProbeFailed)
        }
        if (sid == null) return QuotaInstanceDisplay.Loading
        val subject = subjects[sid] ?: return QuotaInstanceDisplay.Loading
        if (poolsOf(sid).isNotEmpty()) return QuotaInstanceDisplay.Pools(sid)
        if (isRefreshing(d.instanceId) || subject.lastRefresh == null) return QuotaInstanceDisplay.Loading
        subject.lastRefresh.error?.let { return QuotaInstanceDisplay.Failed(it) }
        return QuotaInstanceDisplay.Empty(sid)
    }

    companion object {
        fun kindOrder(k: QuotaPool.Kind): Int = when (k) {
            QuotaPool.Kind.prepaidBalance -> 0
            QuotaPool.Kind.spendCap -> 1
            QuotaPool.Kind.usageWindow -> 2
            QuotaPool.Kind.allowance -> 3
            QuotaPool.Kind.rateLimit -> 4
        }
    }
}

object QuotaStore {
    val freshnessPolicy = QuotaFreshnessPolicy()
    val thresholds = QuotaThresholds()

    private const val FOREGROUND_TICK_MS = 60_000L
    private const val POST_FOREGROUND_SETTLE_MS = 3_000L

    private const val RECENT_USE_WINDOW_MS = 30 * 60_000L

    private val _state = MutableStateFlow(QuotaState())
    val state: StateFlow<QuotaState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val singleFlight = QuotaSingleFlight<QuotaSubjectID, QuotaRefreshResult>(scope)

    private var appContext: Context? = null
    private var repository: ProviderRepository? = null
    private val lock = Any()
    private val lastViewed = HashMap<String, Long>()

    private val watchCounts = HashMap<String, Int>()

    @Volatile private var loaded = false
    @Volatile private var started = false
    private var persistJob: Job? = null
    private var foregroundJob: Job? = null


    fun start(context: Context, repository: ProviderRepository) {
        synchronized(lock) {
            if (started) return
            started = true
            appContext = context.applicationContext
            this.repository = repository
        }
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = becameActive()
                override fun onStop(owner: LifecycleOwner) = resignedActive()
            })
        }
        scope.launch(Dispatchers.IO) {
            ensureLoaded()
            AppLogger.info(TAG, "[Quota] started - subjects=${_state.value.subjects.size} pools=${_state.value.pools.size}")
            repository.awaitConfigLoaded()
            pruneDeletedInstances()
        }
    }

    private fun becameActive() {
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            delay(POST_FOREGROUND_SETTLE_MS)
            refreshDue("didBecomeActive")
            while (isActive) {
                delay(FOREGROUND_TICK_MS)
                refreshDue("tick")
            }
        }
    }

    private fun resignedActive() {
        foregroundJob?.cancel()
        foregroundJob = null
        scope.launch(Dispatchers.IO) { flushPersist() }
    }

    fun credentialsChanged() {
        if (!started) return
        _state.update { it.copy(instanceNotes = emptyMap()) }
        val now = System.currentTimeMillis()
        val recent = synchronized(lock) { lastViewed.filter { now - it.value < RECENT_USE_WINDOW_MS }.keys.toList() }
        if (recent.isEmpty()) return
        scope.launch {
            for (id in recent) refresh(id, forced = false, reason = "credentialChanged")
        }
    }


    fun noteViewed(instanceId: String) {
        synchronized(lock) { lastViewed[instanceId] = System.currentTimeMillis() }
    }

    fun beginWatching(instanceId: String, reason: String) {
        synchronized(lock) { watchCounts[instanceId] = (watchCounts[instanceId] ?: 0) + 1 }
        refreshIfDue(instanceId, reason)
    }

    fun endWatching(instanceId: String) {
        synchronized(lock) {
            val n = watchCounts[instanceId] ?: return
            if (n <= 1) watchCounts.remove(instanceId) else watchCounts[instanceId] = n - 1
        }
        noteViewed(instanceId)
    }

    fun isWatched(instanceId: String): Boolean = synchronized(lock) { watchCounts.containsKey(instanceId) }

    fun refreshIfDue(instanceId: String, reason: String) {
        if (!started) return
        noteViewed(instanceId)
        val instance = instance(instanceId) ?: return
        if (!instance.isEnabled) return
        val d = descriptor(instance)
        if (!QuotaAdapterRegistry.adapter(d).capability(d).active) return
        val st = _state.value
        val sid = st.instanceSubjects[instanceId]
        if (sid != null && !QuotaRefreshGate.decide(
                st.subjects[sid]?.lastRefresh, System.currentTimeMillis(), forced = false, policy = policy(sid.vendor),
            ).isAllowed
        ) return
        scope.launch { refresh(instanceId, forced = false, reason = reason) }
    }


    suspend fun refresh(instanceId: String, forced: Boolean, reason: String, attended: Boolean = true): QuotaRefreshResult =
        withContext(Dispatchers.Default) { refreshOffMain(instanceId, forced, reason, attended) }

    private suspend fun refreshOffMain(instanceId: String, forced: Boolean, reason: String, attended: Boolean): QuotaRefreshResult {
        ensureLoaded()
        if (repository?.configLoaded?.value != true) {
            return QuotaRefreshResult.NotConfigured(QuotaSubject.NotConfiguredReason.instanceDisabled)
        }
        val instance = instance(instanceId) ?: run {
            detach(instanceId)
            return QuotaRefreshResult.NotConfigured(QuotaSubject.NotConfiguredReason.instanceDisabled)
        }
        val d = descriptor(instance)
        val adapter = QuotaAdapterRegistry.adapter(d)
        if (!adapter.capability(d).active) return QuotaRefreshResult.Unsupported
        if (!instance.isEnabled) return note(instanceId, QuotaSubject.NotConfiguredReason.instanceDisabled)

        val subjectId: QuotaSubjectID
        val credentialSource: suspend () -> String?
        val ctx = appContext
        when {
            adapter.vendor == QuotaVendor.claudeSubscription && ctx != null -> {
                subjectId = subscriptionSubject(adapter.vendor, instanceId)
                attach(instanceId, subjectId, "OAuth")
                credentialSource = {
                    runCatching { com.openminis.app.auth.ClaudeOAuthManager(ctx, instanceId).validAccessToken() }.getOrNull()
                }
            }
            adapter.vendor == QuotaVendor.codexSubscription && ctx != null -> {
                subjectId = subscriptionSubject(adapter.vendor, instanceId)
                attach(instanceId, subjectId, "OAuth")
                credentialSource = {
                    runCatching { com.openminis.app.auth.OpenAIOAuthManager(ctx, instanceId).validAccessToken() }.getOrNull()
                }
            }
            adapter.vendor == QuotaVendor.kimiCode && d.isOAuth && ctx != null -> {
                subjectId = subscriptionSubject(adapter.vendor, instanceId)
                attach(instanceId, subjectId, "OAuth")
                credentialSource = {
                    OAuthManager.forInstance(ctx, instance)?.loadManualBearerToken()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: runCatching { com.openminis.app.auth.KimiOAuthManager(ctx, instanceId).validAccessToken() }.getOrNull()
                }
            }
            else -> {
                val credential = credential(instance)
                    ?: return note(instanceId, QuotaSubject.NotConfiguredReason.missingCredential)
                subjectId = QuotaSubjectID(adapter.vendor, QuotaAccountKey.derive(credential))
                attach(instanceId, subjectId, QuotaAccountKey.mask(credential))
                credentialSource = { credential }
            }
        }
        clearNote(instanceId)
        val policy = policy(subjectId.vendor)

        val now = System.currentTimeMillis()
        val capKey = d.capabilityKey
        val rec = _state.value.capabilities[capKey]
        if (rec != null && rec.status == QuotaCapabilityRecord.Status.failed &&
            !QuotaCapabilityGate.shouldProbe(rec, now, userRequested = forced, policy = policy)
        ) {
            AppLogger.info(TAG, "[Quota] skip $subjectId - capability $capKey failed at ${rec.probedAt} (${rec.detail ?: "-"}), next probe after 24h or a user re-check")
            return QuotaRefreshResult.CapabilityFailed
        }
        if (singleFlight.isRunning(subjectId)) {
            AppLogger.info(TAG, "[Quota] join in-flight fetch $subjectId reason=$reason")
        } else {
            val decision = QuotaRefreshGate.decide(
                _state.value.subjects[subjectId]?.lastRefresh, now, forced, policy, attended,
            )
            if (!decision.isAllowed) return QuotaRefreshResult.Blocked(decision)
        }
        return singleFlight.run(subjectId) {
            val token = credentialSource()
            if (token == null) {
                AppLogger.warning(TAG, "[Quota] $subjectId: no usable credential (OAuth signed out or refresh failed)")
                note(instanceId, QuotaSubject.NotConfiguredReason.missingCredential)
            } else {
                performFetch(adapter, d, subjectId, token, forced, reason)
            }
        }
    }

    private suspend fun performFetch(
        adapter: ProviderQuotaAdapter,
        d: QuotaInstanceDescriptor,
        subjectId: QuotaSubjectID,
        credential: String,
        forced: Boolean,
        reason: String,
    ): QuotaRefreshResult {
        val instanceIds = _state.value.subjects[subjectId]?.instanceIds ?: setOf(d.instanceId)
        _state.update { it.copy(refreshingInstanceIds = it.refreshingInstanceIds + instanceIds) }
        try {
            val started = System.currentTimeMillis()
            AppLogger.info(TAG, "[Quota] 🔄 fetch ${adapter.adapterId} subject=$subjectId forced=$forced reason=$reason")
            val relayHint = if (adapter.vendor == QuotaVendor.customRelay) {
                _state.value.capabilities[d.capabilityKey]?.takeIf {
                    it.status == QuotaCapabilityRecord.Status.supported &&
                        !QuotaCapabilityGate.shouldProbe(it, started, userRequested = false, policy = policy(QuotaVendor.customRelay))
                }?.let { RelayKind.fromAdapterId(it.adapterId) }
            } else {
                null
            }
            val endpointOverride = if (com.openminis.app.BuildConfig.DEV_TOOLS) debugEndpointOverride(d.instanceId) else null
            if (endpointOverride != null) {
                AppLogger.warning(TAG, "[Quota] ⚠️ DEBUG endpoint override for ${adapter.adapterId} instance=${d.instanceId} → $endpointOverride")
            }
            val ctx = QuotaFetchContext(
                d, subjectId, credential, QuotaOkHttpTransport(com.openminis.app.network.NetworkRuntime.providerTag(d.instanceId)), started, extraHeaders(adapter.vendor, d), relayHint,
                endpointOverride = endpointOverride,
            )
            val outcome = try {
                adapter.fetch(ctx)
            } catch (e: QuotaFetchError) {
                return recordFailure(e, adapter.adapterId, subjectId, d.capabilityKey, forced)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val fe = QuotaFetchError(QuotaError.Kind.network, e.javaClass.simpleName)
                return recordFailure(fe, adapter.adapterId, subjectId, d.capabilityKey, forced)
            }

            val now = System.currentTimeMillis()
            val policy = policy(subjectId.vendor)
            _state.update { st ->
                val pools = st.pools.toMutableMap()
                val bindings = st.bindings.toMutableList()
                QuotaMerge.ingest(outcome.snapshot, outcome.isComplete, pools, bindings)
                val subject = (st.subjects[subjectId] ?: newSubject(subjectId)).let { s ->
                    s.copy(
                        lastRefresh = QuotaRefreshGate.recordSuccess(s.lastRefresh, now, forced, policy),
                        state = QuotaSubject.State.Available,
                        plan = outcome.snapshot.plan ?: s.plan,
                    )
                }
                val snap = outcome.snapshot
                val summaryKey = "$subjectId|${snap.provenance.source.name}"
                val capKey = d.capabilityKey
                val rec = st.capabilities[capKey]
                val relayKind = outcome.relayKind
                val probedRelay = relayKind != null && ctx.relayKindHint == null
                val capabilities = if (rec?.status != QuotaCapabilityRecord.Status.supported || probedRelay ||
                    QuotaCapabilityGate.shouldProbe(rec, now, userRequested = false, policy = policy)
                ) {
                    st.capabilities + (
                        capKey to QuotaCapabilityRecord(
                            key = capKey, adapterId = relayKind?.adapterId ?: adapter.adapterId,
                            status = QuotaCapabilityRecord.Status.supported,
                            source = snap.provenance.source, confidence = snap.provenance.confidence,
                            probedAt = now,
                            detail = relayKind?.let { "${it.displayName} · ${it.path}" },
                        )
                        )
                } else {
                    st.capabilities
                }
                st.copy(
                    pools = pools,
                    bindings = bindings,
                    subjects = st.subjects + (subjectId to subject),
                    summaries = st.summaries + (
                        summaryKey to QuotaSnapshotSummary(
                            subjectId, snap.provenance.source, adapter.adapterId, snap.capturedAt,
                            snap.pools.map { it.id },
                        )
                        ),
                    capabilities = capabilities,
                )
            }
            outcome.relayKind?.takeIf { ctx.relayKindHint == null }?.let { kind ->
                AppLogger.info(TAG, "[Quota] 🔎 relay ${d.capabilityKey} detected as ${kind.displayName} (${kind.path})")
            }
            for (e in outcome.partialErrors) {
                AppLogger.warning(TAG, "[Quota] partial failure $subjectId: ${e.kind.name} ${e.detail}")
            }
            outcome.responseShape?.let { shape ->
                if (loggedResponseShapes.add(adapter.adapterId)) {
                    AppLogger.info(TAG, "[Quota] 🧩 ${adapter.adapterId} response shape: $shape")
                }
            }
            AppLogger.info(
                TAG,
                "[Quota] ✅ $subjectId pools=${outcome.snapshot.pools.size} complete=${outcome.isComplete} " +
                    "in ${"%.2f".format((now - started) / 1000.0)}s",
            )
            schedulePersist()
            return QuotaRefreshResult.Refreshed
        } finally {
            _state.update { it.copy(refreshingInstanceIds = it.refreshingInstanceIds - instanceIds) }
        }
    }

    private val loggedResponseShapes: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun debugEndpointOverride(instanceId: String): String? {
        val dir = appContext?.cacheDir ?: return null
        val file = File(dir, "quota-debug-endpoints.json")
        if (!file.isFile) return null
        val map = runCatching { QuotaHttp.json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject }
            .getOrNull() ?: return null
        val raw = (map[instanceId] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return raw
    }

    private fun extraHeaders(vendor: QuotaVendor, d: QuotaInstanceDescriptor): Map<String, String> = when (vendor) {
        QuotaVendor.claudeSubscription -> com.openminis.app.auth.ClaudeCliMimicryHeaders.ALL.toMap()
        QuotaVendor.codexSubscription -> buildMap {
            val version = com.openminis.app.provider.openai.OpenAIProvider.CODEX_CLIENT_VERSION
            put("User-Agent", "codex_cli_rs/$version (Android; arm64)")
            put("Originator", "codex_cli_rs")
            appContext?.let { c ->
                com.openminis.app.auth.OpenAIOAuthManager(c, d.instanceId).accountId?.let { put("ChatGPT-Account-Id", it) }
            }
        }
        else -> emptyMap()
    }

    private fun recordFailure(
        fe: QuotaFetchError,
        adapterId: String,
        subjectId: QuotaSubjectID,
        capKey: QuotaCapabilityKey,
        forced: Boolean,
    ): QuotaRefreshResult {
        val now = System.currentTimeMillis()
        val err = fe.persisted(now)
        var record: QuotaRefreshRecord? = null
        _state.update { st ->
            val s = st.subjects[subjectId] ?: newSubject(subjectId)
            val lastRefresh = QuotaRefreshGate.recordFailure(s.lastRefresh, err, now, forced, policy(subjectId.vendor))
            record = lastRefresh
            val subject = s.copy(
                lastRefresh = lastRefresh,
                state = if (st.poolsOf(subjectId).isEmpty()) QuotaSubject.State.Failed else QuotaSubject.State.Available,
            )
            val capabilities = if (QuotaCapabilityRules.failureDisablesEndpoint(fe.kind, subjectId.vendor)) {
                st.capabilities + (
                    capKey to QuotaCapabilityRecord(
                        key = capKey, adapterId = adapterId, status = QuotaCapabilityRecord.Status.failed,
                        probedAt = now,
                        detail = fe.httpStatus?.let { "HTTP $it · ${fe.detail}" } ?: fe.detail,
                    )
                    )
            } else {
                st.capabilities
            }
            st.copy(subjects = st.subjects + (subjectId to subject), capabilities = capabilities)
        }
        AppLogger.warning(
            TAG,
            "[Quota] ❌ $subjectId ${fe.kind.name} ${fe.detail} next=${record?.nextAllowedAt ?: "-"} " +
                "failures=${record?.consecutiveFailures ?: 0}",
        )
        schedulePersist()
        return QuotaRefreshResult.Failed(err)
    }

    private fun note(instanceId: String, reason: QuotaSubject.NotConfiguredReason): QuotaRefreshResult {
        _state.update { st ->
            if (st.instanceNotes[instanceId] == reason) st else st.copy(instanceNotes = st.instanceNotes + (instanceId to reason))
        }
        return QuotaRefreshResult.NotConfigured(reason)
    }

    private fun clearNote(instanceId: String) {
        _state.update { st ->
            if (instanceId in st.instanceNotes) st.copy(instanceNotes = st.instanceNotes - instanceId) else st
        }
    }

    private fun newSubject(id: QuotaSubjectID) = QuotaSubject(id = id)

    private fun attach(instanceId: String, subjectId: QuotaSubjectID, maskedKey: String) {
        var changed = false
        _state.update { st0 ->
            var st = st0
            val old = st.instanceSubjects[instanceId]
            if (old != null && old != subjectId) st = removeInstance(st, instanceId, old)
            val subject = st.subjects[subjectId] ?: newSubject(subjectId)
            val updated = if (instanceId !in subject.instanceIds || subject.accountLabel != maskedKey) {
                subject.copy(instanceIds = subject.instanceIds + instanceId, accountLabel = maskedKey)
            } else {
                subject
            }
            val next = st.copy(
                subjects = st.subjects + (subjectId to updated),
                instanceSubjects = st.instanceSubjects + (instanceId to subjectId),
            )
            changed = next != st0
            next
        }
        if (changed) schedulePersist()
    }

    private fun detach(instanceId: String) {
        var changed = false
        _state.update { st ->
            val old = st.instanceSubjects[instanceId] ?: return@update st
            changed = true
            removeInstance(st.copy(instanceSubjects = st.instanceSubjects - instanceId), instanceId, old)
        }
        if (changed) schedulePersist()
    }

    private fun removeInstance(st: QuotaState, instanceId: String, sid: QuotaSubjectID): QuotaState {
        val s = st.subjects[sid] ?: return st
        val remaining = s.instanceIds - instanceId
        if (remaining.isNotEmpty()) return st.copy(subjects = st.subjects + (sid to s.copy(instanceIds = remaining)))
        val gone = st.pools.values.filter { it.subjectId == sid }.map { it.id }.toSet()
        AppLogger.info(TAG, "[Quota] dropped subject $sid (no instance left)")
        return st.copy(
            subjects = st.subjects - sid,
            pools = st.pools - gone,
            bindings = st.bindings.filterNot { it.poolKey in gone },
            summaries = st.summaries.filterValues { it.subjectId != sid },
        )
    }

    private fun pruneDeletedInstances() {
        val repo = repository ?: return
        val live = repo.config.value.instances.map { it.id }.toSet()
        _state.value.instanceSubjects.keys.filter { it !in live }.forEach(::detach)
    }


    fun subscriptionSubject(vendor: QuotaVendor, instanceId: String) = QuotaSubjectID(vendor, "instance:$instanceId")

    private val loggedObservationInstances = HashSet<String>()

    fun observeSubscriptionHeaders(instanceId: String, vendor: QuotaVendor, headers: Map<String, String>) {
        if (!started) return
        ensureLoaded()
        val instance = instance(instanceId) ?: return
        if (!instance.isEnabled || QuotaVendorDetector.vendor(descriptor(instance)) != vendor) return
        val firstTime = synchronized(lock) { loggedObservationInstances.add(instanceId) }
        if (firstTime) {
            val line = headers.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
            AppLogger.info(TAG, "[Quota][SubscriptionHeaders] first observation vendor=${vendor.name} instance=${instanceId.take(8)} $line")
        }
        val subjectId = subscriptionSubject(vendor, instanceId)
        val now = System.currentTimeMillis()
        val snapshot = when (vendor) {
            QuotaVendor.claudeSubscription -> ClaudeSubscriptionQuotaAdapter.parseHeaders(headers, subjectId, now = now)
            QuotaVendor.codexSubscription -> CodexSubscriptionQuotaAdapter.parseHeaders(headers, subjectId, now = now)
            else -> null
        } ?: return
        attach(instanceId, subjectId, "OAuth")
        val policy = policy(vendor)
        _state.update { st ->
            val pools = st.pools.toMutableMap()
            val bindings = st.bindings.toMutableList()
            QuotaMerge.ingest(snapshot, authoritative = false, pools = pools, bindings = bindings)
            val s = st.subjects[subjectId] ?: newSubject(subjectId)
            val subject = s.copy(
                lastRefresh = QuotaRefreshGate.recordObservation(s.lastRefresh, now, policy),
                state = QuotaSubject.State.Available,
            )
            st.copy(
                pools = pools,
                bindings = bindings,
                subjects = st.subjects + (subjectId to subject),
                summaries = st.summaries + (
                    "$subjectId|${snapshot.provenance.source.name}" to QuotaSnapshotSummary(
                        subjectId, snapshot.provenance.source, snapshot.provenance.adapterId, now, snapshot.pools.map { it.id },
                    )
                    ),
            )
        }
        schedulePersist()
    }


    private suspend fun refreshDue(reason: String) {
        val repo = repository ?: return
        val now = System.currentTimeMillis()
        val st = _state.value
        val candidates = repo.config.value.instances.filter { instance ->
            if (!instance.isEnabled) return@filter false
            if (st.instanceNotes[instance.id] != null) return@filter false
            val d = descriptor(instance)
            if (!QuotaAdapterRegistry.adapter(d).capability(d).active) return@filter false
            val sid = st.instanceSubjects[instance.id]
            !(
                sid != null && !QuotaRefreshGate.decide(
                    st.subjects[sid]?.lastRefresh, now, forced = false, policy = policy(sid.vendor),
                    attended = isWatched(instance.id),
                ).isAllowed
                )
        }
        if (candidates.isEmpty()) return
        AppLogger.info(TAG, "[Quota] ⏱️ $reason: ${candidates.size} due (watched ${candidates.count { isWatched(it.id) }})")
        for (batch in candidates.chunked(2)) {
            coroutineScope {
                batch.map { instance ->
                    val attended = isWatched(instance.id)
                    async { refresh(instance.id, forced = false, reason = reason, attended = attended) }
                }.awaitAll()
            }
        }
    }


    private fun storeFile(): File? = appContext?.let { File(File(it.noBackupFilesDir, "quota"), "store.json") }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val file = storeFile() ?: return
            loaded = true
            if (!file.exists()) return
            val text = runCatching { file.readText() }.getOrNull() ?: return
            val decoded = QuotaStoreFile.decode(text)
            if (decoded == null) {
                AppLogger.warning(TAG, "[Quota] store.json unreadable (${text.length} bytes) - starting empty")
                return
            }
            _state.value = QuotaState(
                subjects = decoded.subjects.associateBy { it.id },
                pools = decoded.pools.associateBy { it.id },
                bindings = decoded.bindings,
                capabilities = decoded.capabilities.associateBy { it.key },
                summaries = decoded.summaries.associateBy { "${it.subjectId}|${it.source.name}" },
                instanceSubjects = decoded.instanceSubjects,
            )
        }
    }

    private fun schedulePersist() {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = scope.launch {
                delay(2_000)
                flushPersist()
            }
        }
    }

    private fun flushPersist() {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = null
        }
        if (!loaded) return
        val target = storeFile() ?: return
        val st = _state.value
        val file = QuotaStoreFile(
            subjects = st.subjects.values.toList(),
            pools = st.pools.values.toList(),
            bindings = st.bindings,
            capabilities = st.capabilities.values.toList(),
            summaries = st.summaries.values.toList(),
            instanceSubjects = st.instanceSubjects,
        )
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "store.json.tmp")
            tmp.writeText(QuotaStoreFile.encode(file))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Quota] persist failed: ${e.javaClass.simpleName}")
        }
    }


    fun policy(vendor: QuotaVendor): QuotaRefreshPolicy = QuotaRefreshPolicy.forVendor(vendor)

    private fun instance(id: String): ProviderInstance? = repository?.config?.value?.instances?.find { it.id == id }

    fun descriptor(instance: ProviderInstance): QuotaInstanceDescriptor = QuotaInstanceDescriptor(
        instanceId = instance.id,
        label = instance.label,
        providerKind = when (instance.providerType) {
            ProviderType.openAI -> QuotaInstanceDescriptor.ProviderKind.openAI
            ProviderType.anthropic -> QuotaInstanceDescriptor.ProviderKind.anthropic
            ProviderType.gemini -> QuotaInstanceDescriptor.ProviderKind.gemini
            ProviderType.antigravity -> QuotaInstanceDescriptor.ProviderKind.antigravity
            ProviderType.openRouter -> QuotaInstanceDescriptor.ProviderKind.openRouter
            ProviderType.openAIResponses -> QuotaInstanceDescriptor.ProviderKind.openAIResponses
            ProviderType.xAI -> QuotaInstanceDescriptor.ProviderKind.xAI
            ProviderType.kimiCode -> QuotaInstanceDescriptor.ProviderKind.kimiCode
            ProviderType.githubCopilot -> QuotaInstanceDescriptor.ProviderKind.githubCopilot
            ProviderType.workBuddy -> QuotaInstanceDescriptor.ProviderKind.unsupported
            ProviderType.qoder, ProviderType.minimaxCode ->
                QuotaInstanceDescriptor.ProviderKind.unsupported
            ProviderType.unsupported -> QuotaInstanceDescriptor.ProviderKind.unsupported
        },
        isOAuth = instance.credentialType == ProviderCredential.oauth,
        isEnabled = instance.isEnabled,
        customBaseURL = instance.customBaseURL?.trim()?.takeIf { it.isNotEmpty() },
        isOpenCodeChannel = isOpenCodeChannel(instance),
    )

    fun isOpenCodeChannel(instance: ProviderInstance): Boolean = when (instance.providerType) {
        ProviderType.openAI, ProviderType.openAIResponses, ProviderType.openRouter, ProviderType.xAI,
        ProviderType.kimiCode, ProviderType.githubCopilot ->
            com.openminis.app.provider.OpenCodeSessionHeader.matches(instance.customBaseURL)
        ProviderType.anthropic, ProviderType.gemini, ProviderType.antigravity, ProviderType.unsupported,
        ProviderType.workBuddy, ProviderType.qoder, ProviderType.minimaxCode -> false
    }

    private fun credential(instance: ProviderInstance): String? {
        val repo = repository ?: return null
        repo.loadApiKey(instance.id)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val ctx = appContext ?: return null
        return OAuthManager.forInstance(ctx, instance)?.loadManualBearerToken()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

object QuotaObservationSink {
    fun recordAnthropicResponse(instanceId: String?, headers: okhttp3.Headers) {
        instanceId ?: return
        var found: HashMap<String, String>? = null
        for (i in 0 until headers.size) {
            val key = headers.name(i).lowercase()
            if (!key.startsWith("anthropic-ratelimit-")) continue
            (found ?: HashMap<String, String>().also { found = it })[key] = headers.value(i)
        }
        val h = found ?: return
        if (h.keys.none { it.startsWith(ClaudeSubscriptionQuotaAdapter.HEADER_PREFIX) }) return
        observe(instanceId, QuotaVendor.claudeSubscription, h)
    }

    fun recordCodexResponse(instanceId: String?, headers: okhttp3.Headers) {
        instanceId ?: return
        var found: HashMap<String, String>? = null
        for (i in 0 until headers.size) {
            val key = headers.name(i).lowercase()
            if (!key.startsWith(CodexSubscriptionQuotaAdapter.HEADER_PREFIX)) continue
            (found ?: HashMap<String, String>().also { found = it })[key] = headers.value(i)
        }
        val h = found ?: return
        if (h.keys.none { it.endsWith("-used-percent") }) return
        observe(instanceId, QuotaVendor.codexSubscription, h)
    }

    private fun observe(instanceId: String, vendor: QuotaVendor, headers: Map<String, String>) {
        try {
            QuotaStore.observeSubscriptionHeaders(instanceId, vendor, headers)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Quota] header observation dropped vendor=${vendor.name}: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
