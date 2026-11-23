package com.openminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.sync.withLock
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.MountedFoldersStore
import java.io.File
import java.util.TimeZone
import kotlin.math.abs

object PRootKernel {

    private const val TAG = "PRootKernel"

    var isBooted: Boolean = false
        private set

    var nativeLibDir: String = ""
        private set

    var prootLoaderPath: String = ""
        private set

    var prootLoader32Path: String = ""
        private set

    private lateinit var rootfsManager: RootfsManager

    private val bootMutex = kotlinx.coroutines.sync.Mutex()

    val customEnvironment: MutableMap<String, String> = mutableMapOf()

    val bindMounts: MutableMap<String, String> = linkedMapOf()

    @Volatile
    private var legacySessionId: String? = null

    @Volatile
    private var legacyFilesDir: File? = null

    fun noteLegacySession(context: Context, sessionId: String) = noteLegacySession(context.filesDir, sessionId)

    internal fun noteLegacySession(filesDir: File, sessionId: String) {
        legacyFilesDir = filesDir
        legacySessionId = sessionId
    }

    @Volatile
    var legacyResolveCount: Long = 0
        private set


    suspend fun boot(context: Context) {
        if (isBooted) {
            Log.d(TAG, "Already booted")
            return
        }
        bootMutex.withLock {
            if (isBooted) {
                Log.d(TAG, "Already booted (post-lock)")
                return@withLock
            }
            bootLocked(context)
        }
    }

    private suspend fun bootLocked(context: Context) {
        rootfsManager = RootfsManager.getInstance(context)
        rootfsManager.installIfNeeded()
        rootfsManager.installProotIfNeeded()

        rootfsManager.applyDefaultMountOverlay()

        com.openminis.app.ui.sandbox.MirrorSpeedTestViewModel.applyAllActiveMirrors(context)
        com.openminis.app.ui.sandbox.MirrorSpeedTestViewModel.autoDetectOnceIfNeeded(context)

        rootfsManager.refreshDns()

        nativeLibDir = rootfsManager.nativeLibDir.absolutePath

        val loaderPath = File(rootfsManager.nativeLibDir, "libproot-loader.so")
        val loader32Path = File(rootfsManager.nativeLibDir, "libproot-loader32.so")
        if (loaderPath.exists()) prootLoaderPath = loaderPath.absolutePath
        if (loader32Path.exists()) prootLoader32Path = loader32Path.absolutePath

        customEnvironment.putIfAbsent("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin")
        customEnvironment.putIfAbsent("HOME", "/root")

        customEnvironment["BROWSER"] = "/usr/local/bin/minis-open"

        customEnvironment.putIfAbsent("ENV", "/etc/profile")

        customEnvironment.putIfAbsent("CHARSET", "UTF-8")

        customEnvironment.putIfAbsent("NO_COLOR", "1")
        customEnvironment.putIfAbsent("PYTHONDONTWRITEBYTECODE", "1")
        customEnvironment.putIfAbsent("GOMAXPROCS", "2")

        customEnvironment.putIfAbsent("UV_LINK_MODE", "symlink")

        if (!customEnvironment.containsKey("GIT_CONFIG_COUNT")) {
            customEnvironment.putIfAbsent("GIT_CONFIG_COUNT", "1")
            customEnvironment.putIfAbsent("GIT_CONFIG_KEY_0", "core.createObject")
            customEnvironment.putIfAbsent("GIT_CONFIG_VALUE_0", "rename")
        }

        customEnvironment["TZ"] = posixTz()

        customEnvironment.putAll(systemProxyEnv(context))

        registerGlobalBindMounts(context)

        NativeOffloadServer.start(rootfsManager.rootfsDir)

        installHandlerStubs(rootfsManager.rootfsDir)

        applyMountedFoldersSnapshot(context)

        isBooted = true
        Log.i(TAG, "PRoot kernel booted " +
            "rootfs=${rootfsManager.rootfsDir.absolutePath} " +
            "nativeLibDir=$nativeLibDir " +
            "loader=$prootLoaderPath " +
            "bindMounts=${bindMounts.size} " +
            "offloadHandlers=${NativeOffloadServer.registeredHandlers.sorted()}")
    }

