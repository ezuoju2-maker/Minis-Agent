package com.openminis.app.provider.openai

internal class ThinkPrefixStreamParser {

    private enum class State { UNDECIDED, THINKING, BODY }

    data class Output(val visible: String, val thinking: String)

    private var state = State.UNDECIDED

    private val pending = StringBuilder()

    private val heldWhitespace = StringBuilder()

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }

    fun feed(text: String): Output {
        if (text.isEmpty()) return Output("", "")
        val visible = StringBuilder()
        val thinking = StringBuilder()
        pending.append(text)

        loop@ while (pending.isNotEmpty()) {
            when (state) {
                State.UNDECIDED -> {
                    val firstNonSpace = pending.indexOfFirst { !it.isWhitespace() }
                    if (firstNonSpace < 0) {
                        break@loop
                    }
                    val rest = pending.substring(firstNonSpace)
                    if (OPEN.startsWith(rest.take(OPEN.length))) {
                        if (rest.length < OPEN.length) break@loop
                    }
                    if (rest.startsWith(OPEN)) {
                        state = State.THINKING
                        pending.delete(0, firstNonSpace + OPEN.length)
                    } else {
                        state = State.BODY
                    }
                }

                State.THINKING -> {
                    val closeIdx = pending.indexOf(CLOSE)
                    if (closeIdx < 0) {
                        val safe = safeEmitLength(pending, CLOSE)
                        if (safe <= 0) break@loop
                        thinking.append(pending, 0, safe)
                        pending.delete(0, safe)
                        break@loop
                    }
                    thinking.append(pending, 0, closeIdx)
                    pending.delete(0, closeIdx + CLOSE.length)
                    state = State.BODY
                    while (pending.isNotEmpty() && pending[0].isWhitespace()) pending.deleteCharAt(0)
                }

                State.BODY -> {
                    var cut = pending.length
                    while (cut > 0 && pending[cut - 1].isWhitespace()) cut--
                    if (cut > 0) {
                        visible.append(heldWhitespace)
                        heldWhitespace.setLength(0)
                        visible.append(pending, 0, cut)
                    }
                    heldWhitespace.append(pending, cut, pending.length)
                    pending.setLength(0)
                    break@loop
                }
            }
        }
        return Output(visible.toString(), thinking.toString())
    }

    fun finishTurn(): Output {
        val visible = StringBuilder()
        val thinking = StringBuilder()
        if (pending.isNotEmpty()) {
            when (state) {
                State.THINKING -> thinking.append(pending)
                State.UNDECIDED, State.BODY -> visible.append(pending)
            }
            pending.setLength(0)
        }
        heldWhitespace.setLength(0)
        return Output(visible.toString(), thinking.toString())
    }

    fun resolveAtToolBoundary(): Output {
        if (state != State.UNDECIDED || pending.isEmpty()) return Output("", "")
        val rest = pending.trimStart()
        if (rest.isNotEmpty() && OPEN.startsWith(rest.take(OPEN.length)) && rest.length < OPEN.length) {
            return Output("", "")
        }
        state = State.BODY
        val out = pending.toString()
        pending.setLength(0)
        return Output(out, "")
    }

    private fun safeEmitLength(buf: StringBuilder, tag: String): Int {
        val maxKeep = minOf(tag.length - 1, buf.length)
        for (keep in maxKeep downTo 1) {
            val suffixStart = buf.length - keep
            var matches = true
            for (k in 0 until keep) {
                if (buf[suffixStart + k] != tag[k]) { matches = false; break }
            }
            if (matches) return suffixStart
        }
        return buf.length
    }
}

private inline fun CharSequence.indexOfFirst(predicate: (Char) -> Boolean): Int {
    for (i in indices) if (predicate(this[i])) return i
    return -1
}
