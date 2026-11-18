package dev.pboard.translate

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class LiveTranslationTest {
    /** Upper-cases text slowly, polling cancellation like the real decoder. */
    private class Slow(override val from: String, override val to: String, val stepMs: Long = 5) : Translator {
        var calls = 0
        override fun translate(text: String, cancelled: () -> Boolean): String? {
            calls++
            repeat(10) { if (cancelled()) return null; Thread.sleep(stepMs) }
            return "$to:" + text.uppercase()
        }
    }

    @Test fun newestRequestWinsAndStaleWorkIsCancelled() {
        val exec = Executors.newSingleThreadExecutor()
        val results = java.util.concurrent.CopyOnWriteArrayList<LiveTranslation.Result>()
        val done = CountDownLatch(1)
        val t = Slow("en", "es")
        lateinit var live: LiveTranslation
        live = LiveTranslation(exec, { _, _ -> t }, { r -> results.add(r); if (r.source == "hello world") done.countDown() })
        live.setDirection("en", "es", "")
        for (s in listOf("h", "he", "hel", "hell", "hello", "hello ", "hello w", "hello world")) live.submit(s)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        exec.shutdown(); exec.awaitTermination(5, TimeUnit.SECONDS)
        // only the last request may be delivered; earlier ones were skipped or cancelled
        assertEquals(listOf("es:HELLO WORLD"), results.map { it.translation })
        assertTrue(live.isCurrent(results.last()))
        assertTrue(t.calls < 8, "stale requests should not all run (${t.calls})")
    }

    @Test fun directionChangeReloadsAndRetranslates() {
        val exec = Executors.newSingleThreadExecutor()
        val loads = ArrayList<String>()
        val got = CountDownLatch(1)
        var last: LiveTranslation.Result? = null
        val live = LiveTranslation(exec, { f, to -> loads.add("$f-$to"); Slow(f, to, 0) }, { r -> last = r; got.countDown() })
        live.setDirection("en", "es", "")
        live.setDirection("es", "en", "hola")
        assertTrue(got.await(5, TimeUnit.SECONDS))
        exec.shutdown(); exec.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals("en:HOLA", last!!.translation)
        assertEquals("es", last!!.from)
        assertTrue(loads.last() == "es-en")
        assertTrue(live.ready)
    }

    @Test fun emptyTextYieldsEmptyTranslation() {
        val exec = Executors.newSingleThreadExecutor()
        val got = CountDownLatch(1)
        var last: LiveTranslation.Result? = null
        val live = LiveTranslation(exec, { f, to -> Slow(f, to, 0) }, { r -> last = r; got.countDown() })
        live.setDirection("en", "es", "")
        live.submit("")
        assertTrue(got.await(5, TimeUnit.SECONDS))
        exec.shutdown()
        assertEquals("", last!!.translation)
    }
}
