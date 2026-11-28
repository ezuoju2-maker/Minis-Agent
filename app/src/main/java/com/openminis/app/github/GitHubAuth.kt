package com.openminis.app.github

import android.content.Context

class GitHubAuth(context: Context) {

    data class Session(
        val token: String,
        val login: String,
        val name: String,
        val scopes: List<String>,
        val kind: String,
        val savedAt: Long,
    )

    private val appContext = context.applicationContext

    private fun prefs() = com.openminis.app.util.EncryptedPrefsFactory
        .safeCreate(appContext, PREFS)

    fun session(): Session? {
        val p = prefs()
        val token = p.getString(KEY_TOKEN, null) ?: return null
        if (token.isBlank()) return null
        val scopes = p.getString(KEY_SCOPES, "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        return Session(
            token = token,
            login = p.getString(KEY_LOGIN, "") ?: "",
            name = p.getString(KEY_NAME, "") ?: "",
            scopes = scopes,
            kind = p.getString(KEY_KIND, "unknown") ?: "unknown",
            savedAt = p.getLong(KEY_SAVED_AT, 0L),
        )
    }

    fun save(token: String, login: String, name: String, scopes: List<String>, kind: String) {
        prefs().edit()
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_LOGIN, login)
            .putString(KEY_NAME, name)
            .putString(KEY_SCOPES, scopes.joinToString(","))
            .putString(KEY_KIND, kind)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() = prefs().edit().clear().apply()

    companion object {
        const val PREFS = "github_prefs"
        private const val KEY_TOKEN = "github.token"
        private const val KEY_LOGIN = "github.login"
        private const val KEY_NAME = "github.name"
        private const val KEY_SCOPES = "github.scopes"
        private const val KEY_KIND = "github.kind"
        private const val KEY_SAVED_AT = "github.savedAt"

        const val MAX_SCOPES_HINT =
            "repo, workflow, admin:org, admin:repo_hook, admin:public_key, admin:gpg_key, " +
                "admin:ssh_signing_key, admin:org_hook, user, project, gist, notifications, delete_repo, " +
                "write:packages, read:packages, delete:packages, read:audit_log, codespace"

        fun token(context: Context): String? = GitHubAuth(context).session()?.token
    }
}
