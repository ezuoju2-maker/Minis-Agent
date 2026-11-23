package com.openminis.app.auth

import android.content.Context
import android.util.Log
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-builtin-accounts] Persistence + token lifecycle for account-login platforms
 * that are not part of the generic OAuth providers (Qoder, MiniMax Code).
 *
 * Stored in the same encrypted prefs file the OAuth managers use, one entry per
 * **platform + region**, so a single "sign out" clears exactly one account and
 * the domestic / international sites of the same brand never overwrite each
 * other (their tokens are not interchangeable).
 */
object BuiltInAccountStore {
    private const val TAG = "BuiltInAccountStore"
    private const val PREFS = "oauth_prefs"

    fun api(platform: String): BuiltInPlatformApi? = when (platform) {
        "qoder" -> QoderApi
        "minimax" -> MiniMaxCodeApi
        else -> null
    }

    fun regions(platform: String): List<BuiltInRegion> =
        api(platform)?.regions ?: listOf(BuiltInRegion.SINGLE)

    private fun prefs(context: Context) =
        EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS)

    private fun key(platform: String, region: String) = "builtin_account_${platform}_$region"
    private fun activeKey(platform: String) = "builtin_region_$platform"

    /** Region the chat provider should use for this platform (last one visited). */
    fun activeRegion(context: Context, platform: String): String {
        val stored = prefs(context).getString(activeKey(platform), null)
        val regions = regions(platform)
        if (stored != null && regions.any { it.id == stored }) return stored
        return regions.firstOrNull()?.id ?: BuiltInRegion.DEFAULT
    }

    fun setActiveRegion(context: Context, platform: String, region: String) {
        prefs(context).edit().putString(activeKey(platform), region).apply()
    }

    /** True when at least one site of this platform has a stored account. */
    fun hasAnyAccount(context: Context, platform: String): Boolean =
        regions(platform).any { load(context, platform, it.id) != null }

    fun save(context: Context, account: BuiltInAccount) {
        val json = JSONObject().apply {
            put("platform", account.platform)
            put("region", account.region)
            put("access_token", account.accessToken)
            account.refreshToken?.let { put("refresh_token", it) }
            put("uid", account.uid)
            put("nickname", account.nickname)
            put("expires_at", account.expiresAt)
            account.extra.forEach { (k, v) -> put("x_$k", v) }
        }
        prefs(context).edit()
            .putString(key(account.platform, account.region), json.toString())
            .putString(activeKey(account.platform), account.region)
            .apply()
    }

    fun load(context: Context, platform: String, region: String = BuiltInRegion.DEFAULT): BuiltInAccount? {
        val raw = prefs(context).getString(key(platform, region), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val token = json.optString("access_token", "")
        if (token.isBlank()) return null
        val extra = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("x_")) extra[k.removePrefix("x_")] = json.optString(k, "")
        }
        return BuiltInAccount(
            platform = platform,
            accessToken = token,
            refreshToken = json.optString("refresh_token", "").ifEmpty { null },
            uid = json.optString("uid", ""),
            nickname = json.optString("nickname", ""),
            expiresAt = json.optLong("expires_at", 0L),
            region = json.optString("region", region).ifBlank { region },
            extra = extra,
        )
    }

    fun clear(context: Context, platform: String, region: String = BuiltInRegion.DEFAULT) {
        prefs(context).edit().remove(key(platform, region)).apply()
    }

    /**
     * A usable bearer for the chat request: returns the stored token of the
     * active region, refreshing first when it is within two minutes of expiry.
     * Null when signed out.
     */
    suspend fun validToken(context: Context, platform: String): String? = withContext(Dispatchers.IO) {
        val api = api(platform) ?: return@withContext null
        val regions = regions(platform)
        val region = activeRegion(context, platform).let { active ->
            if (load(context, platform, active) != null) active
            else regions.firstOrNull { load(context, platform, it.id) != null }?.id ?: active
        }
        val account = load(context, platform, region) ?: return@withContext null
        val soon = account.expiresAt > 0 && account.expiresAt - System.currentTimeMillis() < 120_000
        if (!soon) return@withContext account.accessToken
        val refreshed = runCatching { api.refresh(account) }.getOrNull()
        if (refreshed == null) {
            Log.w(TAG, "$platform token refresh failed; keeping current token")
            return@withContext account.accessToken
        }
        save(context, refreshed)
        refreshed.accessToken
    }
}
