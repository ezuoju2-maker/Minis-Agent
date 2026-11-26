package com.openminis.app.hostfrail

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

data class EnvironmentSnapshot(
    val debuggerAttached: Boolean = false,
    val tracerPid: Int = -1,
    val nativeBridges: List<String> = emptyList(),
    val javaBridges: List<String> = emptyList(),
    val hookImplications: List<String> = emptyList(),
    val repackImplications: List<String> = emptyList(),
    val overlayWindows: Int = 0,
    val nonSystemInputMethod: Boolean = false,
    val interceptingAccessibility: Boolean = false,
    val vpnActive: Boolean = false,
    val proxySurface: Boolean = false,
    val developmentBuild: Boolean = false,
    val emulatorSurface: Boolean = false,
    val externalStoragePath: String = "/",
) {
    val debugSurface: Boolean get() = debuggerAttached || tracerPid > 0 || developmentBuild

    val hookSurface: Boolean get() = nativeBridges.isNotEmpty() || javaBridges.isNotEmpty()

    val repackSurface: Boolean get() = repackImplications.isNotEmpty()

    val captureSurface: Boolean get() = overlayWindows > 0 || interceptingAccessibility

    val exposureScore: Int
        get() {
            var s = 0
            if (debugSurface) s += 3
            if (nativeBridges.isNotEmpty()) s += 3
            if (javaBridges.isNotEmpty()) s += 2
            if (repackSurface) s += 3
            if (overlayWindows > 0) s += 1
            if (interceptingAccessibility) s += 2
            if (nonSystemInputMethod) s += 1
            if (vpnActive) s += 1
            if (proxySurface) s += 1
            if (emulatorSurface) s += 1
            return s
        }

    val exposureLevel: ExposureLevel
        get() = when {
            exposureScore >= 6 -> ExposureLevel.SEVERE
            exposureScore >= 3 -> ExposureLevel.ELEVATED
            exposureScore >= 1 -> ExposureLevel.NOMINAL
            else -> ExposureLevel.CLEAN
        }

    fun summary(): String = buildString {
        append("score=").append(exposureScore)
        append(" level=").append(exposureLevel.name.lowercase(Locale.ROOT))
        append(" debug=").append(debugSurface)
        append(" native=[").append(nativeBridges.joinToString(",")).append(']')
        append(" java=[").append(javaBridges.joinToString(",")).append(']')
        append(" repack=[").append(repackImplications.joinToString(",")).append(']')
        append(" overlay=").append(overlayWindows)
        append(" a11y=").append(interceptingAccessibility)
        append(" vpn=").append(vpnActive)
        append(" proxy=").append(proxySurface)
        append(" emulator=").append(emulatorSurface)
    }
}

enum class ExposureLevel { CLEAN, NOMINAL, ELEVATED, SEVERE }

object EnvironmentSensor {

    private const val PROC_SELF_MAPS = "/proc/self/maps"
    private const val PROC_SELF_STATUS = "/proc/self/status"
    private const val PROC_SELF_TASK = "/proc/self/task"

    private val NATIVE_BRIDGE_MARKERS: List<String> = listOf(
        "frida-agent", "frida-gadget", "frida-server", "libfrida",
        "gum-js-loop", "gmain", "linjector", "libinjector",
        "substrate", "substrate-loader", "libsubstrate",
        "xposed", "libxposed", "riru", "zygisk", "zygote-loader",
        "magisk", "libmagisk", "kernelsu", "apatch",
        "lspatch", "pine", "sandhook", "whale", "bhook",
        "libhook", "adbi", "ddi", "libalex hiding",
    )

