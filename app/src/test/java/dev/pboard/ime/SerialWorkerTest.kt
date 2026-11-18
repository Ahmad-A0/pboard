package dev.pboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The IME's teardown order (P0 crash on every switch to another keyboard): InputMethodService.onDestroy finishes the
 * input, whose save is submitted while the engine thread is being shut down, and callbacks posted earlier can submit
 * work after it. Work queued before shutdown must complete; later submissions must be dropped, not thrown.
 */
class SerialWorkerTest {
    @Test fun workQueuedBeforeShutdownStillRuns() {
        val w = SerialWorker("test")
        val gate = CountDownLatch(1)
        val done = Collections.synchronizedList(ArrayList<Int>())
        w.execute { gate.await() }          // keeps the thread busy so the next items stay queued
        w.execute { done += 1 }
        w.execute { done += 2 }             // e.g. onFinishInput's save of learned data
        w.shutdown()
        gate.countDown()
        assertTrue(w.awaitTermination(2000))
        assertEquals(listOf(1, 2), done)
    }

    @Test fun submissionsAfterShutdownAreDroppedNotThrown() {
        val w = SerialWorker("test")
        w.shutdown()
        assertTrue(w.isShutdown)
        assertFalse(w.execute { error("must not run") })
        assertNull(w.submit { error("must not run") })
    }

    @Test fun runsInSubmissionOrder() {
        val w = SerialWorker("test")
        val order = Collections.synchronizedList(ArrayList<Int>())
        for (i in 0 until 50) w.execute { order += i }
        val f = w.submit { order += 50 }
        f!!.get(2, TimeUnit.SECONDS)
        assertEquals((0..50).toList(), order)
        w.shutdown()
    }
}
