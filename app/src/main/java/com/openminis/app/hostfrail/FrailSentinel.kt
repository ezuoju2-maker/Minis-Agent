package com.openminis.app.hostfrail

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 宿主层的持续观测环路。
 *
 * 单次启动快照抓不到两件事：一是进程起来之后再附加上来的调试器与注入段，
 * 二是被反复卸载重装、只在采样间隙存在的 hook。所以这里按固定周期重跑同一
 * 套探针，并把每次结果与基线比对，只把"新增"的部分记下来 —— 重复上报同一
 * 条已知特征没有信息量，只会把日志冲成噪声。
 *
 * 它只观测、只记录，不拦截、不退出、不改任何业务返回值：破甲层不依赖它，
 * 它挂掉也不影响设置链路。
 */
object FrailSentinel {

    private const val TAG = "FrailSentinel"
    private const val DEFAULT_INTERVAL_SECONDS = 45L
    private const val START_DELAY_SECONDS = 20L

    private val running = AtomicBoolean(false)
    private val ticks = AtomicInteger(0)
    private var executor: ScheduledExecutorService? = null
    private var handle: ScheduledFuture<*>? = null

    @Volatile
    private var baseline: Set<String> = emptySet()

    @Volatile
    private var lastSummary: String = "idle"

    fun start(context: Context, intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS) {
        if (running.getAndSet(true)) return
        val app = context.applicationContext
        baseline = EnvironmentSensor.snapshot(app).nativeBridges.toSet()
        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "frail-sentinel").apply { isDaemon = true }
        }
        executor = ex
        handle = ex.scheduleWithFixedDelay(
            { safeTick(app) },
            START_DELAY_SECONDS,
            intervalSeconds.coerceAtLeast(15L),
            TimeUnit.SECONDS,
        )
        Log.i(TAG, "armed interval=${intervalSeconds}s baseline=${baseline.size}")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        handle?.cancel(false)
        handle = null
        executor?.shutdownNow()
        executor = null
        lastSummary = "stopped"
    }

    private fun safeTick(app: Context) {
        try {
            tick(app)
        } catch (t: Throwable) {
            // 观测环路本身绝不能把宿主带崩；记一次就继续下一轮。
            Log.w(TAG, "tick failed: " + t.javaClass.simpleName)
        }
    }

    private fun tick(app: Context) {
        val n = ticks.incrementAndGet()
        val snap = EnvironmentSensor.snapshot(app)
        val current = snap.nativeBridges.toSet()

        val added = current - baseline
        val gone = baseline - current
        if (added.isNotEmpty()) {
            Log.w(TAG, "new bridges after tick $n: " + added.joinToString(","))
            // 新增注入段本身即基线漂移信号：直接触发一次完整复核，
            // 由 IntegrityLedger 去比对签名 / assets / boot marker。
            FrailHost.verify(app)
            HostResponse.evaluate(app, snap)
            baseline = current
        }
        lastSummary = buildString {
            append("tick=").append(n)
            append(" bridges=").append(current.size)
            append(" added=").append(added.size)
            append(" gone=").append(gone.size)
            append(" score=").append(snap.exposureScore)
            append(" response=").append(HostResponse.describe())
            if (snap.tracerPid > 0) append(" tracer=").append(snap.tracerPid)
        }
        // 每 8 轮做一次完整完整性复核，避免每轮都把 APK 读一遍。
        if (n % 8 == 0) {
            HostResponse.markIntegrityDrift(FrailHost.verify(app).drifted)
            HostResponse.evaluate(app, snap)
        }
    }

    fun isRunning(): Boolean = running.get()

    fun diagnostics(): String = "sentinel=" + lastSummary
}
