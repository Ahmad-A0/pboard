package dev.pboard.ime.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection
import dev.pboard.R
import dev.pboard.ime.theme.Dimens
import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Prefs
import dev.pboard.translate.LiveTranslation
import dev.pboard.translate.Translator
import dev.pboard.translate.Translators
import java.util.concurrent.Executors

/**
 * One open session of the translate bar (toolbar › Translate). The keyboard types into [connection] (the box);
 * every edit is translated on-device (debounced, newest wins) and the translation is written into the app's field at
 * its cursor as composing text, replacing the previous translation as a whole - Gboard's behaviour, measured on 18.6:
 *
 *  - a separating space is committed first when the character before the cursor is a letter;
 *  - the action key (a check mark) commits the translation, clears the box and keeps the bar open;
 *  - closing (back button, leaving the field, rotation) commits what is in the field and discards the box.
 *
 * Nothing typed here is stored: the translator's sentence cache lives in memory and is dropped with the model when
 * the bar closes. Language choices and recents are the only things persisted (prefs).
 */
class TranslateMode(
    private val ctx: Context,
    private val d: Dimens,
    private val colors: TranslateColors,
    private val host: Host,
    private val keyboardLanguage: String,
) {
    interface Host {
        /** The app's field (not the box). */
        val appConnection: InputConnection?
        fun boxView(): View
        /** Shows (or with null removes) a full-window overlay such as the language picker. */
        fun showOverlay(view: View?)
        /** Closes translate mode (the host calls [close]). */
        fun requestClose()
        fun openSettings(route: String)
        /** The box was cleared programmatically: restart the keyboard's input on it (resets composing and shift). */
        fun restartBox()
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = PboardPrefs.get(ctx)
    private val packs = TranslatePacks.get(ctx)
    private val directions: Set<LangPair> = try { packs.directions() } catch (e: Exception) { emptySet() }
    private var pair: LangPair? = TranslateLanguages.initial(directions, keyboardLanguage, prefs[Prefs.translateTarget])

    val bar = TranslateBarView(ctx, d, colors)
    val connection = TranslateInputConnection(host.boxView(), ::onBoxChanged, ::onDone)

    private val live = LiveTranslation(
        executor = EXECUTOR,
        load = { from, to -> load(from, to) },
        deliver = { r -> main.post { apply(r) } },
        onError = { e -> Log.w(TAG, "translation failed", e); main.post { if (open) bar.status = ctx.getString(R.string.translate_failed) } },
    )

    private var open = true
    /** Translation currently composing in the app's field, and the box text it translates. */
    private var shown = ""
    private var shownSource: String? = null
    /** A translation was inserted since the last commit (the separator rule is applied once per phrase). */
    private var phraseStarted = false
    /** Box text whose translation must be committed as soon as it arrives (Done or close came before it). */
    private var commitAfter: String? = null
    private var clearing = false
    private var statusTimeout: Runnable? = null
    private val commitTimeout = Runnable {
        if (commitAfter != null) {
            commitAfter = null
            commitField()
            if (!open) live.release()
        }
    }

    init {
        bar.listener = object : TranslateBarListener {
            override fun onTranslateClose() = host.requestClose()
            override fun onPickSource() = pick(source = true)
            override fun onPickTarget() = pick(source = false)
            override fun onSwap() = swap()
            override fun onStatusTap() { if (directions.isEmpty()) host.openSettings(SETTINGS_ROUTE) }
        }
        updateChips()
        val p = pair
        if (p == null) bar.status = ctx.getString(R.string.translate_no_packs)
        else live.setDirection(p.first, p.second, "")
    }

    private fun load(from: String, to: String): Translator? {
        val (pack, spec) = packs.find(from, to) ?: return null
        val t0 = System.nanoTime()
        return Translators.load(pack.storage, spec).also {
            Log.i(TAG, "loaded $from-$to from ${pack.location} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    private fun updateChips() {
        val p = pair
        bar.sourceLabel = TranslateLanguages.displayName(p?.first ?: keyboardLanguage)
        bar.targetLabel = p?.let { TranslateLanguages.displayName(it.second) } ?: ctx.getString(R.string.translate_language_none)
    }

    // ------------------------------------------------------------------ typing

    private val submitRunnable: Runnable = Runnable {
        // never cancel a translation that Done/close is waiting for
        if (commitAfter != null) main.postDelayed(submitRunnable, 50) else live.submit(connection.value)
    }

    private fun onBoxChanged(text: String, caret: Int) {
        bar.setText(text, caret)
        if (clearing) return
        main.removeCallbacks(submitRunnable)
        if (pair == null) return
        if (text.isEmpty() && commitAfter == null) live.submit("") // clears the field's translation right away
        else main.postDelayed(submitRunnable, DEBOUNCE_MS)
    }

    private fun apply(r: LiveTranslation.Result) {
        if (!live.isCurrent(r)) return
        val forCommit = commitAfter != null && r.source == commitAfter
        if (!forCommit && (!open || commitAfter != null || r.source != connection.value)) return
        val ic = host.appConnection ?: return
        if (r.translation != shown) {
            ic.beginBatchEdit()
            if (!phraseStarted && r.translation.isNotEmpty()) {
                if (TranslateLanguages.needsSeparator(ic.getTextBeforeCursor(1, 0))) ic.commitText(" ", 1)
                phraseStarted = true
            }
            ic.setComposingText(r.translation, 1)
            ic.endBatchEdit()
            shown = r.translation
            if (open) bar.announce(r.translation)
        }
        shownSource = r.source
        if (forCommit) {
            commitAfter = null
            main.removeCallbacks(commitTimeout)
            commitField()
            if (!open) live.release()
        }
    }

    /** Keeps what is in the field (ends the composing region) and starts a new phrase. */
    private fun commitField() {
        host.appConnection?.finishComposingText()
        shown = ""
        shownSource = null
        phraseStarted = false
    }

    /**
     * Commits the translation of the box as it is now: right away if the field already shows it, otherwise as soon as
     * the (immediately started) translation arrives, so Done or close never drop the last words typed.
     */
    private fun commitBox() {
        main.removeCallbacks(submitRunnable)
        val text = connection.value
        if (text.isEmpty() || text == shownSource || pair == null || !live.ready) {
            commitField()
            return
        }
        commitAfter = text
        live.submit(text)
        main.postDelayed(commitTimeout, COMMIT_WAIT_MS)
    }

    /** Action key: keep the translation in the field, clear the box, stay open. */
    private fun onDone() {
        main.post {
            if (!open) return@post
            commitBox()
            clearing = true
            connection.clear()
            clearing = false
            host.restartBox()
        }
    }

    // ------------------------------------------------------------------ languages

    private fun setPair(p: LangPair) {
        val old = pair
        pair = p
        prefs[Prefs.translateTarget] = p.second
        updateChips()
        if (old != p) live.setDirection(p.first, p.second, connection.value)
    }

    private fun swap() {
        val p = pair ?: return
        val s = TranslateLanguages.swapped(directions, p)
        if (s == null) {
            flashStatus(ctx.getString(R.string.translate_no_reverse, TranslateLanguages.displayName(p.second), TranslateLanguages.displayName(p.first)))
            return
        }
        setPair(s)
    }

    private fun flashStatus(text: String) {
        bar.status = text
        statusTimeout?.let { main.removeCallbacks(it) }
        statusTimeout = Runnable { if (open && pair != null) bar.status = null }.also { main.postDelayed(it, 2500) }
    }

    private fun pick(source: Boolean) {
        val p = pair
        if (p == null) { host.openSettings(SETTINGS_ROUTE); return }
        val available = if (source) TranslateLanguages.sources(directions) else TranslateLanguages.targets(directions, p.first)
        val current = if (source) p.first else p.second
        val recent = prefs[if (source) Prefs.translateRecentSources else Prefs.translateRecentTargets]
        val (top, rest) = TranslateLanguages.pickerRows(available, current, recent) { TranslateLanguages.displayName(it) }
        val title = ctx.getString(if (source) R.string.translate_from else R.string.translate_to)
        val picker = LanguagePickerView(ctx, d, colors, title, top, rest) { row ->
            host.showOverlay(null)
            val code = row?.code
            when {
                row == null -> {}
                code == null -> host.openSettings(SETTINGS_ROUTE)
                source -> {
                    prefs[Prefs.translateRecentSources] = TranslateLanguages.remember(recent, code)
                    setPair(TranslateLanguages.withSource(directions, p, code))
                }
                else -> {
                    prefs[Prefs.translateRecentTargets] = TranslateLanguages.remember(recent, code)
                    setPair(TranslateLanguages.withTarget(directions, p, code))
                }
            }
        }
        host.showOverlay(picker)
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The app restarted input on the same field (e.g. a chat app after Send). Like Gboard 18.6, Translate stays open
     * but starts over: the box is cleared and the next translation is a new phrase at the app's cursor (the app owns
     * whatever is in its field now).
     */
    fun onAppRestarted() {
        main.removeCallbacks(submitRunnable)
        main.removeCallbacks(commitTimeout)
        commitAfter = null
        shown = ""
        shownSource = null
        phraseStarted = false
        live.submit("") // cancels a translation in flight for the old text
        clearing = true
        connection.clear()
        clearing = false
        host.restartBox()
    }

    /** Commits the field's translation (waiting briefly for one still being computed) and frees the model. */
    fun close() {
        if (!open) return
        statusTimeout?.let { main.removeCallbacks(it) }
        commitBox()
        open = false
        if (commitAfter == null) live.release()
        host.showOverlay(null)
    }

    companion object {
        private const val TAG = "PboardTranslate"
        const val SETTINGS_ROUTE = "translate"

        /**
         * Pause after the last keystroke before translating. Gboard waits ~300 ms (then a network round trip); the
         * on-device model needs ~50 ms per sentence, so a shorter pause gives a faster result with the same calm
         * (no half-typed words flashing in the field).
         */
        const val DEBOUNCE_MS = 200L

        /** Longest wait for a translation that Done/close asked to commit. */
        private const val COMMIT_WAIT_MS = 1500L

        /** One background thread for all translation work (model loading and decoding). */
        private val EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "pboard-translate").apply { isDaemon = true } }
    }
}
