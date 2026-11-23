package com.openminis.app.util

import android.content.Context

object CopilotFeatureFlag {

    private const val PREFS = "feature_flags"

    @Suppress("unused")
    private const val KEY = "copilotProviderEnabled"

    private const val KEY_CONSENT = "copilotSignInConsentAccepted"

    fun hasAcceptedConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSENT, false)

    fun recordConsent(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONSENT, true).apply()
    }
}
