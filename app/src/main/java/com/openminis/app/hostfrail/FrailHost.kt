package com.openminis.app.hostfrail

import android.content.Context
import com.openminis.app.clientfrail.FrailOrchestrator
import java.util.concurrent.atomic.AtomicBoolean

object FrailHost {

    private val bootstrapped = AtomicBoolean(false)

    @Volatile
    private var lastSnapshot: EnvironmentSnapshot? = null

    @JvmStatic
    fun bootstrap(context: Context) {
        if (bootstrapped.getAndSet(true)) return
        val app = context.applicationContext
        EnvironmentSensor.bindActivityManager(app)
        HostOrigin.retain(app)
        NativeProbe.load()
        HostResponse.restore(app)
        FrailOrchestrator.install(app)
        val snap = EnvironmentSensor.snapshot(app)
        lastSnapshot = snap
        FrailOrchestrator.bindHostSummary(snap.summary())
        IntegrityLedger.record(
            app,
            sourceDigest = sourceFingerprint(),
            versionCode = versionCodeOf(app),
        )
        HostResponse.markIntegrityDrift(verify(app).drifted)
        FrailSentinel.start(app)
        android.util.Log.i(
            "FrailHost",
            "bootstrapped pkg=${HostOrigin.packageName()} ver=${versionCodeOf(app)} " +
                "origin=${HostOrigin.signingDigest(app)?.take(12) ?: "?"} ${snap.summary()} " +
                "native=${NativeProbe.describe()}",
        )
    }

    fun snapshot(): EnvironmentSnapshot? = lastSnapshot

    fun refresh(context: Context): EnvironmentSnapshot {
        val app = context.applicationContext
        val snap = EnvironmentSensor.snapshot(app)
        lastSnapshot = snap
        FrailOrchestrator.bindHostSummary(snap.summary())
        return snap
    }

    fun sourceFingerprint(): String {
        val parts = ArrayList<String>()
        try {
            val dir = java.io.File("/data/app")
            parts.add("appdir=" + (dir.takeIf { it.exists() }?.list()?.size ?: -1))
        } catch (_: Throwable) {
            parts.add("appdir=?")
        }
        parts.add("abis=" + android.os.Build.SUPPORTED_ABIS.joinToString(","))
        parts.add("sdk=" + android.os.Build.VERSION.SDK_INT)
        parts.add("loader=" + (javaClass.classLoader?.javaClass?.name ?: "?"))
        return IntegrityLedger.combine(parts)
    }

    fun versionCodeOf(context: Context): Long = runCatching {
        val info = HostOrigin.packageInfo(context) ?: return 0L
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    fun verify(context: Context): DriftReport {
        val app = context.applicationContext
        val report = IntegrityLedger.verify(
            app,
            sourceDigest = sourceFingerprint(),
            versionCode = versionCodeOf(app),
        )
        android.util.Log.i(
            "FrailHost",
            "integrity ${report.summary()} ${IntegrityLedger.baselineSummary(app)}",
        )
        return report
    }

    fun diagnostics(context: Context): String = buildString {
        append("host=").append(HostOrigin.packageName())
        append(" ver=").append(versionCodeOf(context))
        append(" origin=").append(HostOrigin.signingDigest(context)?.take(16) ?: "?")
        append(' ').append(NativeProbe.describe())
        append(' ').append(FrailOrchestrator.diagnostics())
        val s = lastSnapshot
        if (s != null) append(' ').append(s.summary())
        append(' ').append(IntegrityLedger.baselineSummary(context))
        append(' ').append(FrailSentinel.diagnostics())
    }

    fun isBootstrapped(): Boolean = bootstrapped.get()
}