    fun addBindMount(linuxPath: String, hostPath: String) {
        if (isPerSessionPath(linuxPath)) {
            Log.e(TAG, "refusing per-session bind mount in the global table: $linuxPath -> $hostPath")
            return
        }
        bindMounts[linuxPath] = hostPath
    }

    fun isPerSessionPath(linuxPath: String): Boolean {
        if (!linuxPath.startsWith("/var/minis/")) return false
        val sub = linuxPath.removePrefix("/var/minis/").substringBefore('/')
        return sub in perSessionSubdirs
    }

    fun registerGlobalBindMounts(context: Context) {
        val globalBase = File(context.filesDir, "minis-global")
        listOf("memory", "skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            bindMounts["/var/minis/$subdir"] = hostDir.absolutePath
        }
    }

    fun removeBindMount(linuxPath: String) {
        bindMounts.remove(linuxPath)
    }

    fun clearBindMounts() {
        bindMounts.clear()
    }

    private const val MOUNTS_LINUX_PREFIX = "/var/minis/mounts/"

    private const val GUARD_MARKER = "minis-mount-readonly-guard"

    @Volatile
    var mountedFoldersStore: MountedFoldersStore? = null

    fun applyMountedFoldersSnapshot(context: Context) {
        val store = mountedFoldersStore
        val desired: Map<String, String> = if (store == null) {
            emptyMap()
        } else {
            store.entries.value
                .mapNotNull { entry ->
                    val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: return@mapNotNull null
                    "$MOUNTS_LINUX_PREFIX${entry.name}" to host
                }
                .toMap()
        }

        val stale = bindMounts.keys
            .filter { it.startsWith(MOUNTS_LINUX_PREFIX) }
            .filter { it !in desired }
        for (key in stale) bindMounts.remove(key)

        for ((linuxPath, hostPath) in desired) {
            bindMounts[linuxPath] = hostPath
        }

        materializeMountTargets(context, desired.keys)

        val readOnlyLinuxPrefixes = store?.entries?.value.orEmpty()
            .filter { it.name in desired.keys.map { k -> k.removePrefix(MOUNTS_LINUX_PREFIX) } }
            .filterNot { it.effectiveWritable }
            .map { "$MOUNTS_LINUX_PREFIX${it.name}" }
        installMountWriteGuards(context, readOnlyLinuxPrefixes)

        val entryCount = store?.entries?.value?.size ?: 0
        Log.i(
            TAG,
            "applyMountedFoldersSnapshot: entries=$entryCount active=${desired.size} removed=${stale.size} " +
                storageAccessDiag(context),
        )
        if (entryCount > desired.size) {
            Log.w(
                TAG,
                "applyMountedFoldersSnapshot: ${entryCount - desired.size} mount(s) dropped — host path " +
                    "unresolved or unreadable (see resolveTreeUriToHostPath warnings above)",
            )
        }
    }

    private fun materializeMountTargets(context: Context, desiredLinuxPaths: Set<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            Log.w(TAG, "materializeMountTargets: rootfs not yet available: ${t.message}")
            return
        }
        val mountsRoot = File(rootfs, "var/minis/mounts").also { it.mkdirs() }

        for (linuxPath in desiredLinuxPaths) {
            val name = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
            File(mountsRoot, name).also { d ->
                if (!d.exists()) d.mkdirs()
            }
        }

