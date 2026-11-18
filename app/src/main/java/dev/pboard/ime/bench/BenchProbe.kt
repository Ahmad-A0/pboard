package dev.pboard.ime.bench

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import dev.pboard.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit "input applied and idle" signal for the benchmark harness (tools/bench), so it does not
 * have to infer quiet from field-text polling. Only active in the `bench` build type
 * (BuildConfig.BENCH_PROBE); in release every hook is behind a constant-false check and is removed
 * by R8, and the provider below is only declared in the bench manifest.
 *
 * Counters: [downs]/[ups] = pointer DOWN/UP events the key surface or strip has handled (an injected
 * tap or swipe ends with exactly one UP), [engineInFlight] = engine requests submitted but not yet
 * delivered (or dropped) on the main thread.
 */
object BenchProbe {
    @JvmField val downs = AtomicLong()
    @JvmField val ups = AtomicLong()
    @JvmField val engineInFlight = AtomicInteger()

    @JvmStatic fun onTouch(actionMasked: Int) {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> downs.incrementAndGet()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> ups.incrementAndGet()
        }
    }
}

/**
 * `content://dev.pboard.benchprobe/state` (bench build only): one row with downs, ups,
 * engineInFlight, mainIdle and uptime. The query first waits on the main thread for two Choreographer
 * frames, so every message queued before the query (touch handling, InputConnection edits - the
 * playground field lives in this process too - and engine results) has run and any invalidation
 * has been drawn; mainIdle then says whether nothing else is due on the main thread.
 */
class BenchProbeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (!BuildConfig.BENCH_PROBE) return null
        val latch = CountDownLatch(1)
        var mainIdle = false
        Handler(Looper.getMainLooper()).post {
            val ch = Choreographer.getInstance()
            ch.postFrameCallback {
                ch.postFrameCallback {
                    mainIdle = Looper.myQueue().isIdle
                    latch.countDown()
                }
            }
        }
        val answered = latch.await(2, TimeUnit.SECONDS)
        return MatrixCursor(arrayOf("downs", "ups", "engineInFlight", "mainIdle", "uptime")).apply {
            addRow(arrayOf<Any>(BenchProbe.downs.get(), BenchProbe.ups.get(), BenchProbe.engineInFlight.get(),
                if (answered && mainIdle) 1 else 0, SystemClock.uptimeMillis()))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
