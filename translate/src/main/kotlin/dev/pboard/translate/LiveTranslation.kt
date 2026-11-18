package dev.pboard.translate

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * Translate-as-you-type: every edit of the source text calls [submit]; translations run one at a time on [executor]
 * and the newest request wins. A request that is superseded while it runs is cancelled between decoder steps, so a
 * fast typist never waits for stale work. No debounce is needed: a sentence takes ~50 ms on a mid-range phone and the
 * sentence cache makes earlier sentences free.
 *
 * [load] opens the model for a direction (on the executor; slow, ~0.2 s); [deliver] receives results on the
 * executor thread - the caller hops to its UI thread. Errors are reported through [onError].
 */
class LiveTranslation(
    private val executor: Executor,
    private val load: (from: String, to: String) -> Translator?,
    private val deliver: (Result) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    /** A finished translation of [source] (exactly the text that was submitted) for [from]→[to]. */
    data class Result(val generation: Long, val from: String, val to: String, val source: String, val translation: String)

    private val generation = AtomicLong()

    // owned by the executor thread
    private var translator: Translator? = null

    @Volatile var from: String = ""; private set
    @Volatile var to: String = ""; private set

    /** True once the model for the current direction is loaded and usable. */
    @Volatile var ready = false; private set

    /** Switches direction (loads the model) and retranslates [text] if it is not empty. */
    fun setDirection(from: String, to: String, text: String) {
        this.from = from
        this.to = to
        ready = false
        val g = generation.incrementAndGet()
        executor.execute {
            try {
                val t = translator
                if (t == null || t.from != from || t.to != to) {
                    translator = null
                    t?.close()
                    // a later setDirection superseded this one: let it load its own model
                    if (from != this.from || to != this.to) return@execute
                    translator = load(from, to)
                }
                ready = translator != null
                if (g == generation.get() && text.isNotEmpty()) run(g, text)
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    /** New source text: translate it (an empty text yields an empty translation immediately). */
    fun submit(text: String) {
        val g = generation.incrementAndGet()
        executor.execute {
            try {
                run(g, text)
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    private fun run(g: Long, text: String) {
        if (g != generation.get()) return
        val t = translator ?: return
        val out = if (text.isBlank()) text else t.translate(text) { g != generation.get() } ?: return
        if (g == generation.get()) deliver(Result(g, t.from, t.to, text, out))
    }

    /** Is [r] the answer to the latest request? */
    fun isCurrent(r: Result) = r.generation == generation.get()

    /** Cancels pending work and frees the model (on the executor). */
    fun release() {
        generation.incrementAndGet()
        ready = false
        executor.execute {
            translator?.close()
            translator = null
        }
    }
}
