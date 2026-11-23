package com.openminis.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.view.View
import com.openminis.app.ui.settings.KEY_LANGUAGE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import java.util.Locale

object LocaleWrap {

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return forceLtr(base)
        val code = base
            .getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "")
            .orEmpty()
        if (code.isEmpty()) return forceLtr(base)
        val locale = parseLocale(code) ?: return forceLtr(base)
        val config = Configuration(base.resources.configuration)
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = LocaleList(locale)
            LocaleList.setDefault(list)
            config.setLocales(list)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        config.setLayoutDirection(Locale.ENGLISH)
        return base.createConfigurationContext(config)
    }

    private fun forceLtr(base: Context): Context {
        if (base.resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL) return base
        val ltr = Configuration().apply {
            fontScale = 0f
            setLayoutDirection(Locale.ENGLISH)
        }
        return base.createConfigurationContext(ltr)
    }

    private fun parseLocale(code: String): Locale? {
        if (code.isBlank()) return null
        return if ('-' in code || '_' in code) {
            Locale.forLanguageTag(code.replace('_', '-'))
        } else {
            Locale(code)
        }
    }
}
