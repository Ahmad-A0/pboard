package dev.pboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.pboard.engine.TouchPoint
import dev.pboard.ime.ImeRules
import dev.pboard.ime.config.Config
import dev.pboard.ime.layout.BuiltKeyboard
import dev.pboard.ime.layout.Codes
import dev.pboard.ime.layout.Key
import dev.pboard.ime.layout.Page
import dev.pboard.ime.theme.Dimens
import kotlin.math.abs
import kotlin.math.hypot

/** Everything the key surface reports. Implemented by the input controller. */
interface KeyboardActionListener {
    /** A key went down (feedback: haptics/sound). */
    fun onPress(key: Key)
    /** A character key was typed (tap). [x], [y] are key-surface coordinates of the touch. */
    fun onCharacter(key: Key, text: String, x: Float, y: Float, time: Long)
    /** A function key (enter, ?123, ABC, emoji, …) was released. Space arrives here too. */
    fun onFunction(key: Key)
    /** Shift touched down at [downTime] (the touch event's time, uptime ms). */
    fun onShiftPress(downTime: Long)
    /** Shift released at [upTime] (the touch event's time, uptime ms). */
    fun onShiftRelease(chorded: Boolean, upTime: Long)
    /**
     * Shift released at ([x], [y]) (key-surface coordinates): inside a word a tap on its edge may be a letter it
     * borders (TF-01); otherwise as [onShiftRelease].
     */
    fun onShiftTap(chorded: Boolean, x: Float, y: Float, upTime: Long) = onShiftRelease(chorded, upTime)
    /** One delete step; [word] for accelerated repeat. */
    fun onDelete(word: Boolean)
    /**
     * A tap on the space bar released at ([x], [y]) (key-surface coordinates, [time] the touch event's time): inside a
     * word the engine may resolve it to a neighbouring letter (TF-01).
     */
    fun onSpaceTap(key: Key, x: Float, y: Float, time: Long) = onFunction(key)
    /** A tap (not a hold) on the delete key released at ([x], [y]); like [onSpaceTap]. */
    fun onDeleteTap(x: Float, y: Float, time: Long) = onDelete(false)
    /** A held delete starts repeating (before its first repeat step) and ends (the key is released). */
    fun onDeleteHoldStart() {}
    fun onDeleteHoldEnd() {}
    /** Swipe-left-from-delete selection, measured in words (0 = nothing selected). */
    fun onDeleteSwipe(words: Int)
    fun onDeleteSwipeEnd(commit: Boolean)
    /** Space-bar cursor drag by [delta] characters. */
    fun onCursorMove(delta: Int)
    fun onCursorMoveEnd()
    /** Alternate chosen from a long-press panel. */
    fun onPopupItem(key: Key, item: String)
    /** Long press on a key without alternates (e.g. space opens the language picker). */
    fun onLongPress(key: Key)
    fun onGestureStart()
    fun onGestureUpdate(path: List<TouchPoint>)
    fun onGestureEnd(path: List<TouchPoint>)
    /** A page key was held, slid onto a key of the new page and released there: return to the page before. */
    fun onMomentaryRelease(pageKeyCode: Int)
    /** Whether gesture typing is currently possible (letters page, text field). */
    fun gestureAllowed(): Boolean
}

