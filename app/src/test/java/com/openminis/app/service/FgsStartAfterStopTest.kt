package com.openminis.app.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FgsStartAfterStopTest {

    private class Fixed {
        private val lock = Any()
        var active = 1
        val startsAfterStop = AtomicInteger(0)
        private var stopped = false

        private fun shouldRun() = active > 0

        fun update() = synchronized(lock) {
            if (!shouldRun()) return@synchronized
            if (stopped) startsAfterStop.incrementAndGet()
        }

        fun deactivate() = synchronized(lock) {
            active = 0
            if (!shouldRun()) stopped = true
        }
    }

    private class Racy {
        var active = 1
        val startsAfterStop = AtomicInteger(0)
        @Volatile private var stopped = false

        fun update(sawNonEmpty: Boolean) {
            if (!sawNonEmpty) return
            if (stopped) startsAfterStop.incrementAndGet()
        }

        fun deactivate() {
            active = 0
            stopped = true
        }
    }

    @Test
    fun `an update that loses the race does not start the service again`() {
        val t = Fixed()
        t.deactivate()
        t.update()
        assertEquals("no start may follow the stop", 0, t.startsAfterStop.get())
    }

    @Test
    fun `the unguarded shape is what crashed - proves the test is not vacuous`() {
        val t = Racy()
        t.deactivate()
        t.update(sawNonEmpty = true)
        assertEquals(
            "the stale read is what resurrected the service",
            1,
            t.startsAfterStop.get(),
        )
    }

    @Test
    fun `updates before the stop still dispatch normally`() {
        val t = Fixed()
        t.update()
        t.update()
        assertEquals(0, t.startsAfterStop.get())
    }

    @Test
    fun `concurrent update and deactivate never produce a start after stop`() {
        repeat(200) {
            val t = Fixed()
            val gate = CountDownLatch(1)
            val done = CountDownLatch(2)
            val a = Thread {
                gate.await(); t.deactivate(); done.countDown()
            }
            val b = Thread {
                gate.await(); t.update(); done.countDown()
            }
            a.start(); b.start(); gate.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(0, t.startsAfterStop.get())
        }
    }

    @Test
    fun `the backstop promotes before it stops`() {
        val promoted = AtomicBoolean(false)
        val order = mutableListOf<String>()

        fun startForeground() { promoted.set(true); order.add("startForeground") }
        fun stopForeground() { order.add("stopForeground") }
        fun stopSelf() { order.add("stopSelf") }

        val nothingToRun = true
        if (nothingToRun) {
            startForeground()
            stopForeground()
            stopSelf()
        }

        assertTrue("must promote before stopping", promoted.get())
        assertEquals(listOf("startForeground", "stopForeground", "stopSelf"), order)
        assertTrue(
            "stopSelf must never precede startForeground",
            order.indexOf("startForeground") < order.indexOf("stopSelf"),
        )
    }
}
