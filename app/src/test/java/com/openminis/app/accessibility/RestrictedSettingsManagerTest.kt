package com.openminis.app.accessibility

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedSettingsManagerTest {

    @Test
    fun `LOCAL_FILE is restricted`() {
        assertTrue(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE,
            ),
        )
    }

    @Test
    fun `DOWNLOADED_FILE is restricted`() {
        assertTrue(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE,
            ),
        )
    }

    @Test
    fun `UNSPECIFIED is not restricted`() {
        assertFalse(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED,
            ),
        )
    }

    @Test
    fun `STORE is not restricted`() {
        assertFalse(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_STORE,
            ),
        )
    }

    @Test
    fun `OTHER is not restricted`() {
        assertFalse(
            RestrictedSettingsManager.isRestrictedSource(
                PackageInstaller.PACKAGE_SOURCE_OTHER,
            ),
        )
    }

    @Test
    fun `unknown sources are not restricted`() {
        assertFalse(RestrictedSettingsManager.isRestrictedSource(Int.MAX_VALUE))
        assertFalse(RestrictedSettingsManager.isRestrictedSource(-1))
    }
}
