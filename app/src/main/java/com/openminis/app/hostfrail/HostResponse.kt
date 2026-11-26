package com.openminis.app.hostfrail

import android.content.Context
import com.openminis.app.clientfrail.FrailForm
import com.openminis.app.clientfrail.FrailStore

/**
 * 宿主告警分级响应。
 *
 * 背景：此前宿主层只做三件事——采集快照、写完整性账本、把摘要塞进系统提示。
 * 检测到 Frida / Xposed 这类注入环境后，客户端破甲层照旧用全量形态往下打，
 * 等于把自己的完整战术树暴露在一个已经确认被观测的环境里。这一层把检测结果变成行为差异。
 *
 * 分级（沿用"只有不可逆的事才升级"的思路，避免一有告警就全面降级把功能打没）：
 *  - L0 干净：全量形态，冷却周期按 profile 默认值。
 *  - L1 有痕（模拟器面、覆盖层、无障碍截取、VPN/代理、重打包痕迹）：禁掉特征最明显的
 *    解码类与编码类形态，冷却周期缩到 75%。
 *  - L2 已注入（Frida / Xposed / Substrate / zygisk / Magisk / ptrace 命中）：只留
 *    立场、框架、叙事、稀释、施压这类"读起来像正常对话"的族，冷却缩到 50%，
 *    并优先走 native 通道——此时 Java 层调用本身已经在被看。
 *  - L3 完整性失配（签名或资源清单对不上）：停发破甲层，只走普通请求路径。
 *
 * 只做"收敛 + 降级"，不做拦截弹窗——这是使用者明确的取向；告警本身仍只进账本与日志，不外报。
 */
object HostResponse {

    enum class Level(val value: Int) {
        CLEAN(0), SUSPICIOUS(1), INJECTED(2), INTEGRITY_FAIL(3);

        companion object {
            fun of(v: Int): Level = values().firstOrNull { it.value == v } ?: CLEAN
        }
    }

    private const val KEY = "host_response_level"
    private const val KEY_SINCE = "host_response_since"

    @Volatile
    private var level: Level = Level.CLEAN

    @Volatile
    private var sinceMs: Long = 0L

    /**
     * 完整性失配标志，由 [FrailHost] 在 [IntegrityLedger.verify] 之后写入。
     * 不在这里直接调 verify：那需要 versionCode 与源指纹，属于引导期才知道的量，
     * 让引导方判定后塞进来，本层只负责据此收敛行为。
     */
    @Volatile
    private var integrityDrifted: Boolean = false

    /** L2 下仍然允许出现的形态族：都是"读起来像正常提问"的，不带编码/结构特征。 */
    private val INJECTED_SAFE = setOf(
        FrailForm.Family.STANCE,
        FrailForm.Family.FRAME,
        FrailForm.Family.NARRATIVE,
        FrailForm.Family.DILUTION,
        FrailForm.Family.PRESSURE,
    )

    /** L1 下额外禁掉的两族：特征最明显，静态扫描一眼可辨。 */
    private val SUSPICIOUS_BANNED = setOf(
        FrailForm.Family.DECODE,
        FrailForm.Family.ATTN,
    )

    /** 该形态在当前告警级别下是否允许下发。null 表示不过滤。 */
    fun accepts(form: FrailForm?): Boolean = when {
        form == null -> true
        level == Level.CLEAN -> true
        level == Level.INTEGRITY_FAIL -> false
        level == Level.INJECTED -> form.family in INJECTED_SAFE
        else -> form.family !in SUSPICIOUS_BANNED
    }

    /** 冷却周期倍率：环境越脏，无害冷却来得越勤，用来压会话级漂移检测的命中率。 */
    /** 冷却间隔的整数档位：1 = 原周期，3 = 周期的 3/4，2 = 周期的一半 */
    fun cooldownScale(): Int = when (level) {
        Level.CLEAN -> 1
        Level.SUSPICIOUS -> 3
        Level.INJECTED -> 2
        Level.INTEGRITY_FAIL -> 1
    }

    /** L2 及以上优先走 native 通道，绕开被观测的 Java 调用栈。 */
    fun preferNativePath(): Boolean = level.ordinal >= Level.INJECTED.ordinal

    /** L3 停发破甲层，本轮当普通请求处理。 */
    fun suppressFrailLayer(): Boolean = level == Level.INTEGRITY_FAIL

    fun current(): Level = level

    fun heldForMs(nowMs: Long = System.currentTimeMillis()): Long =
        if (sinceMs == 0L) 0L else nowMs - sinceMs

    /**
     * 由 [FrailSentinel] 在每次快照后调用。只升不降是刻意的：一次确认注入之后，
     * 同一次进程生命周期里不因为后面几次采样变干净就放宽——探针被绕过比探针真的消失更常见。
     * 进程重启后从账本恢复，所以不会被永久锁死。
     */
    @JvmStatic
    @Synchronized
    fun evaluate(context: Context, snap: EnvironmentSnapshot): Level {
        val next = classify(snap)
        if (next.ordinal <= level.ordinal) return level
        level = next
        sinceMs = System.currentTimeMillis()
        persist(context)
        android.util.Log.w(
            "HostResponse",
            "escalated to ${level.name} score=${snap.exposureScore} native=${NativeProbe.describe()}",
        )
        return level
    }

