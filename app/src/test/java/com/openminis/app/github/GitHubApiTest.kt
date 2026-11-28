package com.openminis.app.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiTest {

    @Test
    fun pathsAreNormalizedAndHostsRejected() {
        assertEquals("/user", GitHubApi.normalizePath("user"))
        assertEquals("/repos/o/r/issues", GitHubApi.normalizePath(" /repos/o/r/issues "))
        assertNull(GitHubApi.normalizePath("https://evil.example.com/x"))
        assertNull(GitHubApi.normalizePath("/repos/o/r\nexfiltrate"))
        assertNull(GitHubApi.normalizePath(""))
    }

    @Test
    fun onlyRepoDeleteNeedsConfirmation() {
        assertTrue(GitHubApi.isRepoDelete("/repos/owner/name", "delete"))
        assertTrue(GitHubApi.isRepoDelete("/repos/owner/name/", "DELETE"))
        assertFalse(GitHubApi.isRepoDelete("/repos/owner/name/issues/1", "DELETE"))
        assertFalse(GitHubApi.isRepoDelete("/repos/owner/name", "GET"))
    }

    @Test
    fun bodiesMustParseAsJson() {
        assertNull(GitHubApi.invalidBody("{\"title\":\"hi\"}"))
        assertNull(GitHubApi.invalidBody("[1,2]"))
        assertNotNull(GitHubApi.invalidBody("title=hi"))
        assertNotNull(GitHubApi.invalidBody(""))
    }

    @Test
    fun summaryShowsStatusRateAndScopes() {
        val reply = GitHubApi.Reply(200, "{\"ok\":true}", "4999", "5000", "repo, workflow")
        val text = GitHubApi.summarize(reply)
        assertTrue(text.startsWith("HTTP 200"))
        assertTrue(text.contains("rate 4999/5000"))
        assertTrue(text.contains("repo, workflow"))
        assertTrue(text.contains("{\"ok\":true}"))
    }

    @Test
    fun networkErrorsAreSummarizedAsErrors() {
        val text = GitHubApi.summarize(GitHubApi.Reply(0, "", "", "", "", error = "Network error: timeout"))
        assertTrue(text.startsWith("Error: Network error"))
    }

    @Test
    fun supportedMethodsAreBounded() {
        assertTrue(GitHubApi.METHODS.containsAll(listOf("GET", "POST", "PATCH", "PUT", "DELETE")))
        assertFalse(GitHubApi.METHODS.contains("TRACE"))
    }
}
