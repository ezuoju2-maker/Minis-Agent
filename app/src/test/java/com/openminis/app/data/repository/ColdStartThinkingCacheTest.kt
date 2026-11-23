package com.openminis.app.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdStartThinkingCacheTest {

    private val src: String by lazy {
        val f = File("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    private val initBlock: String by lazy {
        src.substringAfter("    init {").substringBefore("\n    private fun saveConfig(")
    }

    @Test
    fun `the async loader warms the thinking-rule cache after adopting the config`() {
        assertTrue(
            "the init coroutine must call loadAllThinkingRulesIntoCache(), or a cold " +
                "start where the loader wins the race leaves the cache empty",
            initBlock.contains("loadAllThinkingRulesIntoCache()"),
        )
    }

    @Test
    fun `the warm is guarded so it only runs when this coroutine adopted the config`() {
        assertTrue(initBlock.contains("adoptedByAsyncLoader = true"))
        assertTrue(initBlock.contains("if (adoptedByAsyncLoader) loadAllThinkingRulesIntoCache()"))
    }

    @Test
    fun `ensureConfigLoaded still warms the cache on the path it owns`() {
        val fn = src.substringAfter("internal fun ensureConfigLoaded()")
            .substringBefore("\n    private fun ")
        assertTrue(
            "ensureConfigLoaded must keep its own loadAllThinkingRulesIntoCache()",
            fn.contains("loadAllThinkingRulesIntoCache()"),
        )
    }
}
