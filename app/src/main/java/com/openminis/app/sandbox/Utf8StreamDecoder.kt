package com.openminis.app.sandbox

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class Utf8StreamDecoder {

    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val input = ByteBuffer.allocate(pending.size + length)
        input.put(pending).put(bytes, offset, length).flip()
        val out = CharBuffer.allocate(input.remaining())
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        return out.flip().toString()
    }

    fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val out = CharBuffer.allocate(pending.size + 1)
        decoder.decode(input, out, true)
        decoder.flush(out)
        decoder.reset()
        pending = ByteArray(0)
        return out.flip().toString()
    }
}
