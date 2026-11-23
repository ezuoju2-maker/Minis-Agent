package com.openminis.app.ui.chat

internal object ComposerPlaceholderRotation {

    const val DEFAULT_INDEX = 0

    const val HINT_COUNT = 5
    const val POOL_SIZE = HINT_COUNT + 1

    fun nextIndex(
        current: Int,
        hasFocusedBefore: Boolean,
        sessionHasMessages: Boolean,
        screenReaderOn: Boolean,
        randomIndex: (Int) -> Int,
        poolSize: Int = POOL_SIZE,
    ): Int {
        if (screenReaderOn) return current

        if (!hasFocusedBefore && !sessionHasMessages) return current

        if (current !in 0 until poolSize) return randomIndex(poolSize)
        val draw = randomIndex(poolSize - 1)
        return if (draw >= current) draw + 1 else draw
    }
}
