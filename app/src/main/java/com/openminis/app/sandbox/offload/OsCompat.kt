package com.openminis.app.sandbox.offload

import android.content.Context
import android.os.Build

internal object OsCompat {

    fun isHarmonyOsNext(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val hasHarmony = pm.hasSystemFeature("com.huawei.software.features.ohos") ||
                pm.hasSystemFeature("ohos.system.harmony")
            if (!hasHarmony) return false
            val harmonyVer = systemProp("hw_sc.build.os.enable")
                .ifEmpty { systemProp("ro.build.version.harmony") }
            harmonyVer.startsWith("5") || harmonyVer.startsWith("6")
        } catch (_: Throwable) {
            false
        }
    }

    fun systemProp(key: String): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java)
            (m.invoke(null, key) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Redmi", ignoreCase = true)

    val isHuawei: Boolean
        get() = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
            Build.MANUFACTURER.equals("HONOR", ignoreCase = true)

    fun oemLabel(): String {
        val mfr = Build.MANUFACTURER.ifBlank { "Android" }
        return mfr.replaceFirstChar { it.titlecase() }
    }
}
