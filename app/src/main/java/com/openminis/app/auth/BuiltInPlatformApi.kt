package com.openminis.app.auth

data class BuiltInAccount(
    val platform: String,
    val accessToken: String,
    val refreshToken: String?,
    val uid: String,
    val nickname: String = "",
    val expiresAt: Long = 0L,
    val region: String = BuiltInRegion.DEFAULT,
    val extra: Map<String, String> = emptyMap(),
)

data class BuiltInRegion(val id: String, val label: String) {
    companion object {
        val CN = BuiltInRegion("cn", "国内版")
        val INTL = BuiltInRegion("intl", "国际版")
        const val DEFAULT = "cn"

        val SINGLE = BuiltInRegion(DEFAULT, "")
    }
}

data class BuiltInLoginStart(
    val authUrl: String,
    val userCode: String? = null,
    val session: BuiltInAccount = BuiltInAccount("", "", null, ""),
)

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
    val recommended: Boolean = false,
    val tools: Boolean = false,
    val onlyReasoning: Boolean = false,
)

data class BuiltInModels(val models: List<BuiltInModel>, val error: String? = null)

data class CheckinDay(
    val dayNo: Int,
    val points: Int,
    val bonusPoints: Int = 0,
    val isToday: Boolean = false,
    val state: String,
)

data class BuiltInCheckin(
    val days: List<CheckinDay> = emptyList(),
    val streak: Int = 0,
    val canClaim: Boolean = false,
    val claimedToday: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

interface BuiltInPlatformApi {
    val supportsCheckin: Boolean get() = false
    val displayName: String

    val regions: List<BuiltInRegion> get() = listOf(BuiltInRegion.SINGLE)

    suspend fun beginLogin(region: String = BuiltInRegion.DEFAULT): BuiltInLoginStart

    val pollNote: String? get() = null

    suspend fun pollLogin(state: BuiltInAccount): BuiltInAccount?

    suspend fun refresh(account: BuiltInAccount): BuiltInAccount?

    suspend fun fetchModels(account: BuiltInAccount): BuiltInModels

    suspend fun fetchQuota(account: BuiltInAccount): List<Pair<String, String>> = emptyList()

    suspend fun fetchCheckin(account: BuiltInAccount): BuiltInCheckin = BuiltInCheckin()

    suspend fun claimCheckin(account: BuiltInAccount): BuiltInCheckin = BuiltInCheckin()
}
