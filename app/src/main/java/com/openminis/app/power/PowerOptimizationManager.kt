package com.openminis.app.power

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.openminis.app.logging.AppLogger

object PowerOptimizationManager {
    private const val TAG = "PowerOpt"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestBatteryOptimizationExemption(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${activity.packageName}"))
        if (tryStartActivity(activity, direct)) {
            AppLogger.info(TAG, "battery-opt direct dialog launched")
            return true
        }
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (tryStartActivity(activity, list)) {
            AppLogger.info(TAG, "battery-opt list page launched (fallback)")
            return true
        }
        AppLogger.warning(TAG, "no battery-opt settings page available on this device")
        return false
    }

    enum class Vendor(val displayName: String) {
        XIAOMI("Xiaomi / Redmi"),
        HUAWEI("Huawei / Honor"),
        OPPO("OPPO / OnePlus / Realme"),
        VIVO("Vivo / iQOO"),
        SAMSUNG("Samsung"),
        OTHER("Other"),
        ;

        companion object {
            fun current(): Vendor {
                val m = Build.MANUFACTURER.lowercase()
                val b = Build.BRAND.lowercase()
                return when {
                    m.contains("xiaomi") || m.contains("redmi") || b.contains("xiaomi") -> XIAOMI
                    m.contains("huawei") || m.contains("honor") || b.contains("huawei") -> HUAWEI
                    m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> OPPO
                    m.contains("vivo") || m.contains("iqoo") -> VIVO
                    m.contains("samsung") -> SAMSUNG
                    else -> OTHER
                }
            }
        }
    }

    fun needsOemAutostartGuidance(): Boolean = Vendor.current() != Vendor.OTHER

    fun openOemAutostartSettings(activity: Activity): Boolean {
        val candidates: List<ComponentName> = when (Vendor.current()) {
            Vendor.XIAOMI -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            )
            Vendor.HUAWEI -> listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            Vendor.OPPO -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            Vendor.VIVO -> listOf(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            Vendor.SAMSUNG -> listOf(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            Vendor.OTHER -> emptyList()
        }
        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(activity, intent)) {
                AppLogger.info(TAG, "OEM autostart settings launched: ${component.flattenToShortString()}")
                return true
            }
        }
        AppLogger.warning(TAG, "no OEM autostart Activity matched on ${Build.MANUFACTURER}")
        return false
    }

    fun openAppDetailsSettings(activity: Activity): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${activity.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStartActivity(activity, intent)
    }

    private fun tryStartActivity(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        AppLogger.warning(TAG, "startActivity SecurityException: ${e.message}")
        false
    }
}
