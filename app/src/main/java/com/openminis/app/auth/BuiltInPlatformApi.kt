package com.openminis.app.auth

/**
 * [T-builtin-accounts] Shared shape for "account login" built-in platforms that
 * are reverse-engineered from an official desktop/CLI client (Qoder, MiniMax
 * Code). They all follow the same lifecycle:
 *
 *   beginLogin() -> open a URL in the system browser -> pollLogin() -> account
 *
 * so the UI, the storage and the provider plumbing are written once against
 * this interface instead of per vendor.
 */
data class BuiltInAccount(
    val platform: String,
    val accessToken: String,
    val refreshToken: String?,
    val uid: String,
    val nickname: String = "",
    /** Epoch millis; 0 = unknown (refresh lazily on 401). */
    val expiresAt: Long = 0L,
    /**
     * Site variant this account belongs to (see [BuiltInRegion]).
     *
     * Several vendors run two entirely separate sites (qoder.com.cn vs
     * qoder.com, minimax.cn vs minimax.io). Accounts and tokens are NOT
     * interchangeable, so the region is part of the account identity — it is
     * persisted with the token and selects the hosts used by every request.
     */
    val region: String = BuiltInRegion.DEFAULT,
    /** Platform-specific login state needed by the poller (verifier, nonce, …). */
    val extra: Map<String, String> = emptyMap(),
)

/**
 * One site variant of a brand.
 *
 * Vendors that only have a single site expose [SINGLE]; the region is then a
 * constant so the storage layer, the UI and the API all stay uniform.
 */
data class BuiltInRegion(val id: String, val label: String) {
    companion object {
        /** Domestic / China site — always listed first, matching user intuition. */
        val CN = BuiltInRegion("cn", "国内版")
        val INTL = BuiltInRegion("intl", "国际版")
        const val DEFAULT = "cn"

        /** Placeholder for single-site platforms (WorkBuddy keeps its own field). */
        val SINGLE = BuiltInRegion(DEFAULT, "")
    }
}

/** What the UI needs to show after [BuiltInPlatformApi.beginLogin]. */
data class BuiltInLoginStart(
    val authUrl: String,
    /** Shown to the user when the flow is a device code (MiniMax). */
    val userCode: String? = null,
    /** Opaque state handed back to [BuiltInPlatformApi.pollLogin]. */
    val session: BuiltInAccount = BuiltInAccount("", "", null, ""),
)

/** One catalogue row. Fields mirror what the upstream actually returns. */
data class BuiltInModel(
    val id: String,
    val name: String,
    val vendor: String? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val free: Boolean = false,
    val priceFactor: Double? = null,
    /** Capability badges, mirrored from the upstream flags when present. */
    val recommended: Boolean = false,
    val tools: Boolean = false,
    val onlyReasoning: Boolean = false,
)

data class BuiltInModels(val models: List<BuiltInModel>, val error: String? = null)

/** One day in a platform's daily check-in calendar. */
data class CheckinDay(
    val dayNo: Int,
    val points: Int,
    val bonusPoints: Int = 0,
    val isToday: Boolean = false,
    /** claimed / claimable / upcoming / disabled */
    val state: String,
)

/** Result of the daily check-in flow, shown in the platform page. */
data class BuiltInCheckin(
    val days: List<CheckinDay> = emptyList(),
    val streak: Int = 0,
    val canClaim: Boolean = false,
    val claimedToday: Boolean = false,
    /** Human note: what happened, or why check-in is unavailable here. */
    val message: String? = null,
    val error: String? = null,
)

/** Whether a platform exposes a daily check-in button. */
interface BuiltInPlatformApi {
    val supportsCheckin: Boolean get() = false
    /** Human name, used in error text. */
    val displayName: String

    /**
     * Sites this platform runs, domestic first. Single-site platforms keep the
     * default ([BuiltInRegion.SINGLE]) and the UI hides the switcher.
     */
    val regions: List<BuiltInRegion> get() = listOf(BuiltInRegion.SINGLE)

    suspend fun beginLogin(region: String = BuiltInRegion.DEFAULT): BuiltInLoginStart

    /**
     * Human-readable state of the last [pollLogin] call ("waiting for the
     * browser", or the HTTP code that came back). Shown under the spinner so a
     * stalled authorisation is never silent.
     */
    val pollNote: String? get() = null

    /** Returns the account once the user finished authorizing, null while pending. */
    suspend fun pollLogin(state: BuiltInAccount): BuiltInAccount?

    suspend fun refresh(account: BuiltInAccount): BuiltInAccount?

    suspend fun fetchModels(account: BuiltInAccount): BuiltInModels

    /** Credits / plan summary, rendered as key-value rows. Empty when unsupported. */
    suspend fun fetchQuota(account: BuiltInAccount): List<Pair<String, String>> = emptyList()

    /** Fetch the daily check-in calendar/status (no claim). */
    suspend fun fetchCheckin(account: BuiltInAccount): BuiltInCheckin = BuiltInCheckin()

    /** Perform the daily check-in (claim today's reward). */
    suspend fun claimCheckin(account: BuiltInAccount): BuiltInCheckin = BuiltInCheckin()
}
