package com.openminis.app.ui.chat

internal object ProviderAvailability {

    enum class Problem { REMOVED, DISABLED }

    data class Identity(val entryId: String, val instanceId: String, val instanceLabel: String, val modelName: String)

    fun problem(instanceExists: Boolean, instanceEnabled: Boolean): Problem? = when {
        !instanceExists -> Problem.REMOVED
        !instanceEnabled -> Problem.DISABLED
        else -> null
    }
}