        val desiredNames = desiredLinuxPaths.map { it.removePrefix(MOUNTS_LINUX_PREFIX) }.toSet()
        mountsRoot.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name !in desiredNames) {
                f.deleteRecursively()
            }
        }
    }

    fun isLinuxPathUnderReadOnlyMount(linuxPath: String): Boolean {
        if (!linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) return false
        val store = mountedFoldersStore ?: return false
        val rest = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
        val name = rest.substringBefore('/')
        if (name.isEmpty()) return false
        val entry = store.entries.value.firstOrNull { it.name == name } ?: return false
        return !entry.effectiveWritable
    }

    fun mountEntriesForIndex(context: Context): List<FileMentionIndex.MountEntry> {
        val store = mountedFoldersStore ?: return emptyList()
        return store.entries.value.mapNotNull { entry ->
            val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: return@mapNotNull null
            FileMentionIndex.MountEntry(name = entry.name, root = File(host))
        }
    }

    private fun resolveTreeUriToHostPath(treeUriString: String, context: Context): String? = try {
        val treeUri = Uri.parse(treeUriString)
        if (treeUri.authority != "com.android.externalstorage.documents") {
            null
        } else {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(':', limit = 2)
            val volumeId = parts.getOrNull(0).orEmpty()
            val relPath = parts.getOrNull(1).orEmpty()
            val volumeRoot = resolveVolumeRoot(volumeId, context)
            if (volumeRoot == null) {
                Log.w(TAG, "resolveTreeUriToHostPath: unknown volume '$volumeId' for $treeUriString")
                null
            } else {
                val candidate = if (relPath.isEmpty()) File(volumeRoot) else File(volumeRoot, relPath)
                if (candidate.exists() && candidate.canRead()) {
                    val childCount = candidate.list()?.size
                    if (childCount == null) {
                        Log.w(
                            TAG,
                            "resolveTreeUriToHostPath: host=${candidate.absolutePath} canRead=true " +
                                "but list() returned null (readdir EACCES / scoped-storage filtered) — " +
                                "mount will appear empty. ${storageAccessDiag(context)}",
                        )
                    } else {
                        Log.i(
                            TAG,
                            "resolveTreeUriToHostPath: host=${candidate.absolutePath} readable childCount=$childCount",
                        )
                    }
                    candidate.absolutePath
                } else {
                    Log.w(
                        TAG,
                        "resolveTreeUriToHostPath: host=${candidate.absolutePath} " +
                            "exists=${candidate.exists()} canRead=${candidate.canRead()} — bind skipped",
                    )
                    null
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "resolveTreeUriToHostPath failed for $treeUriString: ${t.message}")
        null
    }

    private fun storageAccessDiag(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        return when {
            sdk >= Build.VERSION_CODES.R ->
                "sdk=$sdk allFilesAccess=${Environment.isExternalStorageManager()}"
            else -> {
                val granted = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val legacyOptIn = if (sdk >= Build.VERSION_CODES.Q) {
                    Environment.isExternalStorageLegacy().toString()
                } else {
                    "n/a(pre-Q)"
                }
                "sdk=$sdk legacyStorageGranted=$granted legacyExtStorageOptIn=$legacyOptIn"
            }
        }
    }

    private fun resolveVolumeRoot(volumeId: String, context: Context): String? {
        if (volumeId.equals("primary", ignoreCase = true) || volumeId.isEmpty()) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
            for (volume in sm.storageVolumes) {
                if (volume.uuid?.equals(volumeId, ignoreCase = true) == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        return volume.directory?.absolutePath
                    }
                }
            }
        }
        return null
    }

    fun posixTz(): String {
        val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val secs = offsetMs / 1000L
        val hrs = (secs / 3600).toInt()
        val mins = ((abs(secs) % 3600) / 60).toInt()
        val posixHrs = -hrs
        val sign = if (posixHrs >= 0) "+" else "-"
        return if (mins != 0) {
            "LCL$sign${abs(posixHrs)}:${"%02d".format(mins)}"
        } else {
            "LCL$sign${abs(posixHrs)}"
        }
    }

    fun updateTimezone(): String {
        val tz = posixTz()
        customEnvironment["TZ"] = tz
        Log.i(TAG, "Updated TZ=$tz")
        return tz
    }

    private val PROXY_KEYS = listOf(
        "http_proxy", "https_proxy",
        "HTTP_PROXY", "HTTPS_PROXY",
        "no_proxy", "NO_PROXY",
    )

    private const val PROXY_NO_PROXY = "localhost,127.0.0.1,::1"

    fun systemProxyEnv(context: Context): Map<String, String> {
        val proxyUri = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val info = cm?.defaultProxy
            if (info != null && info.host.isNotBlank() && info.port > 0) {
                "http://${info.host}:${info.port}"
            } else {
                ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read system proxy: ${t.message}")
            ""
        }

        val out = linkedMapOf<String, String>()
        for (key in PROXY_KEYS) {
            out[key] = when (key) {
                "no_proxy", "NO_PROXY" -> if (proxyUri.isNotEmpty()) PROXY_NO_PROXY else ""
                else -> proxyUri
            }
        }
        return out
    }

    fun updateProxy(context: Context): Map<String, String> {
        val env = systemProxyEnv(context)
        customEnvironment.putAll(env)
        val hasProxy = env["http_proxy"].orEmpty().isNotEmpty()
        Log.i(TAG, "Updated proxy — ${if (hasProxy) "active=${env["http_proxy"]}" else "cleared"}")
        return env
    }

    fun buildProotCommand(shellCommand: String): List<String> {
        check(isBooted) { "PRootKernel.boot() must be called before building commands" }

        val cmd = mutableListOf<String>()

        cmd.add(rootfsManager.prootBinary.absolutePath)

        cmd.add("-0")

        cmd.add("--link2symlink")

        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)

        cmd.add("-b")
        cmd.add("/dev")
        cmd.add("-b")
        cmd.add("/proc")
        cmd.add("-b")
        cmd.add("/sys")

        cmd.add("-w")
        cmd.add("/root")

        for ((linuxPath, hostPath) in bindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
            if (linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) {
                Log.i(TAG, "proot bind mount: $hostPath -> $linuxPath")
            }
        }

        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty() && NativeOffloadServer.awaitListening()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        cmd.add("--fake-netlink")

        cmd.addAll(com.openminis.app.network.ShellTransparentNet.prootArgs(rootfsManager.prootBinary))

        cmd.add("/bin/bash")
        cmd.add("-c")
        cmd.add(shellCommand)

        Log.d(TAG, "proot cmd: ${cmd.take(cmd.size - 1).joinToString(" ")} <shellCommand ${shellCommand.length} bytes>")
        return cmd
    }

    private val perSessionSubdirs = SessionMounts.SESSION_SUBDIRS.toSet()

    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? {
        if (!linuxPath.startsWith("/var/minis/")) return resolveHostPath(linuxPath)
        val rest = linuxPath.removePrefix("/var/minis/")
        val slash = rest.indexOf('/')
        val subdir = if (slash < 0) rest else rest.substring(0, slash)
        if (subdir !in perSessionSubdirs) return resolveHostPath(linuxPath)
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId/$subdir")
        val tail = if (slash < 0) "" else rest.substring(slash + 1)
        return if (tail.isEmpty()) sessionBase else File(sessionBase, tail)
    }

    fun resolveHostPath(linuxPath: String): File? {
        val sorted = bindMounts.keys.sortedByDescending { it.length }
        for (mountPoint in sorted) {
            if (linuxPath == mountPoint || linuxPath.startsWith("$mountPoint/")) {
                val hostBase = bindMounts[mountPoint]!!
                val relativePath = linuxPath.removePrefix(mountPoint).removePrefix("/")
                return if (relativePath.isEmpty()) {
                    File(hostBase)
                } else {
                    File(hostBase, relativePath)
                }
            }
        }

        if (isPerSessionPath(linuxPath)) {
            val sid = legacySessionId
            val filesDir = legacyFilesDir
            if (sid != null && filesDir != null) {
                legacyResolveCount++
                val rest = linuxPath.removePrefix("/var/minis/")
                Log.d(TAG, "legacy session resolve $linuxPath -> session $sid")
                return File(filesDir, "minis-sessions/$sid/$rest")
            }
        }

        if (!::rootfsManager.isInitialized) return null
        val stripped = linuxPath.removePrefix("/")
        return if (stripped.isEmpty()) rootfsManager.rootfsDir else File(rootfsManager.rootfsDir, stripped)
    }

    private fun installHandlerStubs(rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin").also { it.mkdirs() }
        var created = 0
        var existed = 0
        var upgraded = 0
        for (name in NativeOffloadServer.registeredHandlers) {
            val stub = File(binDir, name)
            val want = HandlerStub.content(name)
            if (stub.exists()) {
                val have = runCatching { stub.readText() }.getOrNull()
                if (have == HandlerStub.LEGACY_CONTENT) {
                    stub.writeText(want)
                    stub.setExecutable(true, false)
                    upgraded++
                } else {
                    existed++
                }
                continue
            }
            stub.writeText(want)
            stub.setExecutable(true, false)
            created++
        }
        Log.d(TAG, "installHandlerStubs dir=${binDir.absolutePath} created=$created upgraded=$upgraded existed=$existed")

        installShellWrappers(binDir)
    }

    private fun installShellWrappers(binDir: File) {
        val topWrapper = File(binDir, "top")
        topWrapper.writeText(
            """#!/bin/sh
            |# Auto-installed by MinisApp PRootKernel.
            |# busybox top walks all of /proc and aborts on the first unreadable
            |# entry under Android's procfs hidepid restrictions, and this build
            |# of busybox doesn't accept `-p`. Emulate `top` with `ps` over the
            |# PIDs we can actually inspect.
            |
            |# Parse a few common top-style flags so existing muscle memory works.
            |delay=2
            |iters=-1
            |batch=0
            |while [ ${'$'}# -gt 0 ]; do
            |    case "${'$'}1" in
            |        -d) delay=${'$'}2; shift 2;;
            |        -n) iters=${'$'}2; shift 2;;
            |        -b) batch=1; shift;;
            |        -h|--help)
            |            echo "Usage: top [-b] [-n COUNT] [-d SECONDS]"
            |            exit 0;;
            |        *) shift;;
            |    esac
            |done
            |
            |saved_stty=""
            |on_exit() {
            |    [ ${'$'}batch -eq 0 ] && printf '\033[?25h'
            |    [ -n "${'$'}saved_stty" ] && stty "${'$'}saved_stty" 2>/dev/null
            |}
            |trap on_exit INT TERM EXIT
            |
            |list_readable_pids() {
            |    for d in /proc/[0-9]*; do
            |        [ -r "${'$'}d/stat" ] && printf '%s\n' "${'$'}{d##*/}"
            |    done
            |}
            |
            |render_once() {
            |    pids=${'$'}(list_readable_pids | tr '\n' ',' | sed 's/,${'$'}//')
            |    if [ -z "${'$'}pids" ]; then
            |        echo "top: no readable processes in /proc (Android sandbox)"
            |        return
            |    fi
            |    uptime_s=${'$'}(awk '{print int(${'$'}1)}' /proc/uptime 2>/dev/null)
            |    [ -z "${'$'}uptime_s" ] && uptime_s=0
            |    days=${'$'}((uptime_s / 86400))
            |    hours=${'$'}(((uptime_s % 86400) / 3600))
            |    mins=${'$'}(((uptime_s % 3600) / 60))
            |    visible=${'$'}(echo "${'$'}pids" | tr ',' '\n' | wc -l)
            |    printf 'top — up %dd %02d:%02d  visible processes: %d (own session only)\n' "${'$'}days" "${'$'}hours" "${'$'}mins" "${'$'}visible"
            |    printf '%s\n' '  PID USER     STAT  RSS  PPID COMMAND'
            |    /bin/busybox ps -o pid,user,stat,rss,ppid,comm 2>/dev/null \
            |        | awk -v pids=",${'$'}pids," 'NR==1 {next} {if (index(pids,","${'$'}1",")) print "  " ${'$'}0}'
            |}
            |
            |if [ ${'$'}batch -eq 1 ]; then
            |    count=0
            |    while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
            |        render_once
            |        count=${'$'}((count + 1))
            |        [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
            |        sleep "${'$'}delay"
            |    done
            |    exit 0
            |fi
            |
            |# Interactive mode: clear screen + redraw each interval. Exits on
            |# Ctrl+C OR when the user presses 'q' / 'Q'. We put the TTY into
            |# raw, no-echo mode so single keypresses are read without Enter.
            |if [ -t 0 ]; then
            |    saved_stty=${'$'}(stty -g 2>/dev/null)
            |    stty -echo -icanon min 0 time 0 2>/dev/null
            |fi
            |printf '\033[?25l'
            |count=0
            |while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
            |    printf '\033[H\033[2J'
            |    render_once
            |    count=${'$'}((count + 1))
            |    [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
            |    # Sleep in small slices so 'q' is responsive within ~0.1s.
            |    end=${'$'}((${'$'}(date +%s) + delay))
            |    while [ ${'$'}(date +%s) -lt ${'$'}end ]; do
            |        if [ -t 0 ]; then
            |            ch=${'$'}(dd bs=1 count=1 2>/dev/null)
            |            case "${'$'}ch" in q|Q) exit 0;; esac
            |        fi
            |        sleep 0.1
            |    done
            |done
            |""".trimMargin()
        )
        topWrapper.setExecutable(true, false)
        Log.d(TAG, "installShellWrappers: /usr/local/bin/top installed")
    }

    private fun installMountWriteGuards(context: Context, readOnlyLinuxPrefixes: List<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            return
        }
        val binDir = File(rootfs, "usr/local/bin").also { it.mkdirs() }
        val guardedCmds = listOf("touch", "tee", "cp", "mv", "mkdir", "rm", "rmdir", "ln", "dd")
        val configFile = File(rootfs, "var/minis/.mount-readonly-prefixes")

        if (readOnlyLinuxPrefixes.isEmpty()) {
            runCatching { configFile.delete() }
            for (name in guardedCmds) {
                val w = File(binDir, name)
                if (w.exists() && w.readText().contains(GUARD_MARKER)) w.delete()
            }
            Log.i(TAG, "installMountWriteGuards: no read-only mounts, guards cleared")
            return
        }

        configFile.parentFile?.mkdirs()
        configFile.writeText(readOnlyLinuxPrefixes.joinToString("\n", postfix = "\n"))

        for (name in guardedCmds) {
            val wrapper = File(binDir, name)
            wrapper.writeText(
                """#!/bin/sh
                |# $GUARD_MARKER — reject writes into read-only mounted folders.
                |# Auto-installed by MinisApp PRootKernel (T219-4).
                |cfg=/var/minis/.mount-readonly-prefixes
                |if [ -f "${'$'}cfg" ]; then
                |    for arg in "${'$'}@"; do
                |        case "${'$'}arg" in
                |            -*) continue;;  # skip flags
                |        esac
                |        while IFS= read -r prefix; do
                |            [ -z "${'$'}prefix" ] && continue
                |            case "${'$'}arg" in
                |                "${'$'}prefix"|"${'$'}prefix"/*)
                |                    echo "$name: ${'$'}arg: read-only mounted folder — writes are disabled (toggle in Settings → Mount External Folders)" >&2
                |                    exit 1;;
                |            esac
                |        done < "${'$'}cfg"
                |    done
                |fi
                |exec /bin/busybox $name "${'$'}@"
                |""".trimMargin(),
            )
            wrapper.setExecutable(true, false)
        }
        Log.i(
            TAG,
            "installMountWriteGuards: installed for ${guardedCmds.size} cmds, " +
                "readOnlyPrefixes=${readOnlyLinuxPrefixes.joinToString(",")}",
        )
    }

    internal fun getProotTmpDir(context: Context): File {
        val tmpDir = File(context.cacheDir, "proot-tmp")
        tmpDir.mkdirs()
        return tmpDir
    }
}
