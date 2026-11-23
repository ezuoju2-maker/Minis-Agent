package com.openminis.app.provider

object ToolPairing {
    fun key(id: String): String {
        val sep = id.indexOf('|')
        return if (sep < 0) id else id.substring(0, sep)
    }
}
