package com.openminis.app.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHtmlTrustTest {

    @Test
    fun workspaceAndSessionPageCanUseLocalFiles() {
        assertTrue(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/workspace/index.html"))
        assertTrue(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/workspace/app/chart.js"))
        assertTrue(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/sessions/s-1/attachments/page.html"))
        assertTrue(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/skills/foo/scripts/demo.html"))
    }

    @Test
    fun pagesOutsideTheSandboxCannotReadLocalFiles() {
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl("file:///sdcard/Download/invoice.html"))
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl("file:///data/local/tmp/evil.html"))
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/shared/incoming.html"))
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/mounts/usb/doc.html"))
    }

    @Test
    fun traversalOutOfTheWorkspaceIsRejected() {
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/workspace/../../data/data/com.openminis.app/x.html"))
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/workspace/../shared/x.html"))
    }

    @Test
    fun remoteAndMalformedUrlsAreNotLocal() {
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl("https://example.com/index.html"))
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl("minis://workspace/index.html"))
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl(null))
        assertFalse(LocalHtmlTrust.isTrustedLocalUrl(""))
    }

    @Test
    fun queryAndFragmentAreIgnored() {
        assertTrue(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/workspace/a.html?x=1#top"))
        assertTrue(LocalHtmlTrust.isTrustedLocalUrl("file:///var/minis/workspace/a%20b.html"))
    }

    @Test
    fun flagsFollowTrust() {
        assertTrue(LocalHtmlTrust.fileAccessFlags("file:///var/minis/workspace/a.html").first)
        assertFalse(LocalHtmlTrust.fileAccessFlags("file:///sdcard/Download/a.html").first)
        assertFalse(LocalHtmlTrust.fileAccessFlags("https://example.com").second)
    }
}
