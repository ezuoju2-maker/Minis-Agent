package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupResolveCredentialFilterTest {

    private data class Member(
        val entryId: String,
        val hidden: Boolean = false,
        val providerEnabled: Boolean = true,
        val credentialed: Boolean = true,
    )

    private fun available(members: List<Member>): List<String> =
        members.filter { !it.hidden && it.providerEnabled && it.credentialed }
            .map { it.entryId }

    private fun resolve(
        members: List<Member>,
        preferredEntryId: String? = null,
        loadBalance: Boolean = false,
        sessionId: String = "",
    ): String? {
        val avail = available(members)
        if (avail.isEmpty()) return null
        return avail.firstOrNull { it == preferredEntryId }
            ?: if (loadBalance) avail[Math.floorMod(sessionId.hashCode(), avail.size)]
            else avail.first()
    }


    @Test
    fun `uncredentialed head member is skipped, not fatal to the whole group`() {
        val group = listOf(
            Member("anthropic/claude-sonnet-5", credentialed = false),
            Member("anthropic/claude-sonnet-4-6", credentialed = false),
            Member("anthropic/claude-opus-5", credentialed = false),
            Member("openai/gpt-5.6-terra"),
            Member("openai/gpt-5.6-sol"),
        )
        assertEquals("openai/gpt-5.6-terra", resolve(group))
    }

    @Test
    fun `group resolves to null only when EVERY member is unusable`() {
        val group = listOf(
            Member("a/1", credentialed = false),
            Member("b/2", providerEnabled = false),
            Member("c/3", hidden = true),
        )
        assertNull(resolve(group))
    }


    @Test
    fun `hidden entries are filtered, matching iOS`() {
        val group = listOf(Member("a/1", hidden = true), Member("b/2"))
        assertEquals("b/2", resolve(group))
    }

    @Test
    fun `disabled providers are still filtered`() {
        val group = listOf(Member("a/1", providerEnabled = false), Member("b/2"))
        assertEquals("b/2", resolve(group))
    }

    @Test
    fun `filtering preserves declaration order so primary stays first member`() {
        val group = listOf(
            Member("a/1", credentialed = false),
            Member("b/2"),
            Member("c/3"),
        )
        assertEquals(listOf("b/2", "c/3"), available(group))
        assertEquals("b/2", resolve(group))
    }

    @Test
    fun `a fully usable group is unaffected`() {
        val group = listOf(Member("a/1"), Member("b/2"))
        assertEquals("a/1", resolve(group))
    }


    @Test
    fun `preferred entry is honored when still available`() {
        val group = listOf(Member("a/1"), Member("b/2"), Member("c/3"))
        assertEquals("c/3", resolve(group, preferredEntryId = "c/3"))
    }

    @Test
    fun `preferred entry that lost its credential falls back to first available`() {
        val group = listOf(
            Member("a/1", credentialed = false),
            Member("b/2"),
            Member("c/3", credentialed = false),
        )
        assertEquals("b/2", resolve(group, preferredEntryId = "c/3"))
    }


    @Test
    fun `loadBalance picks within the FILTERED list, never an unusable member`() {
        val group = listOf(
            Member("dead/1", credentialed = false),
            Member("ok/1"),
            Member("ok/2"),
        )
        val usable = setOf("ok/1", "ok/2")
        for (sid in listOf("", "s1", "s2", "session-abc", "😀")) {
            val picked = resolve(group, loadBalance = true, sessionId = sid)
            assert(picked in usable) { "loadBalance picked $picked for sid=$sid" }
        }
    }

    @Test
    fun `loadBalance is stable for a given session id`() {
        val group = listOf(Member("a/1"), Member("b/2"), Member("c/3"))
        val first = resolve(group, loadBalance = true, sessionId = "session-42")
        repeat(5) {
            assertEquals(first, resolve(group, loadBalance = true, sessionId = "session-42"))
        }
    }

    @Test
    fun `loadBalance spreads distinct sessions across members`() {
        val group = listOf(Member("a/1"), Member("b/2"), Member("c/3"))
        val picked = (0 until 60).map {
            resolve(group, loadBalance = true, sessionId = "session-$it")
        }.toSet()
        assert(picked.size > 1) { "loadBalance collapsed to a single member: $picked" }
    }

    @Test
    fun `fallback strategy always takes the first available member`() {
        val group = listOf(Member("a/1", credentialed = false), Member("b/2"), Member("c/3"))
        for (sid in listOf("", "s1", "session-xyz")) {
            assertEquals("b/2", resolve(group, loadBalance = false, sessionId = sid))
        }
    }


    @Test
    fun `a disabled head member is never the first call`() {
        val group = listOf(
            Member("off/flagship", providerEnabled = false),
            Member("on/backup"),
        )
        assertEquals("on/backup", resolve(group))
        assertEquals(listOf("on/backup"), available(group))
    }

    @Test
    fun `a disabled entry is unreachable from every position and strategy`() {
        for (position in 0..2) {
            val members = MutableList(3) { i -> Member("on/$i") }
            members[position] = Member("off/$position", providerEnabled = false)
            val avail = available(members)
            assertTrue(
                "the disabled member leaked at position $position: $avail",
                avail.none { it.startsWith("off/") },
            )
            for (sid in listOf("", "s1", "session-42", "polygenelubricants")) {
                for (lb in listOf(false, true)) {
                    val picked = resolve(members, loadBalance = lb, sessionId = sid)
                    assertTrue(
                        "picked $picked (disabled at $position, lb=$lb, sid=$sid)",
                        picked != null && !picked.startsWith("off/"),
                    )
                }
            }
        }
    }

    @Test
    fun `a disabled provider cannot be reached even as the session's stored preference`() {
        val group = listOf(
            Member("off/remembered", providerEnabled = false),
            Member("on/live"),
        )
        assertEquals("on/live", resolve(group, preferredEntryId = "off/remembered"))
    }

    @Test
    fun `a group whose every member is behind a disabled provider resolves to null`() {
        val group = listOf(
            Member("off/1", providerEnabled = false),
            Member("off/2", providerEnabled = false),
        )
        assertNull(resolve(group))
        assertNull(resolve(group, loadBalance = true, sessionId = "s"))
        assertNull(resolve(group, preferredEntryId = "off/1"))
    }

    @Test
    fun `disabled and uncredentialed are independent reasons to skip`() {
        val group = listOf(
            Member("enabled-but-signed-out", credentialed = false),
            Member("credentialed-but-disabled", providerEnabled = false),
            Member("usable"),
        )
        assertEquals(listOf("usable"), available(group))
    }


    private fun src(path: String) = com.openminis.app.ProductionSources.read(path)

    @Test
    fun `both filters exist and the routing one checks all three conditions`() {
        val repoSrc = src("data/repository/ProviderRepository.kt")
        assertTrue(repoSrc.contains("fun enabledMemberEntries(group: ModelGroup)"))
        assertTrue(repoSrc.contains("fun availableMemberEntries(group: ModelGroup)"))

        val routing = repoSrc.substringAfter("fun availableMemberEntries(group: ModelGroup)")
            .substringBefore("fun resolveTitleSubEntry()")
        assertTrue("must drop hidden entries", routing.contains("entry.isHidden"))
        assertTrue("must drop disabled providers", routing.contains("!instance.isEnabled"))
        assertTrue("must drop uncredentialed providers", routing.contains("!hasAnyCredential(instance)"))
        assertTrue(
            "must walk memberEntryIds in declaration order",
            routing.contains("group.memberEntryIds.mapNotNull"),
        )
    }

    @Test
    fun `the request-path resolvers all go through availableMemberEntries`() {
        val sites = mapOf(
            "ui/chat/ChatViewModel.kt" to "availableMemberEntries(group)",
            "agent/jobs/ModelTierResolver.kt" to "availableMemberEntries(",
            "speech/correction/CorrectionStrategy.kt" to "availableMemberEntries(",
            "scheduled/ScheduledAgentRunner.kt" to "availableMemberEntries(",
        )
        for ((path, needle) in sites) {
            assertTrue("$path must resolve through the credential-aware filter", src(path).contains(needle))
        }
        assertTrue(
            "resolveTitleSubEntry must use the routing filter, not the UI one",
            src("data/repository/ProviderRepository.kt").contains(
                "return availableMemberEntries(group).firstOrNull()",
            ),
        )
    }

    @Test
    fun `the settings UI has its own predicate so it can dim rather than hide`() {
        val repoSrc = src("data/repository/ProviderRepository.kt")
        assertTrue(repoSrc.contains("fun isEntryProviderEnabled(entryId: String)"))
        val fn = repoSrc.substringAfter("fun isEntryProviderEnabled(entryId: String)")
            .substringBefore("fun replaceEntries(")
        assertTrue("it must read isEnabled", fn.contains("instance.isEnabled"))
        assertTrue(
            "an orphaned entry id must answer false, not crash",
            fn.contains("?: return false"),
        )
    }

    @Test
    fun `negative hash codes do not crash or index out of bounds`() {
        val group = listOf(Member("a/1"), Member("b/2"))
        val picked = resolve(group, loadBalance = true, sessionId = "polygenelubricants")
        assert(picked == "a/1" || picked == "b/2") { "unexpected pick: $picked" }
    }
}
