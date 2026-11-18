package dev.pboard.ime

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One background thread that runs work in submission order. After [shutdown], work already queued still runs and
 * new submissions are dropped instead of throwing: the IME's teardown (InputMethodService.onDestroy finishing the
 * input, posted callbacks arriving late) must never crash the process.
 */
class SerialWorker(name: String, daemon: Boolean = true) {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = daemon }
    }

    @Volatile var isShutdown = false
        private set

    /** Queues [work]; false if the worker is shut down. */
    fun execute(work: Runnable): Boolean {
        if (isShutdown) return false
        return try {
            exec.execute(work); true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /** Queues [work] and returns its future, or null if the worker is shut down. */
    fun submit(work: Runnable): Future<*>? {
        if (isShutdown) return null
        return try {
            exec.submit(work)
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    /** Stops accepting work; what is queued still runs. */
    fun shutdown() {
        isShutdown = true
        exec.shutdown()
    }

    /** Waits up to [ms] for queued work to finish after [shutdown]. */
    fun awaitTermination(ms: Long): Boolean = exec.awaitTermination(ms, TimeUnit.MILLISECONDS)
}
