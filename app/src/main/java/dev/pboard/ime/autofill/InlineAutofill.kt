package dev.pboard.ime.autofill

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.inline.InlineContentView
import androidx.annotation.RequiresApi
import dev.pboard.ime.config.Config
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyboardColors
import dev.pboard.ime.view.KeyboardRoot
import dev.pboard.ime.view.StripItem
import dev.pboard.ime.view.TopBarView
import java.util.Locale

/**
 * Android 11+ inline autofill suggestions in the strip (CX-14), Gboard 18.6's behaviour as measured with a test
 * autofill service on the emulator:
 *
 * * pboard asks for up to 9 chips styled like Gboard's ([InlineChipStyle]); none while TalkBack's touch exploration
 *   is on (Gboard does the same: the system falls back to its drop-down) or with the suggestion strip hidden.
 * * Chips appear when the service answers (the toolbar shows until then), in the service's order; pinned chips go to
 *   the voice key's place ([InlineStripView]).
 * * Word suggestions win: while the strip shows typed-word candidates or predictions the chips are hidden (Gboard drops
 *   them as soon as a letter is typed); a recently-copied chip does not hide them.
 * * An answer with no chips clears them, but not before they have been visible for 4 s; a new field clears them at once.
 * * The first time chips appear, a one-time bubble says "Autofill suggestions are now above your keyboard".
 *
 * The chips are views rendered by the autofill service; pboard never sees their content, and tapping one is handled
 * by the system (it fills the field directly).
 */
