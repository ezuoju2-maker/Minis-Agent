package com.openminis.app.agent.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object OnDemandBash {

    private const val TAG = "Bashism"
    private const val PREFS = "bash_install"
    private const val KEY_FAIL_COUNT = "failCount"
    private const val KEY_LAST_FAIL = "lastFailAt"
    private const val MAX_STRIKES = 3
    private const val BACKOFF_WINDOW_MS = 24L * 3600 * 1000
    private const val UNAVAILABLE_TTL_MS = 10L * 60 * 1000
    private const val INSTALL_BUDGET_MS = 60_000L
    private const val STOPPED_EXIT = 130
    const val STOPPED_REASON = "stopped by the user"
    private const val APK_PROBE_URL = "http://ports.ubuntu.com/ubuntu-ports/dists/noble/Release"

    private sealed class Availability {
        object Unknown : Availability()
        object Available : Availability()
        data class Unavailable(val until: Long) : Availability()
    }

    private val lock = Mutex()
    private var availability: Availability = Availability.Unknown
    private var attemptedInstallThisLaunch = false

    fun interface Executor {
        suspend fun run(command: String, timeoutMs: Long): Int
    }

    sealed class Outcome {
        object Available : Outcome()
        data class Unavailable(val reason: String) : Outcome()
    }

    suspend fun ensureBash(context: Context, executor: Executor): Outcome = lock.withLock {
        when (val a = availability) {
            is Availability.Available -> return Outcome.Available
            is Availability.Unavailable -> {
                if (System.currentTimeMillis() < a.until) return Outcome.Unavailable("bash not installed")
                availability = Availability.Unknown
            }
            is Availability.Unknown -> {}
        }

        val probe = executor.run("command -v bash >/dev/null 2>&1", 15_000)
        if (probe == 0) {
            availability = Availability.Available
            return Outcome.Available
        }
        if (probe == STOPPED_EXIT) return Outcome.Unavailable(STOPPED_REASON)

        if (attemptedInstallThisLaunch) {
            markUnavailable()
            return Outcome.Unavailable("bash install already attempted this session")
        }
        backoffReason(context)?.let {
            markUnavailable()
            return Outcome.Unavailable(it)
        }
        attemptedInstallThisLaunch = true

        if (!networkReachable()) {
            recordFailure(context); markUnavailable()
            return Outcome.Unavailable("network/apt mirror unreachable")
        }

        Log.i(TAG, "installing bash (budget ${INSTALL_BUDGET_MS / 1000}s)…")
        val rc = executor.run("apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y bash", INSTALL_BUDGET_MS)
        if (rc == STOPPED_EXIT) {
            attemptedInstallThisLaunch = false
            Log.i(TAG, "bash install stopped by the user")
            return Outcome.Unavailable(STOPPED_REASON)
        }
        val verified = rc == 0 && executor.run("command -v bash >/dev/null 2>&1", 15_000) == 0
        if (verified) {
            clearFailure(context)
            availability = Availability.Available
            attemptedInstallThisLaunch = false
            Log.i(TAG, "bash installed OK")
            return Outcome.Available
        }
        recordFailure(context); markUnavailable()
        Log.e(TAG, "bash install failed (rc=$rc)")
        return Outcome.Unavailable("apt-get install bash failed (rc=$rc)")
    }

    suspend fun markDisappeared() = lock.withLock { availability = Availability.Unknown }

    private fun markUnavailable() {
        availability = Availability.Unavailable(System.currentTimeMillis() + UNAVAILABLE_TTL_MS)
    }

    private fun backoffReason(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = p.getInt(KEY_FAIL_COUNT, 0)
        if (count >= MAX_STRIKES) return "bash install disabled after $MAX_STRIKES failures (retry manually: apt-get install -y bash)"
        val last = p.getLong(KEY_LAST_FAIL, 0)
        if (last > 0 && System.currentTimeMillis() - last < BACKOFF_WINDOW_MS) return "bash install backing off (recent failure)"
        return null
    }

    private fun recordFailure(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_FAIL_COUNT, p.getInt(KEY_FAIL_COUNT, 0) + 1)
            .putLong(KEY_LAST_FAIL, System.currentTimeMillis()).apply()
    }

    private fun clearFailure(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_FAIL_COUNT).remove(KEY_LAST_FAIL).apply()
    }

    private suspend fun networkReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val code = com.openminis.app.network.NetworkUrlRequest.open(
                com.openminis.app.network.NetworkScope.SHELL.tag, APK_PROBE_URL, "HEAD",
                connectTimeoutMs = 5000, readTimeoutMs = 5000,
            ).use { it.code }
            code in 200..499
        } catch (_: Exception) {
            false
        }
    }
}
