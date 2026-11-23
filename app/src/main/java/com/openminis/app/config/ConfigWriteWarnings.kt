package com.openminis.app.config

object ConfigWriteWarnings {
    private val current = ThreadLocal<MutableList<String>?>()

    fun add(message: String) {
        current.get()?.add(message)
    }

    fun <T> collect(block: () -> T): Pair<T, List<String>> {
        val previous = current.get()
        val list = ArrayList<String>()
        current.set(list)
        try {
            return block() to list.distinct()
        } finally {
            current.set(previous)
        }
    }
}
