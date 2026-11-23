package com.openminis.app.speech

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReentrantSafeReleaseTest {

    private class FakeNative {
        var freed = false
        var usedAfterFree = false
        val log = mutableListOf<String>()
        fun process(onCallback: () -> Unit) {
            log += "enter"
            onCallback()
            if (freed) usedAfterFree = true
            log += "exit"
        }
    }

    private fun gate(native: FakeNative) = ReentrantSafeRelease<FakeNative> {
        it.log += "release"
        it.freed = true
    }.also { it.attach(native) }

    @Test fun `a teardown from inside the callback is deferred until the call returns`() {
        val native = FakeNative()
        val g = gate(native)
        val alive = g.call { n -> n.process(onCallback = { g.tearDown() }) }

        assertFalse("native object used after being freed", native.usedAfterFree)
        assertEquals(listOf("enter", "exit", "release"), native.log)
        assertFalse("the take is reported torn down", alive)
    }

    @Test fun `the deferred object is released exactly once`() {
        val native = FakeNative()
        val g = gate(native)
        g.call { n -> n.process(onCallback = { g.tearDown(); g.tearDown() }) }
        g.tearDown()
        assertEquals(1, native.log.count { it == "release" })
    }

    @Test fun `a teardown outside any call releases immediately`() {
        val native = FakeNative()
        val g = gate(native)
        g.tearDown()
        assertEquals(listOf("release"), native.log)
    }

    @Test fun `a call after teardown does not touch the object`() {
        val native = FakeNative()
        val g = gate(native)
        g.tearDown()
        var ran = false
        assertFalse(g.call { ran = true })
        assertFalse(ran)
    }

    @Test fun `a normal call keeps the object alive`() {
        val native = FakeNative()
        val g = gate(native)
        assertTrue(g.call { n -> n.process(onCallback = {}) })
        assertFalse(native.freed)
    }

    @Test fun `a teardown from another thread waits for the in-flight call`() {
        val native = FakeNative()
        val g = gate(native)
        val inside = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val stopperDone = AtomicBoolean(false)

        val caller = Thread {
            g.call { n ->
                n.process(onCallback = {
                    inside.countDown()
                    proceed.await(5, TimeUnit.SECONDS)
                })
            }
        }.apply { start() }
        assertTrue(inside.await(5, TimeUnit.SECONDS))

        val stopper = Thread { g.tearDown(); stopperDone.set(true) }.apply { start() }
        stopper.join(200)
        assertFalse("teardown must block while the call is in flight", stopperDone.get())

        proceed.countDown()
        caller.join(5_000); stopper.join(5_000)
        assertTrue(stopperDone.get())
        assertFalse(native.usedAfterFree)
        assertEquals(listOf("enter", "exit", "release"), native.log)
    }

    @Test fun `a throwing call still releases the deferred object`() {
        val native = FakeNative()
        val g = gate(native)
        val thrown = runCatching {
            g.call { g.tearDown(); throw IllegalStateException("boom") }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(listOf("release"), native.log)
    }

    @Test fun `VoiceActivityDetector routes every native use through the gate`() {
        val src = ProductionSources.read("speech/VoiceActivityDetector.kt")
        assertTrue(src.contains("private val vad = ReentrantSafeRelease<VADWrapper> { it.release() }"))
        assertTrue(src.contains("vad.attach(wrapper)"))
        assertTrue(src.contains("val alive = vad.call { w ->"))
        assertTrue(src.contains("vad.tearDown()"))
        val code = src.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertEquals(
            "no direct VADWrapper release outside the gate", 1,
            Regex("""\bit\.release\(\)|\bw\??\.release\(\)|\bwrapper\.release\(\)""").findAll(code).count(),
        )
        assertFalse("the bare lock is gone", code.contains("vadLock"))
    }
}
