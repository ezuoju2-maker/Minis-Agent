package com.openminis.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.openminis.app.logging.AppLogger

object EnvironmentBanner {

    private const val TAG = "Env"

    fun log(context: Context) {
        try {
            val rt = Runtime.getRuntime()
            val mb = 1024L * 1024L

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

            val memClass = am?.memoryClass ?: -1
            val largeMemClass = am?.largeMemoryClass ?: -1

            AppLogger.info(
                TAG,
                "[Env] device=${Build.MANUFACTURER}/${Build.BRAND}/${Build.MODEL} " +
                    "device_codename=${Build.DEVICE} product=${Build.PRODUCT} " +
                    "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                    "rom=${Build.DISPLAY} " +
                    "abi=${Build.SUPPORTED_ABIS.joinToString("|")}",
            )
            AppLogger.info(
                TAG,
                "[Env] heapCeilingMB=${rt.maxMemory() / mb} " +
                    "memoryClassMB=$memClass largeMemoryClassMB=$largeMemClass " +
                    "deviceTotalRamMB=${mi.totalMem / mb} deviceAvailRamMB=${mi.availMem / mb} " +
                    "lowMemThresholdMB=${mi.threshold / mb} deviceLowMemory=${mi.lowMemory} " +
                    "cpuCores=${rt.availableProcessors()}",
            )
            AppLogger.info(TAG, "[Env] memory ${MemorySnapshot.capture().toLogString()}")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[Env] banner failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