/**
 * The key surface: one hardware-accelerated View that draws every key from a cached RenderNode and handles all
 * touch input (multi-touch rollover, previews, long-press panels, gesture typing, cursor and delete swipes).
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, val d: Dimens, val overlay: OverlayView) : View(context) {
    var listener: KeyboardActionListener? = null
    lateinit var painter: KeyPainter

    var keyboard: BuiltKeyboard? = null
        set(value) {
            // A page key held down keeps its pointer across the page change (momentary symbols).
            val keep = momentary
            cancelTrackers(except = keep)
            field = value
            invalidateKeys()
            requestLayout()
            accessibility.invalidateRoot()
        }

    /** TalkBack: keys as virtual views, explore-by-touch and lift-to-type. */
    val accessibility = KeyboardAccessibility(this)

    /**
     * The key caps, recorded once per layout/shift change and kept as a GPU layer: every other frame (presses,
     * previews, the window repainting around them) composites one texture instead of re-rasterising every cap and
     * label. On software GL (the emulator) that re-rasterisation was most of the touch-to-frame time.
     */
    private val keysNode = RenderNode("keys").apply { setUseCompositingLayer(true, null) }
    private var keysDirty = true
    private var dimmed = false

    private val handler = Handler(Looper.getMainLooper())
    private val trackers = ArrayList<Tracker>(4)

    /** Keys currently drawn pressed, and whether each is "lifted" into a preview bubble. */
    private val pressed = HashMap<Key, Boolean>()

    init {
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun invalidateKeys() {
        keysDirty = true
        invalidate()
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(e: MotionEvent): Boolean = accessibility.onHover(e) || super.dispatchHoverEvent(e)

    /** Types [key] as a complete tap (TalkBack lift-to-type or double-tap). */
    fun performKeyFromAccessibility(key: Key) {
        val l = listener ?: return
        cancelAllTrackers()
        val pageBefore = keyboard?.page
        val shiftBefore = painter.shiftState
        l.onPress(key)
        val time = SystemClock.uptimeMillis()
        when {
            key.code == Codes.SHIFT -> { l.onShiftPress(time); l.onShiftRelease(chorded = false, upTime = time) }
            key.code == Codes.DELETE -> l.onDelete(false)
            key.code > 0 && key.code != Codes.SPACE -> {
                val c = engineTouch(key.rect.centerX(), key.rect.centerY(), time)
                l.onCharacter(key, key.text(painter.shiftState != 0), c.x, c.y, time)
            }
            else -> l.onFunction(key)
        }
        accessibility.announceModeChange(key, pageBefore, shiftBefore)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, keyboard?.height ?: 0)
    }

    // ---------------------------------------------------------------- drawing

    /** Event time of the last touch-down not yet drawn (for touch-to-draw latency logging). */
    private var pendingDownTime = 0L

    /** Touch-to-draw logging runs in the window's draw pass (presses no longer redraw this view). */
    private val drawListener = android.view.ViewTreeObserver.OnDrawListener {
        if (pendingDownTime != 0L) {
            LatencyLog.touchToDraw(SystemClock.uptimeMillis() - pendingDownTime)
            pendingDownTime = 0L
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnDrawListener(drawListener)
        // Re-attached after a panel (the "more" grid, emoji, clipboard...) replaced the keys: the platform dropped the
        // cached keys node's content while it was out of the tree, so record it again.
        keysDirty = true
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnDrawListener(drawListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val kb = keyboard ?: return
        if (canvas.isHardwareAccelerated) {
            // HWUI deletes a RenderNode's display list when the node leaves the drawn tree for a frame (this view
            // detached while a panel shows) or when the window's hardware resources are destroyed (hidden, trimmed).
            // A View re-records its own node then; this one is ours, so check it too, or the keys draw as nothing
            // while touches still type (the blank keyboard after closing a panel).
            if (keysDirty || !keysNode.hasDisplayList()) {
                keysNode.setPosition(0, 0, width, height)
                val rc = keysNode.beginRecording()
                drawKeys(rc, kb)
                keysNode.endRecording()
                keysDirty = false
            }
            keysNode.setAlpha(if (dimmed) DIM_ALPHA else 1f)
            canvas.drawRenderNode(keysNode)
        } else {
            if (dimmed) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (DIM_ALPHA * 255).toInt())
            drawKeys(canvas, kb)
            if (dimmed) canvas.restore()
        }
        if (pressLayer != null && canvas.isHardwareAccelerated) return
        for ((key, lifted) in pressed) {
            if (lifted) {
                // The key "lifts" into its preview: the cap disappears and the label fades.
                painter.drawBackground(canvas, key, painter.colors.background)
                painter.drawForeground(canvas, key, alpha = LIFTED_LABEL_ALPHA)
            } else {
                painter.drawKey(canvas, key, pressed = true)
            }
        }
    }

    private fun drawKeys(canvas: Canvas, kb: BuiltKeyboard) {
        for (k in kb.keys) painter.drawKey(canvas, k, pressed = false)
    }

    /** Pressed keys drawn in their own small views (see [PressLayer]); null draws them in this view. */
    var pressLayer: PressLayer? = null

    private val rootLoc = IntArray(2)

    private fun setPressed(key: Key, on: Boolean, lifted: Boolean = false) {
        if (on) pressed[key] = lifted else pressed.remove(key)
        val layer = pressLayer
        if (layer != null && isHardwareAccelerated) {
            if (on) {
                getLocationInWindow(loc)
                (parent?.parent as? View)?.getLocationInWindow(rootLoc)
                layer.showCap(key, lifted, (loc[0] - rootLoc[0]).toFloat(), (loc[1] - rootLoc[1]).toFloat())
            } else {
                layer.hideCap(key)
            }
            return
        }
        invalidate()
    }

    /** Dimming changed (long-press panel open): Gboard dims the toolbar row with the keys. */
    var onDimChanged: ((Boolean) -> Unit)? = null

    private fun setDimmed(on: Boolean) {
        if (dimmed != on) { dimmed = on; invalidate(); onDimChanged?.invoke(on) }
    }

    // ---------------------------------------------------------------- overlay coordinates

    private val loc = IntArray(2)
    private val overlayLoc = IntArray(2)

    private fun toOverlayX(x: Float): Float { getLocationInWindow(loc); overlay.getLocationInWindow(overlayLoc); return x + loc[0] - overlayLoc[0] }
    private fun toOverlayY(y: Float): Float { getLocationInWindow(loc); overlay.getLocationInWindow(overlayLoc); return y + loc[1] - overlayLoc[1] }

    // ---------------------------------------------------------------- touch

    private enum class Mode { TAP, PANEL, GESTURE, CURSOR, DELETE_SWIPE, MOMENTARY, DONE }

    /** Tracker of a page key (?123, ABC, =\<, ...) still held after it switched the page on touch-down. */
    private var momentary: Tracker? = null

    private inner class Tracker(val id: Int) {
        var key: Key? = null
        var mode = Mode.TAP
        var downX = 0f
        var downY = 0f
        var downT = 0L
        var lastX = 0f
        var lastY = 0f
        var preview: OverlayView.Preview? = null
        var panel: PopupPanel? = null
        val path = ArrayList<TouchPoint>(128)
        /** Space scrub: the travel reached a step before the hold time allowed the scrub (it then starts at once). */
        var scrubTravelledEarly = false
        var scrub: ImeRules.Scrub? = null
        var deleteWords = 0
        var repeats = 0
        var shiftChorded = false
        var lastGestureUpdate = 0L
        /** Touch-down came within 150 ms of the previous key: never becomes a cursor/delete scrub. */
        var recentTyping = false
        var momentaryFrom = 0
        /** The tap started on shift and slid onto a letter. */
        var fromShift = false
        /** Shift state when this touch went down (TalkBack announcements). */
        var shiftAtDown = 0

        val longPress = Runnable { onLongPressTimeout(this) }
        val repeat = Runnable { onRepeat(this) }
    }

    private fun tracker(id: Int) = trackers.firstOrNull { it.id == id }

    private var lastDownTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = handleTouch(e, forwarded = false)

    /** A touch the suggestion bar hands over (in this view's coordinates): see [TouchRouting.barTouchToKeys]. */
    fun handOffTouch(e: MotionEvent): Boolean = handleTouch(e, forwarded = true)

    private fun handleTouch(e: MotionEvent, forwarded: Boolean): Boolean {
        val kb = keyboard ?: return false
        // Watchdog: the first finger down with trackers still live means an up or cancel was lost; reset them. (A
        // touch the bar hands over is another stream: fingers on the keys may legitimately be down.)
        if (!forwarded && TouchRouting.staleOnFirstDown(e.actionMasked == MotionEvent.ACTION_DOWN, trackers.count { it.mode != Mode.DONE })) {
            cancelAllTrackers()
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (LatencyLog.enabled) pendingDownTime = e.eventTime
                val i = e.actionIndex
                down(kb, e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = tracker(e.getPointerId(i)) ?: continue
                    // Use historical samples too: gesture paths need the full resolution.
                    for (h in 0 until e.historySize) {
                        move(kb, t, e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
                    }
                    move(kb, t, e.getX(i), e.getY(i), e.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                tracker(e.getPointerId(i))?.let { up(it, e.getX(i), e.getY(i), e.eventTime) }
            }
            MotionEvent.ACTION_CANCEL -> cancelAllTrackers()
        }
        return true
    }

    private fun down(kb: BuiltKeyboard, id: Int, x: Float, y: Float, time: Long) {
        // An active gesture owns the surface; extra fingers are ignored (as in Gboard).
        if (trackers.any { it.mode == Mode.GESTURE }) return
        // Rollover: a new finger commits any key still held by another finger.
        for (other in trackers) {
            if (other.mode == Mode.TAP && other.key?.code != Codes.SHIFT && other.key?.code != Codes.DELETE) {
                commitTap(other, other.lastX, other.lastY, time)
                finish(other)
            } else if (other.key?.code == Codes.SHIFT) {
                other.shiftChorded = true
                handler.removeCallbacks(other.longPress)
            }
        }
        trackers.removeAll { it.mode == Mode.DONE }
        val key = kb.keyAt(x, y) ?: return
        val t = Tracker(id)
        t.key = key
        t.recentTyping = time - lastDownTime < SCRUB_GUARD_MS
        lastDownTime = time
        t.downX = x; t.downY = y; t.downT = time; t.lastX = x; t.lastY = y
        t.shiftAtDown = painter.shiftState
        trackers += t
        listener?.onPress(key)
        if (key.code in PAGE_KEYS) {
            // Page keys act on touch-down (Gboard); sliding onto a key of the new page and lifting types it and
            // returns to the previous page.
            t.mode = Mode.MOMENTARY
            t.key = null
            t.momentaryFrom = key.code
            momentary = t
            listener?.onFunction(key)
            momentary = null
            return
        }
        pressKey(t, key)
        when (key.code) {
            Codes.SHIFT -> listener?.onShiftPress(time)
            // Gboard 18.6 (conformance harness, exact timestamps): a tap deletes on release; a held key starts repeating
            // ~420 ms after touch-down. Nothing is deleted on touch-down, so a swipe from the key deletes only the swiped
            // words.
            Codes.DELETE -> handler.postAtTime(t.repeat, t.downT + REPEAT_START_MS)
        }
        scheduleLongPress(t, key)
        if (key.isLetter) t.path += TouchPoint(x, y, time)
    }

    private fun pressKey(t: Tracker, key: Key) {
        val showPreview = Config.current.popupOnKeypress && painter.hasPreview(key)
        setPressed(key, true, lifted = showPreview)
        if (showPreview) {
            val label = key.label(painter.shiftState != 0)
            t.preview = overlay.showPreview(
                key, label, toOverlayX(key.rect.centerX()),
                toOverlayY(key.rect.top - d.previewGap - d.previewDiameter / 2f),
                hasMore = key.spec.popup.isNotEmpty(),
            )
        }
    }

    private fun releaseKey(t: Tracker) {
        t.key?.let { setPressed(it, false) }
        overlay.dismissPreview(t.preview)
        t.preview = null
    }

    private fun scheduleLongPress(t: Tracker, key: Key) {
        handler.removeCallbacks(t.longPress)
        val wants = key.spec.popup.isNotEmpty() || key.code == Codes.SYMBOLS ||
            key.code == Codes.EMOJI || key.code == Codes.SHIFT || key.code == Codes.LANGUAGE
        // Shift locks on a long press after 1200 ms (Gboard layout value); other keys use the setting (300 ms).
        val delay = if (key.code == Codes.SHIFT) SHIFT_LOCK_DELAY_MS else Config.current.longPressDelayMs.toLong()
        if (wants) handler.postDelayed(t.longPress, delay)
    }

    private fun move(kb: BuiltKeyboard, t: Tracker, x: Float, y: Float, time: Long) {
        t.lastX = x; t.lastY = y
        // Sliding a tap (or a momentary page slide) off the top of the keyboard cancels it: lifting there types
        // nothing (Gboard). Panels, glides and scrubs have their own bounds.
        val offKeyboard = y < -d.barHeight
        if (offKeyboard && (t.mode == Mode.TAP || t.mode == Mode.MOMENTARY) && t.key?.code != Codes.SHIFT) {
            handler.removeCallbacks(t.longPress)
            handler.removeCallbacks(t.repeat)
            releaseKey(t)
            t.key = null
            if (t.mode == Mode.TAP) t.mode = Mode.DONE
            return
        }
        if (t.mode == Mode.MOMENTARY) {
            if (hypot(x - t.downX, y - t.downY) < d.rowPitch * 0.3f && t.key == null) return
            val now = kb.keyAt(x, y)
            if (now !== t.key) {
                if (t.key != null) releaseKey(t)
                t.key = now?.takeIf { it.code > 0 }
                t.key?.let { pressKey(t, it) }
            }
            return
        }
        val key = t.key ?: return
        val kw = d.rowPitch * 0.69f // ~ a letter key width, density independent
        // Before a long press fires, moving 0.3 inch away from the key cancels it.
        if (t.mode == Mode.TAP && (abs(x - key.rect.centerX()) > key.rect.width() / 2f + d.longPressSlop ||
                abs(y - key.rect.centerY()) > key.rect.height() / 2f + d.longPressSlop)) {
            handler.removeCallbacks(t.longPress)
        }
        when (t.mode) {
            Mode.TAP -> {
                val dx = x - t.downX
                val dy = y - t.downY
                if (key.isLetter) t.path += TouchPoint(x, y, time)
                val held = time - t.downT
                val scrubOk = !t.recentTyping && held >= SCRUB_HOLD_MS
                if (key.code == Codes.SPACE && !scrubOk && abs(dx) >= d.scrubStart) t.scrubTravelledEarly = true
                when {
                    key.code == Codes.SPACE && Config.current.gestureCursorControl && scrubOk &&
                        abs(dx) >= d.scrubStart -> {
                        handler.removeCallbacks(t.longPress)
                        t.mode = Mode.CURSOR
                        // The steps already travelled move the cursor at once, one character each.
                        val scrub = ImeRules.Scrub(d.scrubStep, d.dp(ImeRules.Scrub.FAST_DP_PER_MS))
                        t.scrub = scrub
                        val steps = scrub.start(dx, x, time, t.downT, t.scrubTravelledEarly)
                        releaseKey(t)
                        setPressed(key, true)
                        if (steps != 0) listener?.onCursorMove(steps)
                    }
                    key.code == Codes.DELETE && Config.current.gestureDelete && scrubOk && dx <= -d.scrubStart &&
                        !key.rect.contains(x, y) -> {
                        handler.removeCallbacks(t.repeat)
                        t.mode = Mode.DELETE_SWIPE
                        t.deleteWords = 0
                        move(kb, t, x, y, time)
                    }
                    // No glide typing under TalkBack's explore by touch (as Gboard): a slide moves to the next key.
                    key.isLetter && trackers.size == 1 && Config.current.gestureTyping && !accessibility.active &&
                        listener?.gestureAllowed() == true && hypot(dx, dy) > d.glideThreshold -> {
                        handler.removeCallbacks(t.longPress)
                        t.mode = Mode.GESTURE
                        releaseKey(t)
                        overlay.trailBegin()
                        if (Config.current.gestureTrail) for (p in t.path) overlay.trailAdd(toOverlayX(p.x), toOverlayY(p.y), p.t)
                        listener?.onGestureStart()
                    }
                    key.code == Codes.SHIFT && abs(dy) + abs(dx) > kw * 0.5f &&
                        kb.keyUnder(x, y)?.takeIf { it.isLetter } != null -> {
                        // Slide from shift onto a letter: types one capital, then shift releases (Gboard TF-07).
                        // Only a letter actually under the finger counts: a jump off the keyboard must not snap to
                        // the nearest key.
                        handler.removeCallbacks(t.longPress)
                        val now = kb.keyUnder(x, y)!!
                        releaseKey(t)
                        t.key = now
                        t.fromShift = true
                        pressKey(t, now)
                    }
                    else -> {
                        val now = kb.keyAt(x, y)
                        if (now != null && now !== key && key.code > 0 && key.code != Codes.SPACE &&
                            now.code > 0 && now.code != Codes.SPACE && abs(dy) + abs(dx) > kw * 0.3f
                        ) {
                            // Sliding between character keys: the key under the finger is the one typed on release.
                            // The path keeps its start: if this turns into a glide, the decoder needs the touch-down
                            // (clearing it here made glides that start near a key edge lose their first letter, a main
                            // cause of the HWS head-to-head gap, docs/swipe-neural.md).
                            releaseKey(t)
                            t.key = now
                            pressKey(t, now)
                            scheduleLongPress(t, now)
                        }
                    }
                }
            }
            Mode.PANEL -> {
                val p = t.panel ?: return
                val before = p.selected
                val ox = toOverlayX(x)
                val oy = toOverlayY(y)
                // Far away from the panel (1.5 cells beyond it) nothing is selected and lifting types nothing.
                val r = p.rect
                val limit = d.popupCell * PANEL_DESELECT_RATIO
                if (ox < r.left - limit || ox > r.right + limit || oy < r.top - limit || oy > r.bottom + limit + key.rect.height()) {
                    p.selected = -1
                } else {
                    p.select(ox, oy)
                }
                if (p.selected != before) overlay.panelChanged()
            }
            Mode.GESTURE -> {
                t.path += TouchPoint(x, y, time)
                // Points age from their event time (the touch), so the trail fades exactly like Gboard's.
                if (Config.current.gestureTrail) overlay.trailAdd(toOverlayX(x), toOverlayY(y), time)
                if (time - t.lastGestureUpdate >= GESTURE_UPDATE_MS) {
                    t.lastGestureUpdate = time
                    listener?.onGestureUpdate(ArrayList(keyboard?.toEngine(t.path) ?: t.path))
                }
            }
            Mode.CURSOR -> {
                if (outOfScrubBand(key, y)) { endScrub(t); return }
                // Steps of 8 pt from the touch-down point (moving back reverses); one or two characters per step
                // (Gboard 18.6, see [ImeRules.Scrub]).
                val delta = t.scrub?.move(x - t.downX, x, time) ?: 0
                if (delta != 0) listener?.onCursorMove(delta)
            }
            Mode.DELETE_SWIPE -> {
                if (outOfScrubBand(key, y)) {
                    listener?.onDeleteSwipeEnd(false)
                    finish(t)
                    return
                }
                val travel = t.downX - x
                val words = when {
                    travel < d.deleteSwipeFirst -> 0
                    travel < d.deleteSwipeSecond -> 1
                    else -> 2 + ((travel - d.deleteSwipeSecond) / d.deleteSwipeStep).toInt()
                }
                if (words != t.deleteWords) {
                    t.deleteWords = words
                    listener?.onDeleteSwipe(words)
                }
            }
            Mode.MOMENTARY, Mode.DONE -> {}
        }
    }

    /** Scrubs stay active within the key's row extended by 4 mm above and below. */
    private fun outOfScrubBand(key: Key, y: Float) = y < key.rect.top - d.scrubBand || y > key.rect.bottom + d.scrubBand

    private fun endScrub(t: Tracker) {
        listener?.onCursorMoveEnd()
        finish(t)
    }

    private fun up(t: Tracker, x: Float, y: Float, time: Long) {
        handler.removeCallbacks(t.longPress)
        handler.removeCallbacks(t.repeat)
        when (t.mode) {
            Mode.MOMENTARY -> {
                val k = t.key
                if (k != null) {
                    commitTap(t, x, y, time)
                    releaseKey(t)
                    t.mode = Mode.DONE
                    trackers.remove(t)
                    listener?.onMomentaryRelease(t.momentaryFrom)
                    return
                }
            }
            Mode.TAP -> commitTap(t, x, y, time)
            Mode.PANEL -> {
                val p = t.panel
                val key = t.key
                if (p != null && key != null && p.selected in p.items.indices) listener?.onPopupItem(key, p.items[p.selected])
                overlay.hidePanel()
                setDimmed(false)
                if (accessibility.active) accessibility.announce("Alternatives are dismissed")
            }
            Mode.GESTURE -> {
                t.path += TouchPoint(x, y, time)
                overlay.trailEnd()
                listener?.onGestureEnd(ArrayList(keyboard?.toEngine(t.path) ?: t.path))
            }
            Mode.CURSOR -> listener?.onCursorMoveEnd()
            Mode.DELETE_SWIPE -> listener?.onDeleteSwipeEnd(t.deleteWords > 0)
            Mode.DONE -> {}
        }
        finish(t)
        trackers.remove(t)
    }

    /** A touch in the engine's coordinates (a split layout maps it into its unsplit twin, see [BuiltKeyboard.toEngine]). */
    private fun engineTouch(x: Float, y: Float, time: Long): TouchPoint =
        keyboard?.toEngine(TouchPoint(x, y, time)) ?: TouchPoint(x, y, time)

    private fun commitTap(t: Tracker, tx: Float, ty: Float, time: Long) {
        val key = t.key ?: return
        val l = listener ?: return
        val e = engineTouch(tx, ty, time)
        val x = e.x
        val y = e.y
        if (accessibility.active) a11yShift(t, key)
        when {
            key.code == Codes.SHIFT -> l.onShiftTap(t.shiftChorded, x, y, time)
            key.code == Codes.DELETE -> if (t.repeats == 0) l.onDeleteTap(x, y, time) else l.onDeleteHoldEnd()
            key.code == Codes.SPACE -> l.onSpaceTap(key, x, y, time)
            key.code > 0 && key.code != Codes.SPACE -> {
                l.onCharacter(key, key.text(painter.shiftState != 0), x, y, time)
                if (t.fromShift) l.onShiftRelease(chorded = true, upTime = time)
            }
            else -> l.onFunction(key)
        }
    }

    /** Shift turned on or locked by the shift key and not yet released by a letter (TalkBack announcements). */
    private var a11yShiftOn = false

    /**
     * TalkBack with real touches (as Gboard, measured on 15.1; LT-09): the shift key announces "Shift enabled" / "Caps lock
     * enabled" / "Shift disabled", and the letter that releases a manual shift announces "Shift disabled"; automatic
     * capitalisation stays silent. Runs before the tap is committed; the announcement follows the new state.
     */
    private fun a11yShift(t: Tracker, key: Key) {
        if (key.code == Codes.SHIFT) {
            post {
                val now = painter.shiftState
                if (now != t.shiftAtDown) accessibility.announceModeChange(key, keyboard?.page, t.shiftAtDown)
                a11yShiftOn = now != 0
            }
        } else if (a11yShiftOn && key.isLetter) {
            post {
                if (painter.shiftState == 0) accessibility.announce("Shift disabled")
                if (painter.shiftState != 1) a11yShiftOn = painter.shiftState == 2
            }
        }
    }

    private fun finish(t: Tracker) {
        handler.removeCallbacks(t.longPress)
        handler.removeCallbacks(t.repeat)
        releaseKey(t)
        t.mode = Mode.DONE
    }

    fun cancelAllTrackers() = cancelTrackers(null)

    private fun cancelTrackers(except: Tracker?) {
        for (t in trackers) {
            if (t === except) continue
            if (t.mode == Mode.GESTURE) overlay.trailEnd()
            if (t.mode == Mode.DELETE_SWIPE) listener?.onDeleteSwipeEnd(false)
            finish(t)
        }
        trackers.removeAll { it !== except }
        pressed.clear()
        pressLayer?.hideAllCaps()
        overlay.hidePanel()
        overlay.clearPreviews()
        if (dimmed) onDimChanged?.invoke(false)
        dimmed = false
        invalidate()
    }

    private fun onLongPressTimeout(t: Tracker) {
        val key = t.key ?: return
        if (t.mode != Mode.TAP) return
        if (key.spec.popup.isEmpty()) {
            listener?.onLongPress(key)
            finish(t)
            return
        }
        val items = key.spec.popup.map {
            if (painter.shiftState != 0 && key.isLetter) it.uppercase() else it
        }
        val ox = toOverlayX(0f)
        val oy = toOverlayY(0f)
        val panel = PopupPanel.layout(
            items = items,
            keyCenterX = key.rect.centerX() + ox,
            keyTop = key.rect.top + oy,
            boundsLeft = ox,
            boundsRight = ox + width,
            colW = d.popupCell,
            rowH = d.popupCell,
            pad = d.popupPadding,
            bottomGap = d.popupGap,
        )
        t.panel = panel
        t.mode = Mode.PANEL
        overlay.dismissPreview(t.preview, 0)
        t.preview = null
        overlay.showPanel(panel, key)
        if (items.size > 1) setDimmed(true)
        if (accessibility.active) accessibility.announce("Alternatives are available")
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun onRepeat(t: Tracker) {
        val key = t.key ?: return
        if (t.mode != Mode.TAP || key.code != Codes.DELETE) return
        t.repeats++
        if (t.repeats == 1) listener?.onDeleteHoldStart()
        // Gboard 18.6 (emulator farm, exact timestamps over 1.2-3 s holds at two densities): character by character (no
        // word-wise acceleration), each repeat 51 ms after the previous one ran (mean interval 51.3 ms; 51 deletes in
        // a 3 s hold, 16 in 1.2 s). A stalled main thread delays the next repeat instead of firing a catch-up burst.
        val now = SystemClock.uptimeMillis()
        listener?.onDelete(word = false)
        handler.postAtTime(t.repeat, now + REPEAT_INTERVAL_MS)
    }

    companion object {
        const val DIM_ALPHA = 0.41f
        private val PAGE_KEYS = setOf(Codes.SYMBOLS, Codes.ALPHA, Codes.SYMBOLS_MORE, Codes.NUMPAD, Codes.PHONE_SYMBOLS, Codes.PHONE_BACK)
        const val LIFTED_LABEL_ALPHA = 38
        /** Gboard 18.6 (phone and the emulator farm, exact timestamps): the first repeat ~420 ms after touch-down, then every ~51 ms. */
        const val REPEAT_START_MS = ImeRules.DELETE_REPEAT_START_MS
        const val REPEAT_INTERVAL_MS = ImeRules.DELETE_REPEAT_INTERVAL_MS
        const val SCRUB_HOLD_MS = 200L
        const val SHIFT_LOCK_DELAY_MS = 1200L
        const val SCRUB_GUARD_MS = 150L
        const val PANEL_DESELECT_RATIO = 1.5f
        const val GESTURE_UPDATE_MS = 60L
    }
}
