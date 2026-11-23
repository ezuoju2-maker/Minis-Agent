package com.openminis.app.backup

import java.text.BreakIterator
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object BackupPackageName {

    fun packageFileName(
        backupId: String,
        atMillis: Long,
        deviceName: String,
        encrypted: Boolean = false,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val device = filenameDeviceToken(deviceName)
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(atMillis)
        val suffix = if (encrypted) "-encrypted" else ""
        return "$device-$stamp-${sortableId(backupId, atMillis)}$suffix.${BackupFormat.FILE_EXTENSION}"
    }

    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    fun sortableId(backupId: String, atMillis: Long): String {
        val ms = atMillis.coerceAtLeast(0L) and 0xFF_FFFF_FFFFL
        var h = -0x340d631b7bdddcdbL
        for (b in backupId.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
        }
        return encode(ms, 8) + encode(h, 3)
    }

    private fun encode(value: Long, width: Int): String {
        val out = CharArray(width)
        var v = value
        for (i in width - 1 downTo 0) {
            out[i] = ALPHABET[(v and 31).toInt()]
            v = v ushr 5
        }
        return String(out)
    }

    fun filenameDeviceToken(raw: String, fallback: String = "device"): String {
        val out = StringBuilder()
        var lastWasSeparator = false
        val it = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(raw) }
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val g = raw.substring(start, end)
            val c = g[0]
            when {
                g.length == 1 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') -> {
                    out.append(c)
                    lastWasSeparator = false
                }
                g == "'" || g == "’" -> Unit
                out.isNotEmpty() && !lastWasSeparator -> {
                    out.append('-')
                    lastWasSeparator = true
                }
            }
            start = end
            end = it.next()
        }
        var token = out.toString().trimEnd('-')
        if (token.length > 24) token = token.take(24).trimEnd('-')
        return token.ifEmpty { fallback }
    }
}