@RequiresApi(Build.VERSION_CODES.R)
class InlineAutofill(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var root: KeyboardRoot? = null
    private var bar: TopBarView? = null
    private var dims: Dimens? = null
    private var colors: KeyboardColors? = null
    private var strip: InlineStripView? = null
    private var tooltip: InlineTooltipView? = null
    private var chips: List<View> = emptyList()
    private var pinned: List<View> = emptyList()
    /** Chips from pboard itself (recently copied clip, its entities, undo), shown when there are no autofill chips. */
    private var localChips: List<View> = emptyList()
    private var rtl = false
    private var wordsShown = false
    private var shownAt = 0L
    private var generation = 0
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { place() }

    val hasChips get() = chips.isNotEmpty() || pinned.isNotEmpty()

    /** Called with every rebuilt keyboard view: the chips move to the new bar. */
    fun attach(root: KeyboardRoot, bar: TopBarView, dims: Dimens, colors: KeyboardColors) {
        this.root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        strip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        tooltip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        strip = null
        tooltip = null
        this.root = root
        this.bar = bar
        this.dims = dims
        this.colors = colors
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        if (hasChips || localChips.isNotEmpty()) show()
    }

    /** The request for the field about to start, or null to get no inline suggestions. */
    fun createRequest(d: Dimens, c: KeyboardColors, rtl: Boolean, locale: Locale?): InlineSuggestionsRequest? {
        if (!Config.current.suggestionStripVisible) return null
        val a11y = context.getSystemService(AccessibilityManager::class.java)
        if (a11y?.isEnabled == true && a11y.isTouchExplorationEnabled) return null
        this.rtl = rtl
        val locales = if (locale != null) LocaleList(locale) else LocaleList.getEmptyLocaleList()
        return runCatching { InlineChipStyle.request(context, d, c, rtl, locales) }
            .onFailure { android.util.Log.w(TAG, "inline request", it) }.getOrNull()
    }

    /** The service's answer: inflates every chip, then shows them together. */
    fun onResponse(response: InlineSuggestionsResponse): Boolean {
        val d = dims ?: return false
        val list = response.inlineSuggestions
        val gen = ++generation
        if (list.isEmpty()) { clearSoon(); return true }
        main.removeCallbacks(clearRunnable)
        val views = arrayOfNulls<InlineContentView>(list.size)
        var pending = list.size
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(d.barHeight))
        for ((i, s) in list.withIndex()) {
            s.inflate(context, size, context.mainExecutor) { v ->
                views[i] = v
                if (--pending == 0 && gen == generation) {
                    val ok = list.indices.filter { views[it] != null }
                    setChips(
                        ok.filter { !list[it].info.isPinned }.map { views[it]!! },
                        ok.filter { list[it].info.isPinned }.map { views[it]!! },
                    )
                }
            }
        }
        return true
    }

    /**
     * pboard's own chips in the same row (dev.pboard.ime.clipboard): laid out like autofill chips, which take
     * precedence. An empty list removes them.
     */
    fun setLocalChips(views: List<View>) {
        localChips = views
        if (hasChips) return
        if (views.isEmpty()) { strip?.setChips(emptyList(), emptyList(), rtl); place() } else show()
    }

    /** Strip content changed: typed-word candidates and predictions hide the chips, a clipboard chip does not. */
    fun onStripChanged(items: List<StripItem?>?) {
        val words = items?.any { it != null && !it.clip } == true
        if (words == wordsShown) return
        wordsShown = words
        place()
    }

    /**
     * A new inline request (input started on a field): the chips shown belong to the previous answer. Inflations still
     * in flight are kept: the system may ask again right after answering (input restarted on the same field) and does
     * not answer a second time.
     */
    fun clear() {
        main.removeCallbacks(clearRunnable)
        if (hasChips) setChips(emptyList(), emptyList())
    }

    /** Adds the tooltip (which sits above the keyboard) to the IME window's touchable region. */
    fun addTouchable(region: android.graphics.Region) {
        val t = tooltip ?: return
        if (t.visibility != View.VISIBLE || !t.isLaidOut) return
        val x = t.translationX.toInt()
        val y = t.translationY.toInt()
        region.op(x, y, x + t.width, y + t.height, android.graphics.Region.Op.UNION)
    }

    private val clearRunnable = Runnable { setChips(emptyList(), emptyList()) }

    private fun clearSoon() {
        val left = MIN_DISPLAY_MS - (SystemClock.uptimeMillis() - shownAt)
        main.removeCallbacks(clearRunnable)
        if (shownAt == 0L || left <= 0 || !hasChips) clearRunnable.run() else main.postDelayed(clearRunnable, left)
    }

    private fun setChips(chips: List<View>, pinned: List<View>) {
        this.chips = chips
        this.pinned = pinned
        if (hasChips) {
            shownAt = SystemClock.uptimeMillis()
            show()
        } else {
            shownAt = 0L
            dismissTooltip()
            if (localChips.isNotEmpty()) { show(); return }
            strip?.setChips(emptyList(), emptyList(), rtl)
            place()
        }
    }

    private fun show() {
        val r = root ?: return
        val d = dims ?: return
        val c = colors ?: return
        val s = strip ?: InlineStripView(context, d, c).also { v ->
            strip = v
            // Above the keyboard, below the overlay (previews, trails).
            r.addView(v, r.indexOfChild(r.overlay).coerceAtLeast(0), FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        }
        if (hasChips) {
            s.setChips(chips, pinned, rtl)
            maybeShowTooltip()
        } else {
            s.setChips(localChips, emptyList(), rtl)
        }
        place()
    }

    /** Puts the strip over the bar (and the tooltip above it), or hides them. */
    private fun place() {
        val s = strip ?: return
        val r = root ?: return
        val b = bar
        val visible = (hasChips || localChips.isNotEmpty()) && !wordsShown && b != null && b.isAttachedToWindow && b.isShown && !b.expanded &&
            b.width > 0 && b.parent != null
        if (!visible) {
            if (s.visibility != View.GONE) s.visibility = View.GONE
            tooltip?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
            b?.showVoice = true
            return
        }
        val bl = IntArray(2).also { b!!.getLocationInWindow(it) }
        val rl = IntArray(2).also { r.getLocationInWindow(it) }
        val x = (bl[0] - rl[0]).toFloat()
        val y = (bl[1] - rl[1]).toFloat()
        val lp = s.layoutParams
        if (lp.width != b!!.width || lp.height != b.height) {
            lp.width = b.width; lp.height = b.height
            s.layoutParams = lp
        }
        if (s.translationX != x) s.translationX = x
        if (s.translationY != y) s.translationY = y
        if (s.visibility != View.VISIBLE) s.visibility = View.VISIBLE
        val hideVoice = s.hasPinned
        if (b.showVoice == hideVoice) b.showVoice = !hideVoice
        tooltip?.let { t ->
            val d = dims ?: return@let
            val vx = x + d.m(134f)
            val tw = t.bubbleWidth(r.width - vx.toInt()).toInt()
            val tlp = t.layoutParams
            if (tlp.width != tw) { tlp.width = tw; t.layoutParams = tlp }
            // Pointer tip 17 px (420 dpi) above the strip.
            t.translationX = vx
            t.translationY = y - d.m(17f) - t.tipY
            if (t.visibility != View.VISIBLE) t.visibility = View.VISIBLE
        }
    }

    private fun maybeShowTooltip() {
        if (tooltip != null) return
        val r = root ?: return
        val d = dims ?: return
        val c = colors ?: return
        // A private state file, not dev.pboard.prefs: a settings change would rebuild the keyboard. Unavailable before
        // the first unlock (credential-encrypted storage): no bubble then.
        val state = runCatching { context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE) }.getOrNull() ?: return
        val shown = state.getInt(KEY_TIP_COUNT, 0)
        if (shown >= TIP_MAX_SHOWN) return
        state.edit().putInt(KEY_TIP_COUNT, shown + 1).apply()
        val t = InlineTooltipView(context, d, c).apply { onClose = { dismissTooltip() } }
        tooltip = t
        r.addView(t, r.indexOfChild(r.overlay).coerceAtLeast(0), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        t.post { place() }
    }

    private fun dismissTooltip() {
        val t = tooltip ?: return
        tooltip = null
        (t.parent as? ViewGroup)?.removeView(t)
    }

    companion object {
        /** Chips stay visible at least this long before an empty answer clears them (as Gboard 18.6). */
        const val MIN_DISPLAY_MS = 4000L
        private const val TAG = "PboardAutofill"
        /** The education bubble is shown once. */
        const val TIP_MAX_SHOWN = 1
        private const val STATE_FILE = "autofill_state"
        private const val KEY_TIP_COUNT = "tip_count"
    }
}
