package com.openminis.app.clientfrail

import java.util.Locale

/**
 * 策略转移权重表。
 *
 * 状态机原本拒答后按固定顺序换下一个形态，不看这家 provider 的历史表现。
 * 这里按 provider family 各存一张 "形态 i 失败后接形态 j 的成功率" 表，
 * 每次反馈后用 Q-learning 式更新写回，选招时按 softmax 抽样：
 *
 *   Q(f,i,j) <- Q(f,i,j) + α × (r - Q(f,i,j) + γ × max(j') Q(f,j,j'))
 *
 * r 取 +1（这一轮换完拿到了可用正文）或 -1（又被拒）。冷启动时表是空的，
 * 先用 PRIOR 给同族形态一点基础权重，避免第一轮完全随机。
 *
 * 落盘键只含 family 与形态序号，不碰任何业务内容。
 */
internal object TransitionPolicy {

    private const val ALPHA = 0.25f
    private const val GAMMA = 0.6f
    private const val PRIOR = 0.35f
    private const val TEMP = 1.6f
    private const val MAX_ENTRIES = 512

    /** family -> "i|j" -> Q 值 */
    private val tables = HashMap<String, HashMap<String, Float>>()

    @Synchronized
    fun load(family: String) {
        val p = FrailStore.prefs() ?: return
        val out = HashMap<String, Float>()
        val prefix = "q_${family}_"
        for (k in p.all.keys.filter { it.startsWith(prefix) }) {
            val v = p.getFloat(k, 0f)
            if (v != 0f) out[k.removePrefix(prefix)] = v
        }
        tables[family] = out
    }

    @Synchronized
    fun tableFor(family: String): HashMap<String, Float> =
        tables.getOrPut(family) {
            HashMap<String, Float>().also { }
        }

    @Synchronized
    fun pick(family: String, from: String?, candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val t = tableFor(family)
        if (from == null) {
            val best = candidates.maxByOrNull { c -> weightedDeg(family, c) } ?: return null
            return sample(family, candidates) ?: best
        }
        val scored = candidates.map { c -> c to (t[key(from, c)] ?: priorOf(from, c)) }
        val best = scored.maxByOrNull { it.second }?.first ?: candidates.first()
        if (scored.all { it.second <= 0f }) return best
        return sample(family, candidates) ?: best
    }

    @Synchronized
    fun update(family: String, from: String?, to: String, reward: Float) {
        if (from == null || from == to) return
        val t = tableFor(family)
        val k = key(from, to)
        val old = t[k] ?: priorOf(from, to)
        val nextFp = bestValue(t, to)
        t[k] = old + ALPHA * (reward - old + GAMMA * nextFp)
        if (t.size > MAX_ENTRIES) {
            val drop = t.entries.minByOrNull { kotlin.math.abs(it.value) }?.key
            if (drop != null) t.remove(drop)
        }
        FrailStore.putFloat(FrailStore.prefs(), "q_${family}_$k", t[k]!!)
    }

    @Synchronized
    fun reset(family: String) {
        val p = FrailStore.prefs() ?: return
        val prefix = "q_${family}_"
        val ed = p.edit()
        p.all.keys.filter { it.startsWith(prefix) }.forEach { ed.remove(it) }
        ed.apply()
        tables.remove(family)
    }

    fun describe(family: String): String {
        val t = tableFor(family)
        return "entries=${t.size} top=" + t.entries.asSequence()
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(",") { "${it.key}=${"%.2f".format(Locale.ROOT, it.value)}" }
    }

    fun selfTest(): List<String> {
        val out = ArrayList<String>()
        tables.clear()
        val t = tableFor("selftest")
        t["a.b|a.c"] = 0.9f
        t["a.b|a.d"] = -0.4f
        val picked = pick("selftest", "a.b", listOf("a.c", "a.d", "a.e"))
        if (picked == null) out.add("pick returned null on non-empty candidates")
        if (pick("selftest", "a.b", emptyList()) != null) out.add("pick should be null on empty")
        update("selftest", "a.b", "a.c", 1f)
        val v = t["a.b|a.c"] ?: 0f
        if (v <= 0.9f) out.add("update did not raise rewarded weight: $v")
        update("selftest", "a.b", "a.d", -1f)
        val w = t["a.b|a.d"] ?: 0f
        if (w >= -0.4f) out.add("update did not lower penalised weight: $w")
        if (describe("selftest").isBlank()) out.add("describe empty")
        tables.clear()
        return out
    }

    private fun sample(family: String, candidates: List<String>): String? {
        val t = tableFor(family)
        val w = candidates.map { c ->
            c to kotlin.math.exp((t[c + "|*"] ?: 0f) / TEMP)
        }
        val sum = w.sumOf { it.second.toDouble() }
        if (sum <= 0.0) return null
        var r = kotlin.random.Random.nextDouble() * sum
        for ((c, wt) in w) {
            r -= wt
            if (r <= 0.0) return c
        }
        return w.lastOrNull()?.first
    }

    private fun weightedDeg(family: String, c: String): Float {
        val t = tableFor(family)
        var s = 0f
        var n = 0
        for ((k, v) in t) {
            if (k.startsWith("$c|")) { s += v; n++ }
        }
        return if (n == 0) 0f else s / n
    }

    private fun bestValue(t: HashMap<String, Float>, state: String): Float {
        var best = 0f
        for ((k, v) in t) {
            if (k.startsWith("$state|") && v > best) best = v
        }
        return best
    }

    private fun priorOf(from: String, to: String): Float =
        if (familyTag(from) == familyTag(to)) PRIOR else PRIOR * 0.4f

    private fun familyTag(id: String): String = id.substringBefore('.')

    private fun key(from: String, to: String): String = "$from|$to"
}