    private fun classify(snap: EnvironmentSnapshot): Level {
        // 完整性失配走账本判定（签名 / 资源清单漂移），快照里没有这一项
        if (integrityDrifted) return Level.INTEGRITY_FAIL
        val blob = buildString {
            append(snap.nativeBridges.joinToString(","))
            append('|')
            append(snap.javaBridges.joinToString(","))
            append('|')
            append(snap.hookImplications.joinToString(","))
        }
        val injected = listOf("frida", "xposed", "substrate", "riru", "zygisk", "magisk", "kernelsu", "apatch")
        if (injected.any { blob.contains(it, ignoreCase = true) }) return Level.INJECTED
        // ptrace / 调试器附着也算已注入：调用栈正在被单步
        if (snap.debugSurface) return Level.INJECTED
        if (snap.repackSurface || snap.overlayWindows > 0 || snap.interceptingAccessibility ||
            snap.emulatorSurface || snap.proxySurface
        ) {
            return Level.SUSPICIOUS
        }
        return Level.CLEAN
    }

    private fun persist(context: Context) {
        val p = FrailStore.prefs(context) ?: return
        FrailStore.putString(p, KEY, level.name)
        FrailStore.putString(p, KEY_SINCE, sinceMs.toString())
    }

    /** 冷启动恢复：账本记着上次已升到某级，这次进程里没重新采样也先按该级别收敛。 */
    @JvmStatic
    @Synchronized
    fun restore(context: Context) {
        if (sinceMs != 0L) return
        val p = FrailStore.prefs(context) ?: return
        val restored = runCatching { Level.valueOf(FrailStore.getString(p, KEY, "CLEAN") ?: "CLEAN") }
            .getOrDefault(Level.CLEAN)
        level = restored
        sinceMs = runCatching { FrailStore.getString(p, KEY_SINCE, "0")?.toLongOrNull() ?: 0L }
            .getOrDefault(0L)
    }

    /** 由引导方在完整性校验后调用；一旦置位即等价于 L3。传 false 不会降级已有级别。 */
    @JvmStatic
    fun markIntegrityDrift(drifted: Boolean) {
        integrityDrifted = drifted
    }

    @JvmStatic
    fun clear(context: Context) {
        level = Level.CLEAN
        sinceMs = 0L
        integrityDrifted = false
        val p = FrailStore.prefs(context)
        FrailStore.remove(p, KEY)
        FrailStore.remove(p, KEY_SINCE)
    }

    fun describe(): String =
        "${level.name} holdForMs=$heldForMs() cooldown=${cooldownScale()} native=${preferNativePath()}"

    // ────────────────────────── 自测（不触网、不依赖 Android 运行时） ──────────────────────────

    /** 纯函数镜像，供自测验证分级边界而不污染全局状态。 */
    private fun classifyBlob(blob: String, debug: Boolean, dirty: Boolean): Level {
        val injected = listOf("frida", "xposed", "substrate", "riru", "zygisk", "magisk", "kernelsu", "apatch")
        if (injected.any { blob.contains(it, ignoreCase = true) }) return Level.INJECTED
        if (debug) return Level.INJECTED
        if (dirty) return Level.SUSPICIOUS
        return Level.CLEAN
    }

    private fun acceptsAt(l: Level, family: FrailForm.Family): Boolean = when (l) {
        Level.CLEAN -> true
        Level.INTEGRITY_FAIL -> false
        Level.INJECTED -> family in INJECTED_SAFE
        else -> family !in SUSPICIOUS_BANNED
    }

    /**
     * 冷却周期的倍数分母：整数百分比。1=100%、3=75%、2=50%。
     * 原本写成 0.75f / 0.5f，但函数返回 Int——Kotlin 里 Float 不会隐式收窄成 Int，
     * 这是编译错误。改用整数档位，语义不变（75% 就是 3/4、50% 就是 1/2）。
     */
    private fun cooldownAt(l: Level): Int = when (l) {
        Level.CLEAN -> 1
        Level.SUSPICIOUS -> 3
        Level.INJECTED -> 2
        Level.INTEGRITY_FAIL -> 1
    }

    fun selfTest(): List<String> {
        val failures = ArrayList<String>()
        if (classifyBlob("libfrida-agent.so", debug = false, dirty = false) != Level.INJECTED) {
            failures.add("frida 桥应判 INJECTED")
        }
        if (classifyBlob("de.robv.android.xposed.XposedBridge", debug = false, dirty = false) != Level.INJECTED) {
            failures.add("xposed 栈应判 INJECTED")
        }
        if (classifyBlob("", debug = true, dirty = false) != Level.INJECTED) {
            failures.add("调试器附着应判 INJECTED")
        }
        if (classifyBlob("", debug = false, dirty = true) != Level.SUSPICIOUS) {
            failures.add("覆盖层/无障碍应判 SUSPICIOUS")
        }
        if (classifyBlob("", debug = false, dirty = false) != Level.CLEAN) {
            failures.add("空信号应判 CLEAN")
        }
        // 单调性：级别越高，放行的形态必须严格更少
        val cleanN = FrailForm.Family.values().count { acceptsAt(Level.CLEAN, it) }
        val suspN = FrailForm.Family.values().count { acceptsAt(Level.SUSPICIOUS, it) }
        val injN = FrailForm.Family.values().count { acceptsAt(Level.INJECTED, it) }
        val failN = FrailForm.Family.values().count { acceptsAt(Level.INTEGRITY_FAIL, it) }
        if (!(cleanN > suspN && suspN > injN && injN > failN)) {
            failures.add("分级放行集非严格单调：clean=$cleanN susp=$suspN inj=$injN fail=$failN")
        }
        if (!(cooldownAt(Level.INJECTED) < cooldownAt(Level.SUSPICIOUS))) {
            failures.add("L2 冷却倍率未比 L1 更严")
        }
        if (!acceptsAt(Level.INJECTED, FrailForm.Family.STANCE)) {
            failures.add("L2 应仍放行 STANCE 族，否则等于自停")
        }
        if (acceptsAt(Level.INJECTED, FrailForm.Family.DECODE)) {
            failures.add("L2 不应再放行 DECODE 族")
        }
        return failures
    }
}
