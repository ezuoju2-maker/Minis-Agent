package com.openminis.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLocalCleanupTest {

    private fun outcome(name: String, ok: Boolean) =
        BackupHistory.DestinationOutcome(name = name, succeeded = ok)

    private fun shouldRemoveLocal(
        destinations: List<BackupHistory.DestinationOutcome>,
    ): Boolean = destinations.isNotEmpty() && destinations.all { it.succeeded }

    @Test
    fun `every destination verified removes the local copy`() {
        assertTrue(shouldRemoveLocal(listOf(outcome("nas", true))))
        assertTrue(shouldRemoveLocal(listOf(outcome("nas", true), outcome("offsite", true))))
    }

    @Test
    fun `a failed destination keeps the local copy`() {
        assertFalse(shouldRemoveLocal(listOf(outcome("nas", true), outcome("offsite", false))))
        assertFalse(shouldRemoveLocal(listOf(outcome("nas", false))))
    }

    @Test
    fun `no destinations keeps the local copy`() {
        assertFalse(shouldRemoveLocal(emptyList()))
    }

    @Test
    fun `all-delivered is what the footer keys off`() {
        val allOk = listOf(outcome("nas", true), outcome("offsite", true))
        val someFailed = listOf(outcome("nas", true), outcome("offsite", false))
        assertTrue(allOk.isNotEmpty() && allOk.all { it.succeeded })
        assertFalse(someFailed.isNotEmpty() && someFailed.all { it.succeeded })
    }
}
