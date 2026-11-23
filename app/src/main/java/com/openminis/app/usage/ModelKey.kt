package com.openminis.app.usage

import java.util.Locale

object ModelKey {
    private val CHANNEL_PREFIX = Regex("^[a-z]{2,3}:(?=[a-z][a-z0-9.]*-)")
    private val DATE_SUFFIX = Regex("(-20\\d{6}|-20\\d\\d-\\d\\d-\\d\\d|@20\\d{6}|-(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01]))$")
    private val LATEST_SUFFIX = Regex("-latest$")
    private val DOT_BETWEEN_DIGITS = Regex("(?<=\\d)\\.(?=\\d)")

    fun merged(id: String): String {
        var s = id.trim().lowercase(Locale.ROOT).substringAfterLast('/')
        s = CHANNEL_PREFIX.replaceFirst(s, "")
        s = DATE_SUFFIX.replaceFirst(s, "")
        s = LATEST_SUFFIX.replaceFirst(s, "")
        return DOT_BETWEEN_DIGITS.replace(s, "-")
    }

    fun exact(id: String): String = id.trim()

    fun of(id: String, exactModelId: Boolean): String = if (exactModelId) exact(id) else merged(id)
}
