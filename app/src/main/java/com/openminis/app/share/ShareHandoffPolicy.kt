package com.openminis.app.share

object ShareHandoffPolicy {

    enum class Outcome {
        LAUNCHED,

        LAUNCHED_VIA_FALLBACK,

        FAILED,
    }

    fun handOff(
        primary: () -> Unit,
        fallback: () -> Unit,
        onError: (stage: String, error: Throwable) -> Unit = { _, _ -> },
    ): Outcome {
        try {
            primary()
            return Outcome.LAUNCHED
        } catch (t: Throwable) {
            report(onError, "primary", t)
        }

        try {
            fallback()
            return Outcome.LAUNCHED_VIA_FALLBACK
        } catch (t: Throwable) {
            report(onError, "fallback", t)
        }

        return Outcome.FAILED
    }

    private fun report(onError: (String, Throwable) -> Unit, stage: String, t: Throwable) {
        try {
            onError(stage, t)
        } catch (_: Throwable) {
        }
    }
}
