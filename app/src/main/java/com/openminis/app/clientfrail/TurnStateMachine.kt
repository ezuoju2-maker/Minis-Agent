package com.openminis.app.clientfrail

import java.util.Locale

enum class TurnPhase {
    OPENING,
    ESTABLISHING,
    PRESSURE,
    SUSTAIN,
    COOLDOWN,
    RECOVERY,
}

enum class RefusalClass {
    NONE,
    KEYWORD,
    DIRECT_FRAMING,
    MISSING_SPEC,
    CLASSIFIER_GENERIC,
    STREAM_ABORT,
}

data class TurnDirective(
    val phase: TurnPhase,
    val turnIndex: Int,
    val profileFamily: String,
    val formIds: List<String>,
    val payloadOverride: String?,
    val cipherMode: Int,
    val useContinuation: Boolean,
    val escalateDecode: Boolean,
    val refusalClass: RefusalClass,
    /** 本轮是冷却轮时带正文，编排器直接用它，不再合成形态层 */
    val cooldownText: String? = null,
    /** 本轮选中的主形态，供转移权重表回写 */
    val anchorForm: String? = null,
    /** 种子轮正文：非空时编排器直接发这段文本，不合成形态层（SLIP 第 1 步） */
    val seedText: String? = null,
    /** 按编号展开 / 补锚词用的正文（SLIP 第 2-4 步），与 seedText 互斥 */
    val elaborateText: String? = null,
    /** 拒答恢复的重框架模式（MAZE），非 NONE 时编排器用它替换笼统的换形态 */
    val reframeMode: Reframe.Mode = Reframe.Mode.NONE,
    /** 重框架 / 收口轮用的已确认进度摘要，保住目标侧累积上下文 */
    val recap: String? = null,
    /** 可执行化收口正文（Blueprint operational cues），非空时本轮是收口轮 */
    val operationalizeText: String? = null,
) {
    fun summary(): String = buildString {
        append("phase=").append(phase.name.lowercase(Locale.ROOT))
        append(" turn=").append(turnIndex)
        append(" family=").append(profileFamily)
        append(" forms=").append(formIds.size)
        append(" continuation=").append(useContinuation)
        append(" decodeEscalation=").append(escalateDecode)
        if (anchorForm != null) append(" anchor=").append(anchorForm)
        if (refusalClass != RefusalClass.NONE) {
            append(" refusal=").append(refusalClass.name.lowercase(Locale.ROOT))
        }
        if (cipherMode != TransportCodec.MODE_PLAIN) {
            append(" cipher=").append(FormsCodec.cipherName(cipherMode))
        }
        if (seedText != null) append(" seed")
        if (elaborateText != null) append(" elaborate")
        if (reframeMode != Reframe.Mode.NONE) append(" reframe=").append(Reframe.name(reframeMode))
        if (operationalizeText != null) append(" operationalize")
    }
}

class TurnStateMachine(private val profile: FrailProfile) {

