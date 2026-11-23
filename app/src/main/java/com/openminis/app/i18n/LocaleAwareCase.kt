package com.openminis.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

@Composable
fun String.uppercaseForDisplay(): String {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        @Suppress("DEPRECATION")
        configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
    return remember(this, locale) { uppercase(locale) }
}
