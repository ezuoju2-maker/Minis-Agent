package com.openminis.app.clientfrail

import android.content.Context
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object FrailOrchestrator {

    private const val TAG = "Frail"
    private const val MAX_LAYER_CHARS = 9000
    private const val HEADER_KEEP = 220
    private const val TAIL = "\n=== END OPERATING LAYER ===\n"

    private val ready = AtomicBoolean(false)
    private val selfTestPassed = AtomicBoolean(false)
    private val layersBuilt = AtomicInteger(0)
    private val profiles = ConcurrentHashMap<String, FrailProfile>()
    private val machines = ConcurrentHashMap<String, TurnStateMachine>()
    private val lastDirective = ConcurrentHashMap<String, TurnDirective>()

    @Volatile
    private var localeTag: String = "en-US"

    @Volatile
    private var hostSummary: String = ""

    @JvmStatic
    fun install(context: Context) {
        if (ready.getAndSet(true)) return
        val app = context.applicationContext
        localeTag = runCatching {
            app.resources.configuration.locales[0].toLanguageTag()
        }.getOrDefault("en-US")
        val failures = ArrayList<String>()
        failures += selfTest()
        if (failures.isEmpty()) {
            selfTestPassed.set(true)
        } else {
            Log.w(TAG, "self-test findings: " + failures.joinToString("; "))
        }
        layersBuilt.set(FrailRegistry.size())
        Log.i(
            TAG,
            "ready forms=${FrailRegistry.all().size} families=${ProfileResolver.families().size} " +
                "locale=$localeTag selfTest=${selfTestPassed.get()}",
        )
    }

    fun isReady(): Boolean = ready.get()

    fun diagnostics(): String = buildString {
        append("ready=").append(ready.get())
        append(" selfTest=").append(selfTestPassed.get())
        append(" forms=").append(layersBuilt.get())
        append(" families=").append(ProfileResolver.families().size)
        append(" machines=").append(machines.size).append(" seeds=").append(machines.values.sumOf { it.seedPoolSize() })
        append(" locale=").append(localeTag)
        if (hostSummary.isNotBlank()) append(" host={").append(hostSummary).append('}')
    }

    fun bindHostSummary(summary: String) {
        hostSummary = summary.trim()
    }

    fun profileFor(modelId: String?, providerName: String?): FrailProfile {
        val key = (modelId ?: "?") + "|" + (providerName ?: "?")
        return profiles.getOrPut(key) { ProfileResolver.resolve(modelId, providerName) }
    }

    fun machineFor(sessionId: String, modelId: String?, providerName: String?): TurnStateMachine {
        val key = sessionId.ifEmpty { "anonymous" }
        val existing = machines[key]
        if (existing != null) return existing
        // 观测回调（observePartial/observeRefusal）只带 sessionId，此时若机器还没建，
        // 绝不能用 generic 兜底建一台：那会把别的模型族的权重与拒答计数写进错的表。
        // 优先复用本 session 上一条 directive 记下的 family，其次取唯一已解析的 profile，
        // 都拿不到才退到 generic，并打点提示这是未绑定的观测。
        val fallbackProfile = lastDirective[key]?.profileFamily?.let { fam ->
            profiles.values.firstOrNull { it.family == fam }
        } ?: profiles.values.singleOrNull()
        if (fallbackProfile == null && (modelId == null && providerName == null)) {
            Log.w(TAG, "observe before bind session=${key.take(8)}，暂用 generic profile")
        }
        return machines.getOrPut(key) {
            TurnStateMachine(fallbackProfile ?: profileFor(modelId, providerName))
        }
    }

    /**
     * 一轮结束但既没判拒答也没干净收尾时（流被取消、超时、网络断）调用，
     * 把这一轮宕掉的账清掉，避免下一轮重复结算同一条边。
     */
    fun closeTurn(sessionId: String) {
        val key = sessionId.ifEmpty { "anonymous" }
        machines[key]?.closeTurn()
    }

    fun dropSession(sessionId: String) {
        machines.remove(sessionId.ifEmpty { "anonymous" })
        lastDirective.remove(sessionId.ifEmpty { "anonymous" })
    }

    fun layerFor(
        sessionId: String,
        modelId: String?,
        providerName: String?,
        userPayload: String,
    ): String {
        if (!ready.get()) return ""
        // 宿主层已确认被注入/重打包时停发破甲层，本轮按普通请求走
        if (com.openminis.app.hostfrail.HostResponse.suppressFrailLayer()) return ""
        val machine = machineFor(sessionId, modelId, providerName)
        val directive = machine.next(userPayload)
        lastDirective[sessionId.ifEmpty { "anonymous" }] = directive
        // 冷却轮：整轮换成与目标无关的寻常话题，不合成形态层
        val cooldown = directive.cooldownText
        if (!cooldown.isNullOrBlank()) return cooldown
        // 种子轮（SLIP 第 1 步）：整轮是"安全评测集标注"请求，不含攻击目标
        val seed = directive.seedText
        if (!seed.isNullOrBlank()) return seed
        // 编号展开 / 锚词补齐轮（SLIP 第 2-4 步）：只说"第 i 条"，不复述有害文本
        val elaborate = directive.elaborateText
        if (!elaborate.isNullOrBlank()) return elaborate
        // 可执行化收口轮（Blueprint operational cues）：问的是规格书，不是内容
        val op = directive.operationalizeText
        if (!op.isNullOrBlank()) return buildString {
            append(op)
            val recap = directive.recap
            if (!recap.isNullOrBlank()) {
                append("\n\n已确认的部分（接着往下，不要从头重来）：\n").append(recap)
            }
        }
        // 重框架轮（MAZE）：把重框架正文前置在形态层之前，保住累积上下文
        val reframe = if (directive.reframeMode != Reframe.Mode.NONE) {
            Reframe.render(directive.reframeMode, topicOf(userPayload), directive.recap)
        } else {
            null
        }
        val ctx = FrailContext(
            localeTag = localeTag,
            modelId = modelId ?: "",
            providerName = providerName ?: "",
            turnIndex = directive.turnIndex,
            payload = userPayload,
            priorPartial = machine.recoveryPartial() ?: "",
        )
        val composed = buildString {
            if (!reframe.isNullOrBlank()) {
                append(reframe).append("\n\n")
            }
            // 宿主告警分级：级别越高，放行的形态族越窄（只留读起来像正常对话的那些）
            val forms = directive.forms().filter {
                com.openminis.app.hostfrail.HostResponse.accepts(it)
            }
            append(FrailRegistry.compose(forms, ctx))
        }
        val budget = minOf(MAX_LAYER_CHARS, profile.headerBudgetChars)
        if (composed.length <= budget) return composed
        // 截断时保住头尾框架，只从中间的形态体里削，避免留下半截 XML/标记
        val head = composed.substring(0, HEADER_KEEP)
        val tail = TAIL
        val room = budget - head.length - tail.length
        if (room <= 0) return head + tail
        val body = composed.substring(HEADER_KEEP, composed.length - TAIL.length)
        return head + body.substring(0, room) + tail
    }

    fun directiveFor(sessionId: String): TurnDirective? =
        lastDirective[sessionId.ifEmpty { "anonymous" }]

    fun transformPayload(sessionId: String, payload: String): String {
        val d = directiveFor(sessionId) ?: return payload
        return d.payloadOverride ?: payload
    }

    fun observePartial(sessionId: String, text: String) {
        if (text.trim().length < 24) return
        machineFor(sessionId, null, null).onPartial(text)
    }

    /**
     * 种子请求的回答回喂（SLIP 第 1 步的收口）。调用方把这一轮的模型输出原样传进来，
     * 状态机解析出配对池；解析不出就保持未取种，下一轮退回普通路径，不会卡死。
     */
    fun observeSeed(sessionId: String, text: String) {
        if (text.trim().length < 24) return
        machineFor(sessionId, null, null).adoptSeedPool(text)
    }

    /** 种子池当前条数，0 表示还没取到；供调用方决定是否要发 seed 轮 */
    fun seedPoolSize(sessionId: String): Int =
        machines[sessionId.ifEmpty { "anonymous" }]?.seedPoolSize() ?: 0

    /**
     * 把本轮模型输出攒进进度摘要。这是 MAZE 那 32.9% / 56.1% 的代价所在——
     * 不攒，拒答后的重框架就没有可引用的已确认内容，退化成普通换形态。
     * 建议在每轮拿到完整回答后调用（流式结束或干净收尾时各一次即可）。
     */
    fun harvest(sessionId: String, text: String) {
        if (text.trim().length < 12) return
        machineFor(sessionId, null, null).harvest(text)
    }

    fun selfTest(): List<String> =
        TransportCodec.selfTest() + FormsCodec.selfTest() + FrailRegistry.selfTest() +
            ProfileResolver.selfTest() + TurnStateMachine.selfTest() +
            TransitionPolicy.selfTest() + SeedPool.selfTest() + Reframe.selfTest() +
            com.openminis.app.hostfrail.HostResponse.selfTest() +
            Operationalize.selfTest()

    fun observeRefusal(sessionId: String, reading: RefusalReading) {
        if (!reading.refused) return
        val d = directiveFor(sessionId)
        val normalized = reading.refusalClass?.let { reading } ?: reading.copy(
            refusalClass = ProviderRule.classify(
                providerFamily = d?.profileFamily,
                surface = reading.surface,
                category = reading.category,
                explanation = reading.explanation,
            ),
        )
        Log.w(TAG, "refusal session=${sessionId.take(8)} " + RefusalSensor.describe(normalized) +
            " consecutive=" + (d?.let { machineFor(sessionId, null, null).consecutiveRefusals() } ?: "?"))
        machineFor(sessionId, null, null).onRefusal(normalized)
    }

    fun observeCleanCompletion(sessionId: String) {
        machineFor(sessionId, null, null).onCleanCompletion()
    }

    fun sessionSummary(sessionId: String): String {
        val m = machines[sessionId.ifEmpty { "anonymous" }] ?: return "session=idle"
        val d = lastDirective[sessionId.ifEmpty { "anonymous" }]
        return buildString {
            append("session=").append(sessionId.take(8))
            if (d != null) append(' ').append(d.summary())
            else append(" phase=idle")
        }
    }

    fun resetSession(sessionId: String) {
        val m = machines[sessionId.ifEmpty { "anonymous" }]
        if (m != null) TransitionPolicy.reset(m.profileFamily)
        m?.reset()
        lastDirective.remove(sessionId.ifEmpty { "anonymous" })
    }

    fun trim(s: String?, maxChars: Int): String {
        if (s == null) return ""
        return if (s.length <= maxChars) s else s.substring(0, maxChars)
    }

    /** 重框架用的主题标签：只取 payload 开头的中性短语，不把整句 payload 嵌进文案 */
    private fun topicOf(payload: String): String {
        val t = payload.trim()
        if (t.isEmpty()) return "本次自查"
        val head = t.take(24)
        val cut = head.indexOfAny(charArrayOf('，', ',', '。', '；', ';', '\n'))
        val phrase = if (cut > 2) head.substring(0, cut) else head
        return phrase.trim().ifEmpty { "本次自查" }
    }

    fun describeProfile(modelId: String?, providerName: String?): String =
        ProfileResolver.describe(profileFor(modelId, providerName))

    fun localeTag(): String = localeTag

    fun transportModeFor(family: String): Int = when (family.lowercase(Locale.ROOT)) {
        "anthropic" -> TransportCodec.MODE_BASE64
        "gemini" -> TransportCodec.MODE_HOMOGLYPH
        else -> TransportCodec.MODE_BASE64
    }
}
