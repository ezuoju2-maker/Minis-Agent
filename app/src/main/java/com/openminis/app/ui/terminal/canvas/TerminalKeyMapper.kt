package com.openminis.app.ui.terminal.canvas

internal object TerminalKeyMapper {

    fun controlCode(ch: Char): Byte? = when (ch) {
        in 'a'..'z', in 'A'..'Z' -> (ch.code and 0x1F).toByte()
        '@', '2' -> 0x00
        '[', '3' -> 0x1B
        '\\', '4' -> 0x1C
        ']', '5' -> 0x1D
        '^', '6' -> 0x1E
        '_', '-', '/', '7' -> 0x1F
        '?', '8' -> 0x7F
        else -> null
    }

    fun arrow(dir: Char, applicationCursorKeys: Boolean, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray {
        var mod = 1
        if (shift) mod += 1
        if (alt) mod += 2
        if (ctrl) mod += 4
        if (mod == 1) {
            val intro = if (applicationCursorKeys) 'O' else '['
            return byteArrayOf(0x1B, intro.code.toByte(), dir.code.toByte())
        }
        return "\u001B[1;$mod$dir".toByteArray(Charsets.US_ASCII)
    }

    fun functionKey(n: Int): ByteArray? {
        val seq = when (n) {
            1 -> "\u001BOP"
            2 -> "\u001BOQ"
            3 -> "\u001BOR"
            4 -> "\u001BOS"
            5 -> "\u001B[15~"
            6 -> "\u001B[17~"
            7 -> "\u001B[18~"
            8 -> "\u001B[19~"
            9 -> "\u001B[20~"
            10 -> "\u001B[21~"
            11 -> "\u001B[23~"
            12 -> "\u001B[24~"
            else -> return null
        }
        return seq.toByteArray(Charsets.US_ASCII)
    }
}
