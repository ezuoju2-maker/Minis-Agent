package com.openminis.app.ui.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal object OnboardingModelFetch {

    const val TIMEOUT_MS = 20_000L

    data class Result(
        val errors: List<String>,
        val timedOut: Boolean,
    )

    suspend fun <T> run(
        targets: List<T>,
        timeoutMs: Long = TIMEOUT_MS,
        fetch: suspend (T) -> String?,
    ): Result {
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val finished = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                targets.map { target ->
                    async {
                        try {
                            fetch(target)?.let { errors += it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errors += e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        }
                    }
                }.awaitAll()
            }
            true
        }
        return Result(errors = synchronized(errors) { errors.toList() }, timedOut = finished == null)
    }
}
