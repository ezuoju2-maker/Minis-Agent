package com.openminis.app.browser

import android.util.Log
import java.lang.ref.WeakReference
import java.util.Date

object BrowserTabPoolRegistry {

    private const val TAG = "BrowserTabPoolRegistry"

    const val GLOBAL_TAB_CAP = 8

    interface RegistryPool {
        fun registrySnapshot(): List<TabInfo>

        fun evictTabForRegistry(id: Int)

        fun preemptTabForRegistry(id: Int)

        fun evictAllIdleForMemoryPressure(): Int
    }

    data class TabInfo(val id: Int, val inUse: Boolean, val lastActivityDate: Date)

    private val pools = mutableListOf<WeakReference<RegistryPool>>()

    fun register(pool: RegistryPool) {
        compactPools()
        if (pools.none { it.get() === pool }) {
            pools.add(WeakReference(pool))
            Log.i(TAG, "Registered pool (${pools.size} live)")
        }
    }

    fun unregister(pool: RegistryPool) {
        pools.removeAll { it.get() === pool || it.get() == null }
    }

    private fun compactPools() {
        pools.removeAll { it.get() == null }
    }

    private fun livePools(): List<RegistryPool> {
        compactPools()
        return pools.mapNotNull { it.get() }
    }

    fun totalLiveTabs(): Int = livePools().sumOf { it.registrySnapshot().size }

    fun requestSlot(requester: RegistryPool): Boolean {
        val live = livePools()
        if (live.sumOf { it.registrySnapshot().size } < GLOBAL_TAB_CAP) return true

        val now = System.currentTimeMillis()
        var idleVictim: Victim? = null
        var busyVictim: Victim? = null

        for (pool in live) {
            if (pool === requester) continue
            for (tab in pool.registrySnapshot()) {
                val idleFor = now - tab.lastActivityDate.time
                if (!tab.inUse) {
                    if (idleVictim == null || idleFor > idleVictim.idleForMs) {
                        idleVictim = Victim(pool, tab.id, idleFor)
                    }
                } else {
                    if (busyVictim == null || idleFor > busyVictim.idleForMs) {
                        busyVictim = Victim(pool, tab.id, idleFor)
                    }
                }
            }
        }

        idleVictim?.let { v ->
            Log.i(
                TAG,
                "Global cap ($GLOBAL_TAB_CAP) reached — evicting idle tab ${v.tabId} " +
                    "from a sibling pool (idle ${v.idleForMs / 1000}s)",
            )
            v.pool.evictTabForRegistry(v.tabId)
            return true
        }
        busyVictim?.let { v ->
            Log.i(
                TAG,
                "Global cap ($GLOBAL_TAB_CAP) reached with no idle siblings — preempting " +
                    "in-use tab ${v.tabId} (idle ${v.idleForMs / 1000}s)",
            )
            v.pool.preemptTabForRegistry(v.tabId)
            return true
        }
        Log.i(TAG, "Global cap ($GLOBAL_TAB_CAP) reached and no sibling tabs to reclaim — denying")
        return false
    }

    private data class Victim(val pool: RegistryPool, val tabId: Int, val idleForMs: Long)

    fun handleMemoryPressure(): Int {
        val pools = livePools()
        val evicted = pools.sumOf { it.evictAllIdleForMemoryPressure() }
        if (evicted > 0) {
            Log.i(TAG, "Memory pressure — evicted $evicted idle tab(s) across ${pools.size} pool(s)")
        }
        return evicted
    }

    internal fun resetForTests() {
        pools.clear()
    }
}