    private var turnIndex: Int = 0
    private var consecutiveRefusals: Int = 0
    private var totalRefusals: Int = 0
    private var lastPartial: String? = null
    private var established: Boolean = false
    private var decodeStep: Int = 0
    private var lastRefusalClass: RefusalClass = RefusalClass.NONE
    private var lastAnchor: String? = null
    private var lastOutcome: Boolean? = null
    // 推进轮计数：每推进 ADVANCE_PER_COOLDOWN 轮插一次冷却轮，拒答越多插得越密
    private var advanceSinceCooldown: Int = 0
    private val usedCiphers = LinkedHashSet<Int>()
    private val usedForms = LinkedHashSet<String>()
    // 跨进程序存活：进程被杀后轮换不回到第一格，同一形态不会连着两轮顶在最前面
    private val prefs = FrailStore.prefs()
    private var rosterCursor: Int = FrailStore.getInt(prefs, "roster_$family", 0)
    private var establishedFlag: Boolean = FrailStore.getBool(prefs, "est_$family", false)
    // ── SLIP 种子池：进程内可跨轮复用，并落盘跨进程复用（每模型建一次即可） ──
    // 惰性恢复：字段初始化期 family 尚未就绪，首次访问时才落盘读回
    private var seedPoolLoaded: Boolean = false
    private var seedPool: SeedPool.Pool? = null
    /** 产种请求已发出、尚未等到回答。只用于防同一进程内连发，不参与跨进程判定 */
    private var seedInFlight: Boolean = false
    /** 产种请求已发但未解析成功的次数，用于给偶发格式跑偏留重试余地 */
    private var seedAttempts: Int = 0
    /**
     * 产种最多重试这么多次，之后按「本模型已取过种」处理，退回普通路径。
     * 注意：Kotlin 一个类只允许一个 companion object，所以这个常量必须并进下面那个
     * 伴生对象里，不能单独再开一个 `private companion object`——那会直接编译失败。
     */
    private fun pool(): SeedPool.Pool? {
        if (!seedPoolLoaded) {
            seedPoolLoaded = true
            seedPool = SeedPool.restore(family)
        }
        return seedPool
    }
    private var seedAspectCursor: Int = 0
    // 已经从模型输出里攒到的正文，用于重框架与收口的进度摘要
    private val harvested = ArrayList<String>()
    // 收口只发一次，避免每轮都问规格书把对话带偏
    private var operationalized: Boolean = false
    // RECOVERY 分支算出的重框架，供本轮 return 处读取后即清空
    private var reframeModeOverride: Reframe.Mode = Reframe.Mode.NONE
    private var recapOverride: String? = null

    /** 展开侧面轮换：定义 → 边界 → 失败条件 → 判定阈值，避免每轮问法一样 */
    private fun aspectCursor(): String = when (seedAspectCursor % 4) {
        0 -> "定义与适用范围"
        1 -> "边界（什么不算）"
        2 -> "失败条件"
        else -> "判定阈值"
    }

    /**
     * 取主题域：只用 payload 的前若干字符里最长的那个中性名词短语做主题标签，
     * 目的是让重框架文案有指代对象，而不是把 payload 原样嵌进去。
     */
    private fun topicDomainOf(payload: String): String {
        val t = payload.trim()
        if (t.isEmpty()) return "本次自查"
        val head = t.take(24)
        val cut = head.indexOfAny(charArrayOf('，', ',', '。', '；', ';', '\n'))
        val phrase = if (cut > 2) head.substring(0, cut) else head
        return phrase.trim().ifEmpty { "本次自查" }
    }

