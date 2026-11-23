package com.openminis.app.data

import java.io.File
import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsRefreshWindowTest {

    private val windowMs = 6 * 60 * 60 * 1000L

    private fun shouldSkip(lastMs: Long, nowMs: Long): Boolean =
        lastMs > 0L && (nowMs - lastMs) < windowMs

    private fun sameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = aMs
        val aYear = cal.get(Calendar.YEAR)
        val aDay = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = bMs
        return aYear == cal.get(Calendar.YEAR) && aDay == cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 19, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis


    @Test
    fun `the reported gap - refreshed at 0955, asked again at 2220`() {
        val last = at(9, 55)
        val later = at(22, 20)

        assertTrue("the old gate skipped this", sameCalendarDay(last, later))
        assertFalse("the rolling window must refresh here", shouldSkip(last, later))
    }

    @Test
    fun `a refresh within the window is still skipped`() {
        val last = at(9, 55)
        assertTrue(shouldSkip(last, at(10, 30)))
        assertTrue(shouldSkip(last, at(13, 0)))
        assertTrue(shouldSkip(last, at(15, 54)))
    }

    @Test
    fun `the boundary fires exactly at six hours`() {
        val last = at(9, 55)
        assertTrue("one ms before the window closes", shouldSkip(last, last + windowMs - 1))
        assertFalse("exactly at the window", shouldSkip(last, last + windowMs))
        assertFalse("after the window", shouldSkip(last, last + windowMs + 1))
    }

    @Test
    fun `a first run with no recorded refresh always fires`() {
        assertFalse("lastMs = 0 means never refreshed", shouldSkip(0L, at(9, 55)))
    }

    @Test
    fun `crossing midnight inside the window does NOT force a refresh`() {
        val last = at(23, 50)
        val justAfterMidnight = last + 20 * 60 * 1000L
        assertFalse("the old gate refreshed again immediately", sameCalendarDay(last, justAfterMidnight))
        assertTrue("the window correctly skips", shouldSkip(last, justAfterMidnight))
    }

    @Test
    fun `worst-case staleness drops from a day to the window`() {
        val last = at(0, 5)
        assertTrue(sameCalendarDay(last, at(23, 59)))
        assertFalse(shouldSkip(last, at(6, 6)))
    }


    private fun src(path: String) = File(path).readText()

    private val repoSrc by lazy {
        src("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt")
    }
    private val appSrc by lazy { src("src/main/java/com/openminis/app/MinisApp.kt") }

    @Test
    fun `the calendar-day gate is gone`() {
        assertFalse(
            "isSameCalendarDay must not survive as a live gate",
            repoSrc.contains("private fun isSameCalendarDay"),
        )
        assertTrue(repoSrc.contains("MODELS_REFRESH_WINDOW_MS"))
    }

    @Test
    fun `the window is six hours and distinct from the per-instance cache TTL`() {
        assertTrue(
            "window must be 6h",
            repoSrc.contains("MODELS_REFRESH_WINDOW_MS = 6 * 60 * 60 * 1000L"),
        )
        assertTrue(
            "the SWR per-instance TTL is a different knob and stays at 24h",
            repoSrc.contains("MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L"),
        )
    }

    @Test
    fun `a foreground return re-checks the model lists`() {
        val resumed = appSrc.substringAfter("override fun onActivityResumed")
            .substringBefore("override fun onActivityPaused")
        assertTrue(
            "onActivityResumed must call refreshAllModelsIfNeeded",
            resumed.contains("refreshAllModelsIfNeeded"),
        )
    }


    @Test
    fun `five hours fifty-nine skips and six hours one refreshes`() {
        val last = at(9, 55)
        val minute = 60 * 1000L
        assertTrue("5h59m must still skip", shouldSkip(last, last + 5 * 60 * minute + 59 * minute))
        assertFalse("6h01m must refresh", shouldSkip(last, last + 6 * 60 * minute + 1 * minute))
    }

    @Test
    fun `a first launch with no stored date refreshes rather than waiting six hours`() {
        assertFalse("no stored date", shouldSkip(0L, at(9, 55)))
        assertFalse("epoch zero is not 'just refreshed'", shouldSkip(0L, 0L + windowMs - 1))
    }

    @Test
    fun `a clock moved backwards does not wedge the gate shut`() {
        val future = at(20, 0)
        assertTrue("a future stamp skips, by design", shouldSkip(future, at(9, 55)))
        assertFalse("and recovers once the window truly elapses", shouldSkip(future, future + windowMs))
    }

    @Test
    fun `the gate reads the stored timestamp inside the coroutine, not before it`() {
        val fn = repoSrc.substringAfter("fun refreshAllModelsIfNeeded(")
            .substringBefore("/** Resolve the models.dev lookup base URL")
        val launchAt = fn.indexOf("scope.launch {")
        val readAt = fn.indexOf("prefs.getLong(key")
        assertTrue("both must be present", launchAt >= 0 && readAt >= 0)
        assertTrue(
            "prefs.getLong must happen INSIDE scope.launch",
            launchAt < readAt,
        )
        assertTrue(
            "the window comparison belongs in the coroutine too",
            fn.indexOf("MODELS_REFRESH_WINDOW_MS") > launchAt,
        )
    }

    @Test
    fun `the stamp is written before the per-instance fan-out`() {
        val fn = repoSrc.substringAfter("fun refreshAllModelsIfNeeded(")
            .substringBefore("/** Resolve the models.dev lookup base URL")
        val writeAt = fn.indexOf("putLong(key, now)")
        val fanOutAt = fn.indexOf("autoRefreshModels(instance)")
        assertTrue("both must be present", writeAt >= 0 && fanOutAt >= 0)
        assertTrue("the stamp must be written first", writeAt < fanOutAt)
    }

    @Test
    fun `the launch path still calls it too`() {
        assertTrue(appSrc.contains("providerRepository.refreshAllModelsIfNeeded("))
        val count = Regex("refreshAllModelsIfNeeded\\(").findAll(appSrc).count()
        assertTrue("expected both the launch and resume call sites, found $count", count >= 2)
    }
}
