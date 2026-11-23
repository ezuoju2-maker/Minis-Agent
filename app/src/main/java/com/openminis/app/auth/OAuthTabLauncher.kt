package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

object OAuthTabLauncher {
    fun launch(context: Context, uri: Uri, newTask: Boolean = false) {
        val tabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        if (newTask) tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tabs.launchUrl(context, uri)
    }
}
