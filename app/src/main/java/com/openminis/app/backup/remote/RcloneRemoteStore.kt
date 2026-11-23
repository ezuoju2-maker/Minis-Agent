package com.openminis.app.backup.remote

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class RcloneRemoteStore internal constructor(
    private val context: Context,
    prefsOverride: SharedPreferences?,
    secretsOverride: SharedPreferences?,
) {

    constructor(context: Context) : this(context, null, null)

    @Serializable
    data class Remote(
        val name: String,
        val backend: String,
        val params: Map<String, String> = emptyMap(),
        val path: String = "",
        val createdAt: Long = 0,
        val enabled: Boolean = true,
        val allowInsecureTLS: Boolean = false,
    ) {
        val fsSpec: String get() = "$name:"

        fun join(child: String): String {
            if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                if (path == "/") return "/$child"
                val base = path.removeSuffix("/")
                return if (base.isEmpty()) child else "$base/$child"
            }
            val base = path.trim('/')
            return if (base.isEmpty()) child else "$base/$child"
        }

        val listRoot: String
            get() = if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                if (path == "/") "/" else path.removeSuffix("/")
            } else {
                path.trim('/')
            }
    }

    class StoreException(message: String) : Exception(message)

    private val prefs by lazy {
        prefsOverride ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val secrets by lazy {
        secretsOverride ?: EncryptedPrefsFactory.safeCreate(context, SECRETS_PREFS_NAME)
    }

    var remotes: List<Remote>
        get() = runCatching {
            prefs.getString(KEY_REMOTES, null)?.let { JSON.decodeFromString(REMOTE_LIST, it) }
        }.getOrNull() ?: emptyList()
        private set(value) {
            prefs.edit().putString(KEY_REMOTES, JSON.encodeToString(REMOTE_LIST, value)).apply()
        }

    fun remote(name: String): Remote? = remotes.firstOrNull { it.name == name }

    val enabledRemotes: List<Remote> get() = remotes.filter { it.enabled }


    fun addLocalFolder(name: String, treeUri: String, displayPath: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw StoreException("Choose a name for this folder.")
        if (remote(trimmed) != null) {
            throw StoreException("A destination named \"$trimmed\" already exists.")
        }
        if (remotes.any { it.backend == BACKEND_LOCAL && it.params[PARAM_TREE_URI] == treeUri }) {
            throw StoreException("That folder is already a backup destination.")
        }
        remotes = remotes + Remote(
            name = trimmed,
            backend = BACKEND_LOCAL,
            params = mapOf(PARAM_TREE_URI to treeUri),
            path = displayPath,
            createdAt = System.currentTimeMillis(),
        )
        AppLogger.info(TAG, "[Rclone] local folder destination added: $trimmed -> $displayPath")
    }


    fun add(
        name: String,
        backend: String,
        params: Map<String, String>,
        secret: String?,
        path: String,
        allowInsecureTLS: Boolean = false,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw StoreException("Choose a name using letters, numbers, - or _.")
        }
        if (remote(trimmed) != null) {
            throw StoreException("A destination named \"$trimmed\" already exists.")
        }
        if (!secret.isNullOrEmpty()) storeSecret(trimmed, secret)
        remotes = remotes + Remote(
            name = trimmed, backend = backend, params = params,
            path = path, createdAt = System.currentTimeMillis(),
            allowInsecureTLS = allowInsecureTLS,
        )
        AppLogger.info(TAG, "[Rclone] remote added: $trimmed ($backend)")
    }

    fun remove(name: String) {
        remotes = remotes.filterNot { it.name == name }
        secrets.edit().remove(name).apply()
        AppLogger.info(TAG, "[Rclone] remote removed: $name")
    }

    fun setEnabled(name: String, on: Boolean) {
        val all = remotes
        if (all.none { it.name == name }) return
        remotes = all.map { if (it.name == name) it.copy(enabled = on) else it }
        AppLogger.info(TAG, "[Rclone] remote '$name' ${if (on) "enabled" else "disabled"}")
    }


    sealed interface SecretEdit {
        data object Keep : SecretEdit
        data object Clear : SecretEdit
        data class Replace(val value: String) : SecretEdit
    }

    fun update(
        name: String,
        newName: String? = null,
        newPath: String? = null,
        newParams: Map<String, String>? = null,
        secret: SecretEdit = SecretEdit.Keep,
        allowInsecureTLS: Boolean? = null,
        verify: ((candidate: Remote, secret: String?) -> Unit)? = null,
    ): Remote {
        val all = remotes
        val i = all.indexOfFirst { it.name == name }
        if (i < 0) throw StoreException("That destination no longer exists.")
        val before = all[i]

        val targetName = newName?.trim()?.takeIf { it != name }
        if (targetName != null) {
            validateRename(targetName, local = isLocalFolder(before.backend))
            if (all.any { it.name == targetName }) {
                throw StoreException("A destination named \"$targetName\" already exists.")
            }
        }

        var updated = before
        if (targetName != null) updated = updated.copy(name = targetName)
        if (newPath != null) updated = updated.copy(path = normalizedPath(before.backend, newPath))
        if (newParams != null) updated = updated.copy(params = mergedParams(before.params, newParams))
        if (allowInsecureTLS != null) updated = updated.copy(allowInsecureTLS = allowInsecureTLS)

        val currentSecret = loadSecret(name)
        val nextSecret = resolveSecret(currentSecret, secret)
        verify?.invoke(updated, nextSecret)

        val edit = secrets.edit()
        if (targetName != null) edit.remove(name)
        if (nextSecret.isNullOrEmpty()) edit.remove(updated.name) else edit.putString(updated.name, nextSecret)
        edit.apply()
        remotes = all.toMutableList().also { it[i] = updated }
        if (targetName != null) synchronized(syncLock) { syncedFingerprints.remove(name) }
        AppLogger.info(TAG, "[Rclone] remote updated: $name -> ${updated.name}:/${updated.path}")
        return updated
    }

    private fun validateRename(newName: String, local: Boolean) {
        if (newName.isEmpty()) throw StoreException("Choose a name for this destination.")
        if (!local && !newName.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw StoreException("Choose a name using letters, numbers, - or _.")
        }
    }

    fun hasSecret(name: String): Boolean = !loadSecret(name).isNullOrEmpty()


    private fun storeSecret(name: String, secret: String) {
        secrets.edit().putString(name, secret).apply()
    }

    private fun loadSecret(name: String): String? = secrets.getString(name, null)

    private fun secretKey(backend: String): String = secretKeyFor(backend)


    fun syncToRclone() = synchronized(syncLock) {
        val configPath = File(context.cacheDir, "rclone-ephemeral.conf").absolutePath
        runCatching { RcloneBridge.rpc("config/setpath", mapOf("path" to configPath)) }

        RcloneBridge.setInsecureTLS(remotes.any { it.allowInsecureTLS })

        for (r in remotes.filterNot { it.backend == BACKEND_LOCAL }) {
            val params = buildConfigParams(
                backend = r.backend,
                params = r.params,
                secret = loadSecret(r.name),
                allowInsecureTLS = r.allowInsecureTLS,
                obscure = ::obscureViaRclone,
            ).toMutableMap<String, Any?>()
            val fp = configFingerprint(r.backend, r.params, loadSecret(r.name), r.allowInsecureTLS)
            if (syncedFingerprints[r.name] == fp) continue
            syncedFingerprints.remove(r.name)
            runCatching {
                RcloneBridge.rpc(
                    "config/create",
                    mapOf(
                        "name" to r.name,
                        "type" to r.backend,
                        "parameters" to params,
                        "opt" to mapOf("nonInteractive" to true),
                    ),
                )
            }.onSuccess {
                syncedFingerprints[r.name] = fp
            }.onFailure {
                AppLogger.error(TAG, "[Rclone] config/create failed for '${r.name}': ${it.message}")
            }
        }
        AppLogger.info(TAG, "[Rclone] synced ${remotes.size} remote(s) into rclone config")
    }

    fun probeConnection(candidate: Remote, secret: String?) {
        val probe = candidate.copy(name = "${candidate.name}$PROBE_SUFFIX")
        try {
            RcloneBridge.setInsecureTLS(candidate.allowInsecureTLS || remotes.any { it.allowInsecureTLS })
            RcloneBridge.rpc(
                "config/create",
                mapOf(
                    "name" to probe.name,
                    "type" to probe.backend,
                    "parameters" to buildConfigParams(
                        backend = probe.backend,
                        params = probe.params,
                        secret = secret,
                        obscure = ::obscureViaRclone,
                        allowInsecureTLS = probe.allowInsecureTLS,
                    ),
                    "opt" to mapOf("nonInteractive" to true),
                ),
            )
            RcloneBrowser.listDirectories(probe, probe.listRoot)
        } finally {
            dropRcloneSection(probe.name)
            RcloneBridge.setInsecureTLS(remotes.any { it.allowInsecureTLS })
        }
    }

    fun dropRcloneSection(name: String) {
        runCatching { RcloneBridge.rpc("config/delete", mapOf("name" to name)) }
        synchronized(syncLock) { syncedFingerprints.remove(name) }
    }

    private fun obscureViaRclone(clear: String): String? = runCatching {
        RcloneBridge.rpc("core/obscure", mapOf("clear" to clear))
            .optString("obscured").takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        private const val TAG = "Rclone"

        private val syncLock = Any()

        private val syncedFingerprints = HashMap<String, String>()

        internal fun configFingerprint(
            backend: String,
            params: Map<String, String>,
            secret: String?,
            allowInsecureTLS: Boolean,
        ): String {
            val canonical = buildString {
                append(backend).append('\u0000')
                for ((k, v) in params.toSortedMap()) append(k).append('=').append(v).append('\u0000')
                append(if (secret == null) "-" else "+" + secret).append('\u0000')
                append(allowInsecureTLS)
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        const val BACKEND_LOCAL = "local-folder"

        const val PARAM_TREE_URI = "treeUri"

        fun isLocalFolder(backend: String): Boolean = backend == BACKEND_LOCAL

        fun mergedParams(old: Map<String, String>, edits: Map<String, String>): Map<String, String> {
            val out = old.toMutableMap()
            for ((k, v) in edits) {
                val t = v.trim()
                if (t.isEmpty()) out.remove(k) else out[k] = t
            }
            return out
        }

        fun resolveSecret(current: String?, edit: SecretEdit): String? = when (edit) {
            SecretEdit.Keep -> current
            SecretEdit.Clear -> null
            is SecretEdit.Replace -> edit.value.ifEmpty { current }
        }

        fun normalizedPath(backend: String, path: String): String {
            if (isLocalFolder(backend)) return path
            val trimmed = path.trim()
            if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                return if (trimmed.length > 1 && trimmed.endsWith("/")) trimmed.dropLast(1) else trimmed
            }
            return trimmed.trim('/')
        }
        internal const val PROBE_SUFFIX = ".edit-probe"
        private const val PREFS_NAME = "backup_rclone_remotes"
        private const val SECRETS_PREFS_NAME = "backup_rclone_secrets"
        private const val KEY_REMOTES = "remotes"

        fun secretKeyFor(backend: String): String = when (backend) {
            "s3" -> "secret_access_key"
            else -> "pass"
        }

        fun secretNeedsObscuring(backend: String): Boolean = backend != "s3"

        fun buildConfigParams(
            backend: String,
            params: Map<String, String>,
            secret: String?,
            obscure: (String) -> String?,
            allowInsecureTLS: Boolean = false,
        ): Map<String, Any?> {
            val out = params.toMutableMap<String, Any?>()
            out["type"] = backend

            if (backend == "ftp" && allowInsecureTLS) out["no_check_certificate"] = "true"

            if (backend == "smb") out.remove("share")

            if (backend == "smb" && (params["user"] ?: "").isBlank()) out["user"] = ""

            val hasSecret = !secret.isNullOrEmpty()

            if (backend != "s3" && !hasSecret && (out["pass"] as? String).isNullOrEmpty()) {
                obscure("")?.let { out["pass"] = it }
                return out
            }

            if (!hasSecret) {
                out.remove(secretKeyFor(backend))
                return out
            }

            out[secretKeyFor(backend)] =
                if (secretNeedsObscuring(backend)) obscure(secret!!) ?: secret else secret
            return out
        }
        fun looksLikeCertificateFailure(message: String?): Boolean {
            val m = message?.lowercase() ?: return false
            return m.contains("x509") ||
                m.contains("certificate") ||
                m.contains("tls: failed to verify") ||
                m.contains("unknown authority") ||
                m.contains("self-signed") ||
                m.contains("self signed")
        }

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val REMOTE_LIST = ListSerializer(Remote.serializer())
    }
}
