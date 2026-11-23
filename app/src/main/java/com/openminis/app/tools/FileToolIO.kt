package com.openminis.app.tools

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal object FileToolIO {
    const val IO_TIMEOUT_MS = 45_000L

    const val MAX_EDIT_BYTES = 8L * 1024 * 1024

    const val MAX_THREADS = 32

    const val SLOT_WAIT_MS = 10_000L

    private val abandonedByPool = java.util.WeakHashMap<ExecutorService, AtomicInteger>()

    private fun abandoned(executor: ExecutorService): AtomicInteger =
        synchronized(abandonedByPool) { abandonedByPool.getOrPut(executor) { AtomicInteger() } }

    internal fun poolHeldByHungWork(abandoned: Int, max: Int): Boolean = abandoned >= maxOf(1, max / 2)

    private val threadIds = AtomicInteger()

    internal fun newPool(max: Int): ExecutorService =
        ThreadPoolExecutor(0, max, 60L, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, com.openminis.app.diagnostics.ThreadNames.of("fileio-${threadIds.incrementAndGet()}")).apply { isDaemon = true }
        }

    private val pool = newPool(MAX_THREADS)

    fun busyMessage(operation: String, max: Int): String =
        "$operation could not start: $max earlier file operations are still blocked (a mounted folder or " +
            "storage provider is not responding). Try again later, or use the shell for this file."

    fun saturatedMessage(operation: String, max: Int): String =
        "$operation could not start: $max file operations are already running. Try again in a moment."

    class TimedOut(message: String) : Exception(message)

    class OutOfMemory(message: String) : Exception(message)

    fun timedOutMessage(operation: String, timeoutMs: Long, writes: Boolean): String =
        "$operation did not finish within ${timeoutMs / 1000}s (the file may be on a mounted folder " +
            "or storage provider that is not responding)." +
            if (writes) " The write may still complete; re-read the file before retrying." else ""

    fun <T> run(
        operation: String,
        writes: Boolean = false,
        timeoutMs: Long = IO_TIMEOUT_MS,
        work: () -> T,
    ): T = runOn(pool, MAX_THREADS, operation, writes, timeoutMs, work)

    internal fun <T> runOn(
        executor: ExecutorService,
        max: Int,
        operation: String,
        writes: Boolean,
        timeoutMs: Long,
        work: () -> T,
    ): T {
        val stuck = abandoned(executor)
        val state = AtomicInteger(0)
        val task = java.util.concurrent.Callable<T> {
            try {
                work()
            } finally {
                if (!state.compareAndSet(0, 1)) stuck.decrementAndGet()
            }
        }
        fun abandon() {
            if (state.compareAndSet(0, 2)) stuck.incrementAndGet()
        }
        val future = submitWaiting(executor, max, stuck, operation, minOf(timeoutMs, SLOT_WAIT_MS), task)
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            abandon()
            AppLogger.warning("FileToolIO", "$operation timed out after ${timeoutMs}ms")
            throw TimedOut(timedOutMessage(operation, timeoutMs, writes))
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is OutOfMemoryError) {
                AppLogger.warning("FileToolIO", "$operation ran out of memory")
                throw OutOfMemory("$operation ran out of memory.")
            }
            throw cause
        } catch (e: InterruptedException) {
            future.cancel(true)
            abandon()
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun <T> submitWaiting(
        executor: ExecutorService,
        max: Int,
        stuck: AtomicInteger,
        operation: String,
        waitMs: Long,
        task: java.util.concurrent.Callable<T>,
    ): java.util.concurrent.Future<T> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (true) {
            try {
                return executor.submit(task)
            } catch (e: RejectedExecutionException) {
                val hung = stuck.get()
                if (poolHeldByHungWork(hung, max)) {
                    AppLogger.warning("FileToolIO", "[GH453] $operation refused: $max pool threads busy, $hung held by hung calls")
                    throw TimedOut(busyMessage(operation, max))
                }
                if (System.nanoTime() >= deadline) {
                    AppLogger.warning("FileToolIO", "[GH453] $operation refused: $max pool threads busy with live work for ${waitMs}ms")
                    throw TimedOut(saturatedMessage(operation, max))
                }
                Thread.sleep(50)
            }
        }
    }

    class LineWindow(
        val totalLines: Int,
        val selectedCount: Int,
        val content: String,
    )

    fun scanLines(file: File, tail: Boolean, offset: Int, lines: Int?, maxLength: Int): LineWindow =
        FileInputStream(file).use { scanLines(it, tail, offset, lines, maxLength) }

    fun scanLines(input: InputStream, tail: Boolean, offset: Int, lines: Int?, maxLength: Int): LineWindow {
        val cap = 4L * (maxLength.toLong().coerceAtLeast(0) + 1)
        return if (tail) scanTail(input, lines, cap) else scanHead(input, offset, lines, cap)
    }

    private inline fun forEachByte(input: InputStream, onByte: (Byte) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) onByte(buf[i])
        }
    }

    private const val LF: Byte = 0x0A
    private const val CR: Byte = 0x0D

    private fun scanHead(input: InputStream, offset: Int, lines: Int?, cap: Long): LineWindow {
        val start = (offset.toLong() - 1).coerceAtLeast(0)
        val end = if (lines != null) start + lines else Long.MAX_VALUE
        val window = ByteSink()
        var lineIndex = 0L
        var lineOpen = false
        var lineStarted = false
        var selected = 0L
        var prevCR = false

        fun beginSelectedLine() {
            if (!lineStarted) {
                lineStarted = true
                if (selected > 0) window.addCapped(LF, cap)
            }
        }
        fun endLine() {
            if (lineIndex in start until end) {
                beginSelectedLine()
                selected++
            }
            lineIndex++
            lineOpen = false
            lineStarted = false
        }

        forEachByte(input) { b ->
            if (prevCR && b == LF) {
                prevCR = false
                return@forEachByte
            }
            prevCR = false
            if (b == LF || b == CR) {
                endLine()
                prevCR = b == CR
                return@forEachByte
            }
            lineOpen = true
            if (lineIndex in start until end && window.size < cap) {
                beginSelectedLine()
                window.addCapped(b, cap)
            }
        }
        if (lineOpen) endLine()
        return LineWindow(lineIndex.toIntClamped(), selected.toIntClamped(), window.decode())
    }

    private fun scanTail(input: InputStream, lines: Int?, cap: Long): LineWindow {
        val keepLines = lines?.toLong() ?: Long.MAX_VALUE
        val kept = ArrayDeque<ByteArray>()
        var keptBytes = 0L
        val current = TailLine(cap)
        var totalLines = 0L
        var lineOpen = false
        var prevCR = false

        fun endLine() {
            totalLines++
            lineOpen = false
            if (keepLines <= 0) {
                current.reset()
                return
            }
            val bytes = current.take()
            kept.addLast(bytes)
            keptBytes += bytes.size
            while (kept.size > keepLines) keptBytes -= kept.removeFirst().size
            while (kept.size > 1) {
                val joinedWithoutFirst = keptBytes - kept.first().size + (kept.size - 2)
                if (joinedWithoutFirst < cap) break
                keptBytes -= kept.removeFirst().size
            }
        }

        forEachByte(input) { b ->
            if (prevCR && b == LF) {
                prevCR = false
                return@forEachByte
            }
            prevCR = false
            if (b == LF || b == CR) {
                endLine()
                prevCR = b == CR
                return@forEachByte
            }
            lineOpen = true
            if (keepLines > 0) current.add(b)
        }
        if (lineOpen) endLine()

        val selected = minOf(keepLines, totalLines)
        val out = ByteSink()
        kept.forEachIndexed { i, bytes ->
            if (i > 0) out.add(LF)
            out.addAll(bytes)
        }
        return LineWindow(totalLines.toIntClamped(), selected.toIntClamped(), out.decode())
    }

    private fun Long.toIntClamped(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private class ByteSink {
        private var buf = ByteArray(256)
        var size = 0L
            private set

        fun add(b: Byte) {
            if (size.toInt() == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size.toInt()] = b
            size++
        }

        fun addCapped(b: Byte, cap: Long) {
            if (size < cap) add(b)
        }

        fun addAll(bytes: ByteArray) {
            for (b in bytes) add(b)
        }

        fun decode(): String = String(buf, 0, size.toInt(), Charsets.UTF_8)
    }

    private class TailLine(private val cap: Long) {
        private var buf = ByteArray(256)
        private var len = 0

        fun add(b: Byte) {
            if (len == buf.size) {
                if (len >= 2 * cap) {
                    val keep = cap.toInt()
                    System.arraycopy(buf, len - keep, buf, 0, keep)
                    len = keep
                } else {
                    buf = buf.copyOf(minOf(buf.size.toLong() * 2, 2 * cap).toInt().coerceAtLeast(buf.size + 1))
                }
            }
            buf[len++] = b
        }

        fun take(): ByteArray {
            val from = (len - cap).coerceAtLeast(0).toInt()
            val out = buf.copyOfRange(from, len)
            reset()
            return out
        }

        fun reset() {
            len = 0
            if (buf.size > 4096) buf = ByteArray(256)
        }
    }
}
