package com.openminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareHandoffPolicyTest {

    private fun vivoStyleFailure(): RuntimeException =
        RuntimeException(
            "Unable to start activity ComponentInfo{com.openminis.app/…ShareReceiverActivity}",
            NullPointerException( null),
        )

    @Test
    fun `normal device launches on the first attempt and never runs the fallback`() {
        var primary = 0
        var fallback = 0
        val outcome = ShareHandoffPolicy.handOff(
            primary = { primary++ },
            fallback = { fallback++ },
        )
        assertEquals(ShareHandoffPolicy.Outcome.LAUNCHED, outcome)
        assertEquals(1, primary)
        assertEquals("fallback must stay dormant on a healthy device", 0, fallback)
    }

    @Test
    fun `the vivo crash is caught and the fallback launch is used`() {
        var fallback = 0
        val outcome = ShareHandoffPolicy.handOff(
            primary = { throw vivoStyleFailure() },
            fallback = { fallback++ },
        )
        assertEquals(ShareHandoffPolicy.Outcome.LAUNCHED_VIA_FALLBACK, outcome)
        assertEquals(1, fallback)
    }

    @Test
    fun `both attempts failing reports FAILED instead of throwing`() {
        val outcome = ShareHandoffPolicy.handOff(
            primary = { throw vivoStyleFailure() },
            fallback = { throw vivoStyleFailure() },
        )
        assertEquals(ShareHandoffPolicy.Outcome.FAILED, outcome)
    }

    @Test
    fun `a cause carrying a null message does not defeat the guard`() {
        val npe = NullPointerException(null as String?)
        val outcome = ShareHandoffPolicy.handOff(
            primary = { throw npe },
            fallback = { throw npe },
        )
        assertEquals(ShareHandoffPolicy.Outcome.FAILED, outcome)
    }

    @Test
    fun `Errors are caught too, not just Exceptions`() {
        val outcome = ShareHandoffPolicy.handOff(
            primary = { throw StackOverflowError("deep OEM stack") },
            fallback = { throw AssertionError("boom") },
        )
        assertEquals(ShareHandoffPolicy.Outcome.FAILED, outcome)
    }

    @Test
    fun `each failure is reported with its stage`() {
        val stages = mutableListOf<String>()
        ShareHandoffPolicy.handOff(
            primary = { throw vivoStyleFailure() },
            fallback = { throw vivoStyleFailure() },
            onError = { stage, _ -> stages.add(stage) },
        )
        assertEquals(listOf("primary", "fallback"), stages)
    }

    @Test
    fun `a throwing logger cannot defeat the guard`() {
        val outcome = ShareHandoffPolicy.handOff(
            primary = { throw vivoStyleFailure() },
            fallback = { throw vivoStyleFailure() },
            onError = { _, _ -> throw IllegalStateException("logger died") },
        )
        assertEquals(ShareHandoffPolicy.Outcome.FAILED, outcome)
    }

    @Test
    fun `handOff never throws for any failure combination`() {
        val throwers: List<() -> Unit> = listOf(
            { },
            { throw vivoStyleFailure() },
            { throw NullPointerException(null as String?) },
            { throw AssertionError("x") },
        )
        for (p in throwers) {
            for (f in throwers) {
                val outcome = ShareHandoffPolicy.handOff(primary = p, fallback = f)
                assertTrue(outcome in ShareHandoffPolicy.Outcome.entries)
            }
        }
    }
}
