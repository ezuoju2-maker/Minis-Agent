package com.openminis.app.speech

internal enum class VadManualStopOutcome {
    TRANSCRIBE,

    NO_MATCH,

    NOTHING_TO_DO,
    ;

    companion object {
        fun decide(hasSession: Boolean, heldSeconds: Float, floorSeconds: Float): VadManualStopOutcome = when {
            !hasSession -> NOTHING_TO_DO
            heldSeconds > floorSeconds -> TRANSCRIBE
            else -> NO_MATCH
        }
    }
}