    private val JAVA_BRIDGE_MARKERS: List<String> = listOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XposedHelpers",
        "io.github.lsposed.lspd.core.Main",
        "org.lsposed.lspatch.",
        "com.saurik.substrate.MS",
        "com.topjohnwu.magisk.",
        "me.weishu.epic.",
    )

    private val EMULATOR_FILE_MARKERS: List<String> = listOf(
        "/dev/qemu_pipe", "/dev/goldfish_pipe", "/dev/goldfish_sync",
        "/dev/vboxguest", "/dev/vboxuser", "/system/bin/qemu-props",
        "/system/lib/libc_malloc_debug_qemu.so", "/system/lib64/libc_malloc_debug_qemu.so",
        "/dev/com.microsoft.emul", "/dev/hyperv_fb",
    )

    private val EMULATOR_PROPERTY_TOKENS: List<String> = listOf(
        "generic", "sdk", "emulator", "goldfish", "ranchu", "vbox86",
        "test-keys", "userdebug", "eng",
    )

    private val INJECTION_RELATED_PERMISSIONS: List<String> = listOf(
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
    )

    fun snapshot(context: Context): EnvironmentSnapshot {
        val jitter = NativeProbe.jitterCoefficient(NativeProbe.timingJitterSamples())
        val status = readStatus()
        val tracerFromStatus = parseTracerPid(status)
        scanThreadNames()

        //  nativeProbe() 走 JNI 回调把观测写进 NativeProbe.OBSERVATION，这里只取
        //  一次快照。上一版每次 snapshot 都 attach 一个监听器，那条强引用永不释放，
        //  长会话下等于逐个泄露 EnvironmentSnapshot。
        val native = NativeProbe.observeNow()
        val nativeBridges = native?.bridges ?: emptyList()
        val nativeImplications = native?.implications ?: emptyList()
        val javaBridges = detectJavaBridges(context)
        val overlay = countOverlays(context)
        val ime = detectForeignInputMethod(context)
        val a11y = detectInterceptingAccessibility(context)
        val vpn = detectVpn(context)
        val proxy = detectProxySurface()
        val repack = detectRepackImplications(context)
        val emulator = detectEmulator()

        return EnvironmentSnapshot(
            debuggerAttached = (native?.debuggerAttached == true) ||
                isDebuggerConnected() || jitter <= 0.004,
            tracerPid = if ((native?.tracerPid ?: -1) > 0) native!!.tracerPid else tracerFromStatus,
            nativeBridges = nativeBridges,
            javaBridges = javaBridges,
            hookImplications = nativeImplications,
            repackImplications = repack,
            overlayWindows = overlay,
            nonSystemInputMethod = ime,
            interceptingAccessibility = a11y,
            vpnActive = vpn,
            proxySurface = proxy,
            developmentBuild = isDevelopmentBuild(),
            emulatorSurface = emulator,
            externalStoragePath = File("/storage/emulated/${Process.myUserHandle().hashCode()}")
                .takeIf { it.exists() }?.absolutePath ?: "/",
        )
    }

    private fun isDebuggerConnected(): Boolean = runCatching {
        android.os.Debug.isDebuggerConnected()
    }.getOrDefault(false)

    fun readStatus(): String = runCatching {
        RandomAccessFile(PROC_SELF_STATUS, "r").use { it.readFully(ByteArray(it.length().toInt())) }
            .let { String(it, StandardCharsets.UTF_8) }
    }.getOrDefault("")

    fun parseTracerPid(status: String): Int {
        for (line in status.lineSequence()) {
            if (line.startsWith("TracerPid:")) {
                return line.removePrefix("TracerPid:").trim().toIntOrNull() ?: -1
            }
        }
        return -1
    }

    fun readTaskComm(tid: Int): String = runCatching {
        File(PROC_SELF_TASK, "$tid/comm").readText().trim()
    }.getOrDefault("")

    fun enumerateThreadNames(): List<String> = runCatching {
        File(PROC_SELF_TASK).listFiles()?.mapNotNull { dir ->
            runCatching { File(dir, "comm").readText().trim() }.getOrNull()
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun scanThreadNames(): List<String> {
        val names = enumerateThreadNames()
        val hits = mutableListOf<String>()
        for (n in names) {
            val low = n.lowercase(Locale.ROOT)
            if (NATIVE_BRIDGE_MARKERS.any { low.contains(it) }) hits.add(n)
        }
        return hits.distinct()
    }

    fun mappedSharedObjects(): List<String> = runCatching {
        File(PROC_SELF_MAPS).useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                parts.getOrNull(5)?.takeIf { it.endsWith(".so") || it.contains("/lib/") }
            }.distinct().toList()
        }
    }.getOrDefault(emptyList())

    fun scanMappedBridges(): List<String> {
        val hits = mutableListOf<String>()
        for (so in mappedSharedObjects()) {
            val low = so.lowercase(Locale.ROOT)
            if (NATIVE_BRIDGE_MARKERS.any { low.contains(it) }) hits.add(so)
        }
        return hits.distinct()
    }

    private fun detectJavaBridges(context: Context): List<String> {
        val hits = mutableListOf<String>()
        val cl = context.classLoader
        for (marker in JAVA_BRIDGE_MARKERS) {
            val present = runCatching {
                Class.forName(marker, false, cl) != null
            }.getOrDefault(false)
            if (present) hits.add(marker)
        }
        val stack = runCatching {
            Thread.currentThread().stackTrace.joinToString("\n") { it.className + "." + it.methodName }
        }.getOrDefault("")
        if (stack.contains("xposed", ignoreCase = true) ||
            stack.contains("com.saurik.substrate", ignoreCase = true)
        ) {
            hits.add("stack:hook-frame")
        }
        return hits.distinct()
    }

    private fun countOverlays(context: Context): Int = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0
        val wm = context.getSystemService(Context.WINDOW_SERVICE)
            as? android.view.WindowManager ?: return 0
        @Suppress("DEPRECATION")
        val list = wm.overlayPackages ?: return 0
        list.count { it != context.packageName }
    }.getOrDefault(0)

    private fun detectForeignInputMethod(context: Context): Boolean = runCatching {
        val settings = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return false
        val component = android.content.ComponentName.unflattenFromString(settings) ?: return false
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(component.packageName, 0)
        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
            component.packageName != context.packageName
    }.getOrDefault(false)

    private fun detectInterceptingAccessibility(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager ?: return false
        if (!am.isEnabled) return false
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        enabled.split(':').any { raw ->
            val cn = android.content.ComponentName.unflattenFromString(raw) ?: return@any false
            cn.packageName != context.packageName
        }
    }.getOrDefault(false)

    private fun detectVpn(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        false
    }.getOrDefault(false)

    private fun detectProxySurface(): Boolean {
        val host = System.getProperty("http.proxyHost")
        val port = System.getProperty("http.proxyPort")
        if (!host.isNullOrBlank() && !port.isNullOrBlank()) return true
        val socks = System.getProperty("socksProxyHost")
        return !socks.isNullOrBlank()
    }

    private fun detectRepackImplications(context: Context): List<String> {
        val out = mutableListOf<String>()
        val info = HostOrigin.packageInfo(context) ?: return listOf("package-info-unreadable")
        if (!HostOrigin.EXPECTED_PACKAGE.equals(info.packageName, ignoreCase = true)) {
            out.add("package-mismatch:${info.packageName}")
        }
        val sigs = HostOrigin.signaturesOf(info)
        if (sigs.isEmpty()) {
            out.add("no-signing-signature")
        } else if (sigs.size > 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            out.add("multi-signer-legacy")
        }
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        }.getOrNull()
        if (appInfo != null) {
            val flags = appInfo.flags
            if ((flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                out.add("debuggable-manifest")
            }
            if ((flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY) != 0) {
                out.add("test-only-manifest")
            }
            val nativeDir = appInfo.nativeLibraryDir
            if (nativeDir.isNullOrBlank()) out.add("native-dir-missing")
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val diags = am.processesInErrorState
            if (diags != null && diags.isNotEmpty()) out.add("process-error-state")
        }
        return out.distinct()
    }

    fun hasInjectionRelatedPermission(context: Context): Boolean {
        for (p in INJECTION_RELATED_PERMISSIONS) {
            val granted = runCatching {
                context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (granted) return true
        }
        return false
    }

    fun isDevelopmentBuild(): Boolean {
        val tags = Build.TAGS ?: ""
        val type = Build.TYPE ?: ""
        return tags.contains("test-keys", ignoreCase = true) ||
            type.equals("eng", ignoreCase = true) ||
            type.equals("userdebug", ignoreCase = true)
    }

    fun detectEmulator(): Boolean {
        if (isDevelopmentBuild()) return true
        for (f in EMULATOR_FILE_MARKERS) {
            if (File(f).exists()) return true
        }
        val blob = listOf(
            Build.FINGERPRINT ?: "",
            Build.MODEL ?: "",
            Build.MANUFACTURER ?: "",
            Build.HARDWARE ?: "",
            Build.BOARD ?: "",
            Build.PRODUCT ?: "",
            Build.DEVICE ?: "",
            Build.BRAND ?: "",
        ).joinToString(" ").lowercase(Locale.ROOT)
        if (EMULATOR_PROPERTY_TOKENS.any { blob.contains(it) }) return true
        if (Build.HARDWARE.lowercase(Locale.ROOT).contains("goldfish")) return true
        if (Build.HARDWARE.lowercase(Locale.ROOT).contains("ranchu")) return true
        val radio = Build.getRadioVersion() ?: ""
        if (radio.equals("unknown", ignoreCase = true) || radio.isEmpty()) {
            if (blob.contains("sdk") || blob.contains("emulator")) return true
        }
        return false
    }

    fun bootMarker(): String {
        val sb = StringBuilder()
        sb.append(Build.FINGERPRINT ?: "?").append('|')
        sb.append(Build.BOARD ?: "?").append('|')
        sb.append(Build.BOOTLOADER ?: "?").append('|')
        sb.append(Build.HARDWARE ?: "?").append('|')
        sb.append(Build.VERSION.SDK_INT).append('|')
        sb.append(Build.SUPPORTED_ABIS.joinToString(","))
        return sb.toString()
    }

    fun isPackageInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0) != null
    }.getOrDefault(false)

    fun launchedFromKnownInstaller(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer != null
    }.getOrDefault(false)

    fun probeRootIndicators(): List<String> {
        val hits = mutableListOf<String>()
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/magisk/.core/bin/su", "/data/adb/magisk", "/data/adb/ksu",
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
            "/data/app/com.topjohnwu.magisk", "/data/app/me.weishu.kernelsu",
        )
        for (p in paths) if (File(p).exists()) hits.add(p)
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(":")) {
            if (File(dir, "su").exists()) {
                hits.add("$dir/su")
                break
            }
        }
        return hits.distinct()
    }

    fun probeDebuggableProcesses(): List<String> = runCatching {
        val am = AppGlobals.cachedActivityManager ?: return emptyList()
        am.runningAppProcesses?.mapNotNull { it.processName } ?: emptyList()
    }.getOrDefault(emptyList())

    internal object AppGlobals {
        @Volatile
        var cachedActivityManager: ActivityManager? = null
    }

    fun bindActivityManager(context: Context) {
        AppGlobals.cachedActivityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    fun importAppContext(context: Context): Context = context.applicationContext ?: context

    fun selfIntent(): Intent = Intent(Intent.ACTION_MAIN).setPackage(EXPECTED_PACKAGE)
}