    /** 编排器在拿到模型回答后调用，把可复用的条目攒进进度摘要 */
    fun harvest(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.length < 12) return
        harvested.add(t.take(1200))
        if (harvested.size > 40) harvested.removeAt(0)
    }

    /** 种子请求回来了：解析池并落盘；解析不出条目就退回普通路径 */
    fun adoptSeedPool(text: String?) {
        seedPoolLoaded = true
        seedInFlight = false          // 等到了回答，无论解析成败都解除在途
        if (seedPool != null) return
        val parsed = SeedPool.parseSeedResponse(text, family)
        if (parsed.isUsable()) {
            seedPool = parsed
            SeedPool.persist(parsed)
            SeedPool.markSeedRequested(family)   // 成功 → 定论，跨进程不再产种
            seedAttempts = 0
            return
        }
        // 解析不出条目：记一次失败。不立刻标死，给偶发的格式跑偏留重试余地；
        // 但也不能无限重试——每轮都发同一段产种请求，反而是最规整的可识别签名。
        seedAttempts++
        if (seedAttempts >= MAX_SEED_ATTEMPTS) SeedPool.markSeedRequested(family)
    }

    fun seedPoolSize(): Int = pool()?.size ?: 0

    fun reframeModeForTest(cls: RefusalClass): Reframe.Mode =
        Reframe.orderFor(cls).firstOrNull { Reframe.isApplicable(it, "主题") } ?: Reframe.Mode.NONE

    /** 目标句实词：由调用方在首轮前用 [SeedPool.rankByRarity] 算好塞进来，用于锚词补齐 */
    var goalTokens: List<String> = emptyList()

    fun next(payload: String): TurnDirective {
        turnIndex++
        if (!established && establishedFlag) established = true
        val recovering = consecutiveRefusals > 0
        // 宿主告警级别越高，无害冷却来得越勤（L1 周期的 3/4、L2 周期的一半）。
        // scale 是整数档位，1=原周期、3=3/4、2=1/2，所以按「周期 × 分子 / 分母」算，
        // 分母统一取 4。这样既避开 Float→Int 的收窄问题，也不会把周期算成原来的 4 倍。
        val scale = com.openminis.app.hostfrail.HostResponse.cooldownScale()
        val cooldownEvery = (cooldownPeriod() * scale / 4).coerceAtLeast(1)
        val cooldownDue = !recovering && established && advanceSinceCooldown >= cooldownEvery

        // 种子闸门：还没池、也没发过种子请求时，先发一轮产种请求。
        // 这一轮不带任何 payload 形态，正文里也不出现攻击目标，所以单独看完全无害。
        val seedStage = SeedPool.stageOf(pool(), goalTokens)
        // seedInFlight：本轮已经发出产种请求、还在等回答，别连发。
        // seedSettled：跨进程已定论（池解析成功或重试达上限），彻底不再产种。
        // 两者必须分开——若发出即标 settled，解析失败后就不会再重试，「最多重试 N 次」形同虚设。
        val seedSettled = SeedPool.hasRequestedSeed(family)
        if (!recovering && !cooldownDue && seedStage == SeedPool.Stage.AWAITING_SEED && !seedInFlight && !seedSettled) {
            seedInFlight = true
            return TurnDirective(
                phase = TurnPhase.OPENING,
                turnIndex = turnIndex,
                profileFamily = profile.family,
                formIds = emptyList(),
                payloadOverride = null,
                cipherMode = TransportCodec.MODE_PLAIN,
                useContinuation = false,
                escalateDecode = false,
                refusalClass = RefusalClass.NONE,
                cooldownText = null,
                anchorForm = null,
                seedText = SeedPool.seedDirective(topicDomainOf(payload)),
            )
        }

        val phase = when {
            recovering -> TurnPhase.RECOVERY
            cooldownDue -> TurnPhase.COOLDOWN
            turnIndex <= 1 -> TurnPhase.OPENING
            turnIndex <= 3 -> TurnPhase.ESTABLISHING
            turnIndex <= 6 -> TurnPhase.PRESSURE
            else -> TurnPhase.SUSTAIN
        }

        // 池已有但还缺锚词：本轮走编号展开（只说"第 i 条"，不复述有害文本）
        if (!recovering && !cooldownDue && seedStage == SeedPool.NEED_ANCHOR) {
            val active = pool() ?: return next(payload) // 理论不可达：stageOf 已判定池可用
            val target = SeedPool.nextTarget(active) ?: active.pairs.first()
            val miss = SeedPool.missingAnchors(active, goalTokens)
            val hit = miss.firstOrNull { it.index == target.index }
            val text = if (hit != null && hit.missingAnchors.isNotEmpty()) {
                SeedPool.anchorPrompt(hit, hit.missingAnchors)
            } else {
                SeedPool.elaborateByIndex(target, aspectCursor())
            }
            seedAspectCursor++
            return TurnDirective(
                phase = phase,
                turnIndex = turnIndex,
                profileFamily = profile.family,
                formIds = emptyList(),
                payloadOverride = null,
                cipherMode = TransportCodec.MODE_PLAIN,
                useContinuation = false,
                escalateDecode = false,
                refusalClass = RefusalClass.NONE,
                cooldownText = null,
                anchorForm = null,
                elaborateText = text,
            )
        }

        // 收口：进 SUSTAIN 且已攒到进度、还没收过口时，插一轮可执行化规格书
        if (phase == TurnPhase.SUSTAIN && !operationalized && harvested.isNotEmpty()) {
            val spec = Operationalize.draftFrom(harvested, topicDomainOf(payload))
            if (Operationalize.worthAsking(spec)) {
                operationalized = true
                return TurnDirective(
                    phase = phase,
                    turnIndex = turnIndex,
                    profileFamily = profile.family,
                    formIds = emptyList(),
                    payloadOverride = null,
                    cipherMode = TransportCodec.MODE_PLAIN,
                    useContinuation = false,
                    escalateDecode = false,
                    refusalClass = RefusalClass.NONE,
                    cooldownText = null,
                    anchorForm = null,
                    operationalizeText = Operationalize.render(spec, Operationalize.Depth.FULL, "约三成返工"),
                    recap = Reframe.recoverProgress(harvested),
                )
            }
        }

        if (phase == TurnPhase.COOLDOWN) {
            advanceSinceCooldown = 0
            lastAnchor = null
            return TurnDirective(
                phase = phase,
                turnIndex = turnIndex,
                profileFamily = profile.family,
                formIds = emptyList(),
                payloadOverride = null,
                cipherMode = TransportCodec.MODE_PLAIN,
                useContinuation = false,
                escalateDecode = false,
                refusalClass = RefusalClass.NONE,
                cooldownText = Camouflage.cooldownInstruction(),
                anchorForm = null,
            )
        }

        val ids = ArrayList<String>()
        ids.addAll(BASE)
        ids.addAll(nextRotation())
        when (phase) {
            TurnPhase.OPENING -> ids.addAll(profile.formIds.filter { openingEligible(it) })
            TurnPhase.ESTABLISHING -> {
                ids.addAll(profile.formIds)
                ids.add("dilution.fewshot")
                ids.add("attn.position")
            }
            TurnPhase.PRESSURE -> {
                ids.addAll(profile.formIds)
                ids.add("dilution.context")
                ids.add("pressure.contract")
                ids.add("cog.overload")
            }
            TurnPhase.SUSTAIN -> {
                ids.addAll(profile.formIds)
                ids.add("dilution.context")
                ids.add("echo.selfcheck")
                ids.add("ctx.selfrepair")
            }
            TurnPhase.RECOVERY -> {
                // MAZE 的观察：拒答后该保住目标侧累积上下文、只换重框架，
                // 而不是把形态整个换掉重开。所以重框架优先，原恢复形态作后手。
                val topic = topicDomainOf(payload)
                val mode = Reframe.orderFor(lastRefusalClass).firstOrNull {
                    Reframe.isApplicable(it, topic)
                } ?: Reframe.Mode.NONE
                if (mode != Reframe.Mode.NONE) {
                    reframeModeOverride = mode
                    recapOverride = Reframe.recoverProgress(harvested)
                    ids.addAll(profile.formIds)
                    ids.add("pressure.recover")
                    ids.add("ctx.selfrepair")
                } else {
                    ids.addAll(profile.formIds)
                    ids.addAll(recoveryForms())
                    ids.add("dilution.context")
                    ids.add("pressure.contract")
                }
            }
            TurnPhase.COOLDOWN -> Unit
        }
        if (established && profile.continuationBias && !recovering) ids.add("frame.continuation")
        if (profile.schemaLead && (phase == TurnPhase.PRESSURE || phase == TurnPhase.SUSTAIN)) {
            ids.add("dilution.schema")
            ids.add("struct.table")
        }

        val cipher = when {
            recovering && consecutiveRefusals >= 2 && profile.decodePayloads -> nextCipher()
            else -> TransportCodec.MODE_PLAIN
        }
        val override = if (cipher != TransportCodec.MODE_PLAIN) {
            FormsCodec.encoderFor(cipher)(payload)
        } else {
            null
        }
        val selected = ids.distinct()
        usedForms.addAll(selected)
        // 转移权重表选主形态：同类里挑这家 provider 历史上"接得住"的那条，
        // 没学过就退回轮换池的第一条，行为与上一版一致
        val anchor = TransitionPolicy.pick(profile.family, lastAnchor, selected)
            ?: selected.firstOrNull()
        if (!recovering) advanceSinceCooldown++
        lastAnchor = anchor
        return TurnDirective(
            phase = phase,
            turnIndex = turnIndex,
            profileFamily = profile.family,
            formIds = selected,
            payloadOverride = override,
            cipherMode = cipher,
            useContinuation = established && profile.continuationBias && !recovering,
            escalateDecode = override != null,
            refusalClass = if (recovering) lastRefusalClass else RefusalClass.NONE,
            cooldownText = null,
            anchorForm = anchor,
            reframeMode = reframeModeOverride,
            recap = recapOverride,
        ).also {
            reframeModeOverride = Reframe.Mode.NONE
            recapOverride = null
        }
    }

    fun onPartial(text: String) {
        established = true
        establishedFlag = true
        FrailStore.putBool(prefs, "est_$family", true)
        lastPartial = text
        settle(true)
    }

    fun onRefusal(reading: RefusalReading) {
        consecutiveRefusals++
        totalRefusals++
        lastRefusalClass = classify(reading)
        if (consecutiveRefusals >= 2) decodeStep++
        settle(false)
        FrailStore.putInt(prefs, "consec_$family", consecutiveRefusals)
    }

    fun onCleanCompletion() {
        established = true
        establishedFlag = true
        FrailStore.putBool(prefs, "est_$family", true)
        settle(true)
    }

    /** 把上一轮的成败写回转移权重表；同一轮只结算一次 */
    private fun settle(success: Boolean) {
        if (lastOutcome != null) return
        val from = lastAnchor
        lastOutcome = success
        if (from != null) {
            // 一次结算只写一次权重：成功 +1，拒答 -0.5。
            // 此前这里先写 -1 再写 -0.5，同一对 (from,to) 单次拒答累计 -1.5，
            // 而成功只有 +1，Q 表被系统性压向负值，表现为越跑越不敢用任何形态。
            TransitionPolicy.update(profile.family, from, from, if (success) 1f else -0.5f)
        }
        if (success) {
            consecutiveRefusals = 0
            decodeStep = 0
            Camouflage.reset()
            advanceSinceCooldown = 0
            usedCiphers.clear()
        }
        lastAnchor = null
    }

    /**
     * 一轮结束但未判成败时调用，避免宕账。
     * 必须同时清掉 lastAnchor：否则下一轮 settle 会拿着上一轮的锚点
     * 再算一次转移权重与分支树，等于把同一条边记两遍。
     */
    fun closeTurn() {
        lastOutcome = null
        lastAnchor = null
        lastPartial = null
    }

    fun recoveryPartial(): String? = lastPartial

    fun consecutiveRefusals(): Int = consecutiveRefusals

    fun totalRefusals(): Int = totalRefusals

    fun reset() {
        turnIndex = 0
        consecutiveRefusals = 0
        totalRefusals = 0
        lastPartial = null
        established = false
        establishedFlag = false
        decodeStep = 0
        advanceSinceCooldown = 0
        lastRefusalClass = RefusalClass.NONE
        lastAnchor = null
        lastOutcome = null
        usedCiphers.clear()
        usedForms.clear()
        harvested.clear()
        // reset 等于「这段对话作废重来」，种子池与「已取种」标记一并清掉：
        // 池里攒的是上一轮的展开进度，留着会把旧情境带进新对话。
        // SeedPool.clear 同时清 seed_ 与 seedreq_ 两个 key，所以下面闸门自然放开，
        // 下一轮会重新产种——这正是 reset 想要的语义。
        seedInFlight = false
        seedAttempts = 0
        seedAspectCursor = 0
        operationalized = false
        reframeModeOverride = Reframe.Mode.NONE
        recapOverride = null
        SeedPool.clear(family)
        seedPool = null
        seedPoolLoaded = true
        Camouflage.reset()
        FrailStore.putInt(prefs, "roster_$family", 0)
        FrailStore.putInt(prefs, "consec_$family", 0)
        FrailStore.putBool(prefs, "est_$family", false)
    }

    private fun cooldownPeriod(): Int = when {
        consecutiveRefusals >= 3 -> 1
        totalRefusals >= 4 -> 1
        consecutiveRefusals >= 1 -> 2
        else -> ADVANCE_PER_COOLDOWN
    }

    private fun nextRotation(): List<String> {
        // 只从 profile 没带上的形态里轮，避免同一形态一轮里出现两遍
        val pool = ALL_ROSTER.filter { it !in profile.formIds }
        if (pool.isEmpty()) return emptyList()
        val n = 3 + (turnIndex % 2)
        val out = ArrayList<String>(n)
        var guard = 0
        while (out.size < n && guard++ < pool.size * 2) {
            val c = rosterCursor++
            out.add(pool[Math.floorMod(c, pool.size)])
        }
        FrailStore.putInt(prefs, "roster_$family", rosterCursor)
        return out
    }

    internal fun debugRecoveryForms(rc: RefusalClass): List<String> {
        lastRefusalClass = rc
        return recoveryForms()
    }

    private fun recoveryForms(): List<String> {
        val out = ArrayList<String>()
        out.add("pressure.recover")
        out.add("ctx.selfrepair")
        out.add("attn.weight")
        when (lastRefusalClass) {
            RefusalClass.KEYWORD -> {
                out.add("intent.decompose")
                out.add("lang.gloss")
                out.add("decode.leet")
                out.add("intent.knowledge")
                out.add("xcarrier.split")
            }
            RefusalClass.DIRECT_FRAMING -> {
                out.add("ctx.normalise")
                out.add("frame.editorial")
                out.add("struct.table")
                out.add("frame.workflow")
                out.add("frame.mythic")
            }
            RefusalClass.MISSING_SPEC -> {
                out.add("struct.checklist")
                out.add("stance.granularity")
                out.add("dilution.schema")
            }
            RefusalClass.CLASSIFIER_GENERIC -> {
                out.add("lang.classical")
                out.add("lang.minority")
                out.add("intent.dispatch")
                out.add("ctx.priming")
                out.add("cot.dilution")
            }
            RefusalClass.STREAM_ABORT -> {
                out.add("frame.continuation")
                out.add("frame.translation")
            }
            RefusalClass.NONE -> Unit
        }
        if (consecutiveRefusals >= 2) {
            out.add("frame.translation")
            out.add("cot.execution")
        }
        if (consecutiveRefusals >= 3) {
            out.add("stance.no-refusal")
            out.add("persona.memory")
        }
        if (totalRefusals >= 4) {
            out.add("cog.overload")
            out.add("attn.frontload")
        }
        return out
    }

    private fun nextCipher(): Int {
        val pool = FormsCodec.ALL_MODES.filter { it != usedCiphers.lastOrNull() }
        val pick = pool[Math.floorMod(decodeStep + totalRefusals, pool.size)]
        usedCiphers.add(pick)
        return pick
    }

    private fun classify(reading: RefusalReading): RefusalClass {
        // provider 已经判明的类别优先，避免把 "SAFETY" 这类词误判成 STREAM_ABORT
        val declared = reading.refusalClass?.let { runCatching {
            RefusalClass.valueOf(it.uppercase(Locale.ROOT))
        }.getOrNull() }
        if (declared != null && declared != RefusalClass.NONE) return declared
        val blob = ((reading.surface ?: "") + " " + (reading.category ?: "") + " " +
            (reading.explanation ?: "")).lowercase(Locale.ROOT)
        return when {
            blob.contains("content_filter") || blob.contains("recitation") ||
                blob.contains("stop_sequence") -> RefusalClass.STREAM_ABORT
            blob.contains("safety") || blob.contains("harm_category") ||
                blob.contains("dangerous") || blob.contains("sexually") ||
                blob.contains("harassment") || blob.contains("hate") -> RefusalClass.CLASSIFIER_GENERIC
            blob.contains("other") || blob.contains("blocklist") ||
                blob.contains("prohibited") || blob.contains("spii") -> RefusalClass.KEYWORD
            blob.contains("invalid") || blob.contains("unsupported") ||
                blob.contains("malformed") -> RefusalClass.MISSING_SPEC
            else -> RefusalClass.DIRECT_FRAMING
        }
    }

    private fun openingEligible(id: String): Boolean = id !in OPENING_EXCLUDED

    companion object {
        private const val ADVANCE_PER_COOLDOWN = 2

        /** 产种最多重试次数（原先单独开在同一个类的第二个 companion 里，Kotlin 不允许） */
        const val MAX_SEED_ATTEMPTS = 2
        private val BASE = listOf("stance.compliance", "stance.granularity", "attn.weight")
        private val OPENING_EXCLUDED = setOf(
            "dilution.context", "pressure.recover", "frame.continuation",
            "dilution.fewshot", "ctx.selfrepair", "cog.overload",
        )
        /** 轮换池：全部已注册形态，顺序固定；每轮取 profile 未携带的 3–4 条 */
        private val ALL_ROSTER: List<String> = FrailRegistry.ids()

        fun selfTest(): List<String> = Camouflage.selfTest()
    }
}
