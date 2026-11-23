package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

enum class SpeedTier(val storageValue: String, val wireValue: String?) {
    STANDARD("off", null),
    FAST("fast", "priority"),
    ULTRAFAST("ultrafast", "ultrafast"),
    ;

    companion object {
        fun fromStorage(value: String?): SpeedTier? = entries.firstOrNull { it.storageValue == value }
    }
}

object FastModePrefs {
    private const val PREFS = "minis_fast_mode_prefs"
    private const val KEY_LEGACY_ENABLED = "codexFastModeEnabled"
    private const val KEY_TIER = "codexSpeedTier"
    private const val KEY_CATALOG_ULTRAFAST = "codexCatalogUltrafastModels"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedTier: SpeedTier = SpeedTier.STANDARD

    @Volatile
    private var cachedCatalogUltrafast: List<String> = emptyList()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun resolveStoredTier(storedTier: String?, legacyEnabled: Boolean?): SpeedTier =
        SpeedTier.fromStorage(storedTier)
            ?: if (legacyEnabled == true) SpeedTier.FAST else SpeedTier.STANDARD

    fun prime(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        val stored = p.getString(KEY_TIER, null)
        val legacy = if (p.contains(KEY_LEGACY_ENABLED)) p.getBoolean(KEY_LEGACY_ENABLED, false) else null
        val tier = resolveStoredTier(stored, legacy)
        cachedTier = tier
        cachedCatalogUltrafast = p.getString(KEY_CATALOG_ULTRAFAST, null).orEmpty()
            .split(',').filter { it.isNotBlank() }
        if (stored == null) {
            p.edit().putString(KEY_TIER, tier.storageValue).apply()
        }
    }

    fun tier(): SpeedTier = cachedTier

    fun isEnabled(): Boolean = cachedTier != SpeedTier.STANDARD

    fun setTier(context: Context, tier: SpeedTier) {
        cachedTier = tier
        prefs(context).edit()
            .putString(KEY_TIER, tier.storageValue)
            .putBoolean(KEY_LEGACY_ENABLED, tier != SpeedTier.STANDARD)
            .apply()
    }

    fun catalogUltrafastIds(): List<String> = cachedCatalogUltrafast

    fun recordCatalogUltrafast(context: Context?, ids: List<String>) {
        val lowered = ids.map { it.lowercase() }.distinct()
        cachedCatalogUltrafast = lowered
        val ctx = context ?: appContext ?: return
        prefs(ctx).edit().putString(KEY_CATALOG_ULTRAFAST, lowered.joinToString(",")).apply()
    }

    @androidx.annotation.VisibleForTesting
    internal fun setCachedEnabledForTest(enabled: Boolean) {
        cachedTier = if (enabled) SpeedTier.FAST else SpeedTier.STANDARD
    }

    @androidx.annotation.VisibleForTesting
    internal fun setCachedTierForTest(tier: SpeedTier) {
        cachedTier = tier
    }
}

object SpeedTiers {
    internal val ULTRAFAST_MODEL_PREFIXES = listOf("gpt-6-astra")

    fun supportsUltrafast(modelId: String): Boolean {
        val id = modelId.lowercase()
        return ULTRAFAST_MODEL_PREFIXES.any { id.startsWith(it) }
    }

    fun catalogDeclaresUltrafast(
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): Boolean =
        model.serviceTiers?.any { it == SpeedTier.ULTRAFAST.wireValue } == true ||
            model.id.lowercase() in catalogIds

    fun supportsUltrafast(
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): Boolean = catalogDeclaresUltrafast(model, catalogIds) || supportsUltrafast(model.id)

    fun effective(
        chosen: SpeedTier,
        model: com.openminis.app.data.model.LLMModel,
        ultrafastPathAllowed: Boolean = true,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): SpeedTier =
        if (chosen == SpeedTier.ULTRAFAST && !(ultrafastPathAllowed && supportsUltrafast(model, catalogIds))) {
            SpeedTier.FAST
        } else {
            chosen
        }

    fun ultrafastUnsupportedNote(
        chosen: SpeedTier,
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): String? {
        if (chosen != SpeedTier.ULTRAFAST || !model.id.contains("gpt", ignoreCase = true)) return null
        val declared = catalogIds.toList()
        val sent = effective(chosen, model, catalogIds = catalogIds).wireValue ?: "none"
        if (!supportsUltrafast(model, catalogIds)) {
            return "Ultrafast not supported for model=${model.id} (whitelist=$ULTRAFAST_MODEL_PREFIXES, " +
                "catalogue declares=$declared); sending service_tier=$sent"
        }
        if (!catalogDeclaresUltrafast(model, catalogIds)) {
            return "Ultrafast for model=${model.id} comes from the fallback whitelist only; the account catalogue " +
                "declares it for $declared - an account without the entitlement is likely served at Standard " +
                "(service_tier=$sent)"
        }
        return null
    }

    fun displayed(chosen: SpeedTier, fastEligible: Boolean, ultrafastEligible: Boolean): SpeedTier = when {
        !fastEligible -> SpeedTier.STANDARD
        chosen == SpeedTier.ULTRAFAST && !ultrafastEligible -> SpeedTier.FAST
        else -> chosen
    }
}
