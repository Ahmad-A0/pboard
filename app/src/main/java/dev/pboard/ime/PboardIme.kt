package dev.pboard.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import dev.pboard.engine.KeyboardEngine
import dev.pboard.engine.TouchPoint
import dev.pboard.ime.config.Config
import dev.pboard.ime.config.ToolbarItems
import dev.pboard.ime.engine.Engines
import dev.pboard.ime.layout.BuiltKeyboard
import dev.pboard.ime.layout.KeyboardLanguage
import dev.pboard.ime.layout.Page
import dev.pboard.ime.theme.Dimens
import dev.pboard.ime.theme.KeyFonts
import dev.pboard.ime.theme.KeyboardTheme
import dev.pboard.ime.view.ImeTrace
import dev.pboard.ime.view.KeyPainter
import dev.pboard.ime.view.KeyboardRoot
import dev.pboard.ime.view.KeyboardView
import dev.pboard.ime.view.StripItem
import dev.pboard.ime.view.TopBarView
import dev.pboard.ime.clipboard.ClipboardHistory
import dev.pboard.ime.clipboard.ClipboardPanelListener
import dev.pboard.ime.clipboard.ClipboardPanelView
import dev.pboard.ime.config.PrefsKeyboardConfig
import dev.pboard.ime.emoji.EmojiData
import dev.pboard.ime.emoji.EmojiPanelListener
import dev.pboard.ime.emoji.EmojiPanelView
import dev.pboard.ime.emoji.EmojiAnnotations
import dev.pboard.ime.emoji.EmojiSearchConnection
import dev.pboard.ime.emoji.EmojiSearchListener
import dev.pboard.ime.emoji.EmojiSearchView
import dev.pboard.ime.toolbar.ToolbarLayout
import dev.pboard.ime.translate.TranslateColors
import dev.pboard.ime.translate.TranslateLanguages
import dev.pboard.ime.translate.TranslateMode
import dev.pboard.ime.view.ToolbarPanelView
import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Prefs
import dev.pboard.prefs.KeyboardLanguages
import dev.pboard.prefs.LanguageCatalog
import dev.pboard.prefs.OneHandedMode
import dev.pboard.prefs.SplitMode
import dev.pboard.prefs.KeyboardMode
import dev.pboard.prefs.ScreenOrientation
import dev.pboard.ime.view.OneHandedListener
import dev.pboard.ime.view.OneHandedSideView
import dev.pboard.ime.view.PressLayer
import dev.pboard.ime.view.TextEditingListener
import dev.pboard.ime.view.TextEditingPanelView
import dev.pboard.ime.view.ResizeListener
import dev.pboard.ime.view.ResizeOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PboardIme : InputMethodService(), KeyboardHost, dev.pboard.ime.view.ToolbarDragHost {
    private lateinit var dims: Dimens
    private var root: KeyboardRoot? = null
    private var keyboardView: KeyboardView? = null
    private var topBar: TopBarView? = null
    private var painter: KeyPainter? = null
    private lateinit var controller: InputController
    /** Smart Compose and the text-intelligence models (dev.pboard.ime.textintel). */
    private lateinit var textIntel: dev.pboard.ime.textintel.TextIntelIme
    private lateinit var feedback: Feedback

    private var language: KeyboardLanguage? = null
    private var page = Page.LETTERS
    private var editor = EditorState(null)
    private val built = HashMap<Page, BuiltKeyboard>()

    private val main = Handler(Looper.getMainLooper())
    private val worker = SerialWorker("pboard-worker")

    private val configListener = { main.post { rebuildAll() }; Unit }

    /** "Suggest contact names" (opt-in): contact names for the engine, in memory only (dev.pboard.ime.contacts). */
    private val contacts by lazy {
        dev.pboard.ime.contacts.ContactSuggestions(applicationContext, worker, main) { names ->
            controller.runner.run("contacts") { it.setContactNames(names) }
            controller.prepareContext()
        }
    }

    /** Physical keyboards (CX-12): typing through the keyboard, the floating tools button instead of the keys. */
    private lateinit var hardware: dev.pboard.ime.hardware.HardwareKeyboard

    /** Recently-copied text and the items in it as chips in the strip (ED-06, ED-07). */
    private val clipChips by lazy {
        dev.pboard.ime.clipboard.ClipboardChips(this, object : dev.pboard.ime.clipboard.ClipboardChips.Host {
            override val dims get() = this@PboardIme.dims
            override val colors get() = KeyboardTheme.colors(this@PboardIme)
            override val inputConnection get() = currentInputConnection
            override fun showChips(views: List<View>) { inlineAutofill?.setLocalChips(views) }
            override fun paste(text: String) = controller.insertText(text)
        })
    }

    /** Inline autofill chips in the strip (Android 11+, CX-14). */
    private val inlineAutofill =
        if (android.os.Build.VERSION.SDK_INT >= 30) dev.pboard.ime.autofill.InlineAutofill(this) else null

    override fun onCreate(): Unit = ImeTrace.section("pboard:imeCreate") {
        super.onCreate()
        Config.install(PrefsKeyboardConfig(this).also { watchPrefs(it) })
        dims = Dimens(this, Config.current.heightScale, fontScale = Config.current.keyFontScale)
        clipboard = ClipboardHistory(this).apply {
            enabled = Config.current.clipboardHistory
            retentionMs = Config.current.clipboardRetentionHours * 3_600_000L
            onChanged = { clipboardView?.invalidate() }
            start()
        }
        feedback = Feedback(this)
        controller = InputController(this)
        textIntel = dev.pboard.ime.textintel.TextIntelIme(this, controller)
        hardware = dev.pboard.ime.hardware.HardwareKeyboard(this, hardwareHost)
        val glyphs = android.graphics.Paint()
        controller.emojiFor = { word ->
            dev.pboard.ime.emoji.EmojiAnnotations.peek(controller.locale)?.suggest(word).orEmpty().filter { glyphs.hasGlyph(it) }
        }
        Config.addListener(configListener)
        watchPersonalDictionary() // needs the controller (its engine runner)
        loadLanguage(currentLocaleTag())
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            registerReceiver(debugReceiver, IntentFilter(DEBUG_ACTION), RECEIVER_EXPORTED)
        }
    }

    private val themeCycleToken = Any()

    private var themeFade: android.animation.ValueAnimator? = null

    /**
     * Debug broadcast: draws the built views in theme [themeId] (null: the selected theme) without rebuilding them,
     * cross-fading from the colours shown over [fadeMs].
     */
    private fun recolor(themeId: String?, fadeMs: Long = 0) {
        themeFade?.cancel()
        val from = KeyboardTheme.colors(this)
        KeyboardTheme.debugOverride = null
        val to = if (themeId == null) KeyboardTheme.colors(this) else
            KeyboardTheme.resolve(themeId, KeyboardTheme.systemDark(this), { dark -> dev.pboard.ime.theme.Palette.resolve(this, dark) })
        fun show(c: dev.pboard.ime.theme.KeyboardColors) {
            KeyboardTheme.debugOverride = c
            painter?.colors = c
            root?.let { it.colors = c; it.overlay.colors = c; it.invalidate(); it.overlay.invalidate() }
            topBar?.let { it.colors = c; it.invalidate() }
            keyboardView?.invalidateKeys()
        }
        if (fadeMs <= 0) { show(to); if (themeId == null) KeyboardTheme.debugOverride = null; return }
        themeFade = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = fadeMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a -> show(if (a.animatedFraction >= 1f) to else blendColors(from, to, a.animatedFraction)) }
            start()
        }
    }

    /** Every colour of [a] moved the fraction [t] towards [b]'s (a missing gradient end counts as the background). */
    private fun blendColors(a: dev.pboard.ime.theme.KeyboardColors, b: dev.pboard.ime.theme.KeyboardColors, t: Float): dev.pboard.ime.theme.KeyboardColors {
        fun mix(x: Int, y: Int): Int {
            fun ch(s: Int) = (((x ushr s) and 0xff) * (1 - t) + ((y ushr s) and 0xff) * t + 0.5f).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        // A solid theme fading to or from a gradient: its gradient is its one colour.
        val from = if (a.backgroundEnd == null && b.backgroundEnd != null) a.copy(backgroundEnd = a.background) else a
        val to = if (b.backgroundEnd == null && a.backgroundEnd != null) b.copy(backgroundEnd = b.background) else b
        // The data class's componentN() are its constructor parameters, in order.
        val cls = dev.pboard.ime.theme.KeyboardColors::class.java
        val ctor = cls.declaredConstructors.first { c -> c.parameterTypes.size == cls.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) } }
        val args = (1..ctor.parameterTypes.size).map { i ->
            val component = cls.getMethod("component$i")
            val x = component.invoke(from); val y = component.invoke(to)
            if (x is Int && y is Int) mix(x, y) else y
        }
        return ctor.newInstance(*args.toTypedArray()) as dev.pboard.ime.theme.KeyboardColors
    }

    /**
     * Debug builds only: lets scripts tune rendering without rebuilding, e.g.
     * adb shell am broadcast -a dev.pboard.DEBUG --es font roboto-flex --es fvs "'opsz' 28"
     */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Voice typing from a 16 kHz mono WAV instead of the microphone: --es voiceWav /data/local/tmp/x.wav
            intent.getStringExtra("voiceWav")?.let { openVoice(java.io.File(it)); return }
            // Restyle the keyboard in place, on a timer, while it is being typed on (demo recordings; a theme chosen
            // in Settings rebuilds the views instead): --es themes color_blue,default_dark --ei themeEveryMs 1500
            // [--ei themeDelayMs 500] [--ei themeFadeMs 200: cross-fade]; --es themes , goes back to the selected theme.
            intent.getStringExtra("themes")?.let { list ->
                main.removeCallbacksAndMessages(themeCycleToken)
                val every = intent.getIntExtra("themeEveryMs", 1500).toLong()
                val delay = intent.getIntExtra("themeDelayMs", 0).toLong()
                val fade = intent.getIntExtra("themeFadeMs", 0).toLong()
                val ids = list.split(',').filter { it.isNotEmpty() }
                if (ids.isEmpty()) recolor(null)
                ids.forEachIndexed { i, id -> main.postDelayed({ recolor(id, fade) }, themeCycleToken, delay + i * every) }
                return
            }
            val p = painter ?: return
            intent.getStringExtra("fontFile")?.let { name ->
                runCatching { Typeface.createFromFile(java.io.File(filesDir, name)) }.getOrNull()
                    ?.let { p.setTypeface(it, intent.getStringExtra("fvs")) }
            }
            intent.getStringExtra("font")?.let { family ->
                p.setTypeface(Typeface.create(family, Typeface.NORMAL), intent.getStringExtra("fvs"))
            }
            // Live tuning of measured dimensions: --ef <name> <px at 420dpi>, --ei fnWeight <w>, --ez hintBold <b>
            val d = dims
            // Slowed-down recordings: --ei trailFadeMs 3000 keeps the glide trail three times as long.
            if (intent.hasExtra("trailFadeMs")) root?.overlay?.trailFadeMs = intent.getIntExtra("trailFadeMs", 1000).toLong()
            intent.extras?.let { x ->
                fun f(k: String, set: (Float) -> Unit) { if (x.containsKey(k)) set(d.m(x.getFloat(k))) }
                f("letterSize") { d.letterSize = it }
                f("letterDy") { d.letterDy = it }
                f("punctSize") { d.punctuationSize = it }
                f("punctDy") { d.punctuationDy = it }
                f("fnSize") { d.functionTextSize = it }
                f("fnDy") { d.functionTextDy = it }
                f("hintSize") { d.hintSize = it }
                f("hintInset") { d.hintRightInset = it }
                f("hintTop") { d.hintTopInset = it }
                f("radius") { d.keyRadius = it }
                if (x.containsKey("fnSpacing")) d.functionTextSpacing = x.getFloat("fnSpacing")
                if (x.containsKey("fnWeight")) d.functionTextWeight = x.getInt("fnWeight")
                if (x.containsKey("hintBold")) d.hintBold = x.getBoolean("hintBold")
                p.clearMetrics()
            }
            keyboardView?.invalidateKeys()
        }
    }

    override fun onDestroy() {
        voiceSession?.cancel()
        main.removeCallbacks(releaseVoiceModel)
        voiceEngine?.let { runCatching { it.release() } }
        hardware.destroy()
        Config.removeListener(configListener)
        prefsJob?.cancel()
        contacts.stop()
        dictionaryJob?.cancel()
        if (Engines.live === controller.runner) Engines.live = null
        clipboard.stop()
        runCatching { unregisterReceiver(debugReceiver) }
        main.removeCallbacksAndMessages(null)
        // InputMethodService.onDestroy finishes the input (onFinishInput saves learned data): it must run while the
        // engine thread still takes work. Queued work (the saves) completes after shutdown; anything submitted later
        // (callbacks arriving after destruction) is dropped instead of crashing the process.
        super.onDestroy()
        saveLearned()
        worker.shutdown()
        controller.runner.shutdown()
        textIntel.shutdown()
    }

    // ------------------------------------------------------------------ language / engine

    /**
     * The language in use comes from Settings › Languages (dev.pboard.prefs.KeyboardLanguages: the single source of
     * truth); the system's subtypes only mirror it ([syncSystemSubtypes]).
     */
    private fun currentLocaleTag(): String = LanguageCatalog.active(this)?.languageTag ?: "en-GB"

    /** The tag [loadLanguage] last loaded (the dialect the engine and layout use). */
    private var loadedTag: String? = null

    private fun loadLanguage(tag: String) {
        val lang = runCatching { KeyboardLanguage.load(assets, tag) }.getOrElse { KeyboardLanguage.load(assets, "en-US") }
        loadedTag = tag
        language = lang
        controller.locale = lang.locale
        textIntel.setLocale(lang.locale)
        built.clear()
        // One engine for the IME's lifetime; a subtype change swaps its active language (setLanguages).
        val ctx = applicationContext
        val existing = controller.runner.engine
        val locale = lang.locale
        worker.execute { dev.pboard.ime.emoji.EmojiAnnotations.get(ctx, locale) }
        worker.execute {
            if (existing == null) {
                val e = Engines.create(ctx, lang.locale)
                main.post {
                    if (controller.runner.engine == null) controller.runner.engine = e
                    pushGeometry()
                    contacts.sync(force = true)
                    pushPersonalDictionary()
                    // Build gesture templates and warm the decoders now, not on the first keystroke or swipe.
                    controller.runner.run("warm-up") { (it as? dev.pboard.engine.decode.PboardEngine)?.warmUp() }
                    controller.prepareContext()
                }
            } else {
                val spec = Engines.spec(ctx, lang.locale)
                controller.runner.run("set-languages") { it.setLanguages(listOf(spec)); (it as? dev.pboard.engine.decode.PboardEngine)?.warmUp() }
                main.post { pushPersonalDictionary(); controller.prepareContext() }
            }
        }
    }

    /** A subtype picked in the system's switcher becomes the active language if it is enabled in Settings › Languages. */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val tag = newSubtype?.languageTag.orEmpty()
        if (LanguageCatalog.enabled(this).any { it.languageTag == tag }) PboardPrefs.get(this)[Prefs.activeLanguage] = tag
        applyActiveLanguage()
    }

    /** Loads the active language if it changed (setting edited, language switch) and keeps the system in step. */
    private fun applyActiveLanguage() {
        val tag = currentLocaleTag()
        if (tag != loadedTag) {
            loadLanguage(tag)
            if (root != null) showPage(page)
            flashLanguageLabel()
        }
        syncSystemSubtypes()
    }

    /**
     * The system's view of pboard's languages follows the setting: its current subtype is the active language and, on
     * Android 14+, its enabled subtypes are the enabled languages (so the system switcher lists the same ones).
     * Languages without a subtype in res/xml/method.xml are left out of the system's view.
     */
    private fun syncSystemSubtypes() {
        if (currentInputConnection == null) return // only the current IME (with a live session) may switch its subtype
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val me = imm.inputMethodList.firstOrNull { it.packageName == packageName } ?: return
        val subtypes = (0 until me.subtypeCount).map { me.getSubtypeAt(it) }
        val enabled = LanguageCatalog.enabled(this)
        val active = LanguageCatalog.active(this) ?: return
        val target = subtypes.firstOrNull { it.languageTag == active.languageTag }
        if (target != null && imm.currentInputMethodSubtype?.languageTag != active.languageTag) {
            runCatching { switchInputMethod(me.id, target) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val want = enabled.mapNotNull { e -> subtypes.firstOrNull { it.languageTag == e.languageTag }?.hashCode() }.toSet()
            val have = imm.getEnabledInputMethodSubtypeList(me, false).map { it.hashCode() }.toSet()
            if (want.isNotEmpty() && want != have) {
                runCatching { imm.setExplicitlyEnabledInputMethodSubtypes(me.id, want.toIntArray()) }
            }
        }
    }

    /** Several keyboards enabled in Settings › Languages: the language key and switch are offered. */
    private fun multiLanguage(): Boolean = LanguageCatalog.enabled(this).size > 1

    // ------------------------------------------------------------------ views

    override fun onCreateInputView(): View = ImeTrace.section("pboard:createInputView") {
        ImeTrace.openStarted()
        hardware.inputView(createInputView())
    }

    private val hardwareHost = object : dev.pboard.ime.hardware.HardwareKeyboard.Host {
        override val controller get() = this@PboardIme.controller
        override val colors get() = KeyboardTheme.colors(this@PboardIme)
        override fun keyboardView(): View? = root
        override fun setInputView(view: View) = this@PboardIme.setInputView(view)
        override fun openSettings() = this@PboardIme.openSettings(null)
        override fun openEmoji() = this@PboardIme.openEmoji()
        override fun openClipboard() = this@PboardIme.openClipboard()
        override fun startVoiceInput() = this@PboardIme.startVoiceInput()
        override fun switchLanguage() = this@PboardIme.switchLanguage()
        override fun hasSeveralLanguages() = multiLanguage()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        hardware.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        hardware.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: android.view.inputmethod.CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        hardware.onCursorAnchorInfo(cursorAnchorInfo)
        if (searchConnection == null && translate == null) textIntel.onCursorAnchor(cursorAnchorInfo)
    }

    private fun createInputView(): View {
        dropTranslate() // rotation / theme change: Gboard closes Translate, keeping the translation
        // One-handed (Gboard): the whole keyboard is drawn at the width ratio (keys, labels, bar), top-aligned in the
        // normal keyboard's height.
        val oneHandedScale = PboardPrefs.get(this).let { prefs ->
            when {
                prefs[Prefs.floatingKeyboard] -> prefs[Prefs.geometry(KeyboardMode.FLOATING, orientationKey())].widthRatio
                prefs[Prefs.oneHandedMode] == OneHandedMode.OFF -> 1f
                else -> prefs[Prefs.geometry(KeyboardMode.ONE_HANDED, orientationKey())].widthRatio
            }
        }
        dims = Dimens(this, Config.current.heightScale, oneHandedScale, Config.current.keyFontScale)
        // Gboard 18.6 key shape "Rounded": fully rounded caps (drawRoundRect clamps the radius to a pill).
        if (Config.current.roundedKeys) dims.keyRadius = dims.keyHeight
        val r = KeyboardRoot(this, dims)
        textIntel.attach(r)
        val colors = KeyboardTheme.colors(this)
        r.colors = colors
        // grammar fix: the reopened word's tint is the functional-key colour at 60 % (Gboard, measured)
        controller.grammar.tintColor = (colors.functionalKey and 0x00FFFFFF) or 0x99000000.toInt()
        controller.grammar.context = this
        val font = KeyFonts.regular(this)
        val p = KeyPainter(colors, dims)
        p.setTypeface(font, null)
        painter = p
        r.overlay.painter = p
        r.overlay.colors = colors
        val bar = TopBarView(this, dims).apply {
            dragHost = this@PboardIme
            this.colors = colors
            listener = controller
            setTypeface(font)
        }
        val kv = KeyboardView(this, dims, r.overlay).apply {
            painter = p
            listener = controller
        }
        r.setPanelContent(bar, kv)
        // A touch in the band between the bar's buttons and the top key row types on the keys (Gboard 18.6).
        bar.keysHandoff = { e ->
            if (kv.parent == null || kv.parent !== bar.parent || kv.visibility != View.VISIBLE) false else {
                val ev = android.view.MotionEvent.obtain(e)
                ev.offsetLocation((bar.left - kv.left).toFloat(), (bar.top - kv.top).toFloat())
                kv.handOffTouch(ev)
                ev.recycle()
                true
            }
        }
        r.setOnApplyWindowInsetsListener { v, insets ->
            // Gboard keeps the IME navigation bar area (back / switcher buttons) at least 48dp tall.
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            (v as KeyboardRoot).bottomInset = if (nav > 0) maxOf(nav, (48 * resources.displayMetrics.density).toInt()) else 0
            insets
        }
        // A rebuilt view (theme change) keeps the last known inset until the window re-dispatches insets.
        r.bottomInset = root?.bottomInset ?: 0
        r.requestApplyInsets()
        // Ends the pboard:keyboardOpen trace section at the window's first draw after a show request.
        r.viewTreeObserver.addOnDrawListener { ImeTrace.keyboardDrawn() }
        root = r
        topBar = bar
        keyboardView = kv
        inlineAutofill?.attach(r, bar, dims, colors)
        kv.onDimChanged = { on -> bar.alpha = if (on) KeyboardView.DIM_ALPHA else 1f }
        // The first build may guess the width (window insets, e.g. a landscape cutout): rebuild once it is known.
        r.onWidthChanged = { w ->
            val built = keyboardView?.keyboard?.width ?: 0
            if (root === r && built != w - r.panel.paddingLeft - r.panel.paddingRight) { this.built.clear(); showPage(page) }
        }
        // Key presses and previews repaint only their own small views (latency on software rendering, power).
        PressLayer(r, dims).also { layer ->
            layer.painter = p
            kv.pressLayer = layer
            r.overlay.pressLayer = layer
        }
        built.clear()
        applyOneHanded(r, colors)
        // A rebuilt view (mode, theme or settings change) starts with the controller's current shift state.
        onShiftChanged(controller.shift)
        return r
    }

    // ------------------------------------------------------------------ one-handed mode

    private fun orientationKey() =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ScreenOrientation.LANDSCAPE
        else ScreenOrientation.PORTRAIT

    /** One-handed: the keyboard keeps [KeyboardGeometry.widthRatio] of the width on one edge, buttons in the rest. */
    private fun applyOneHanded(r: KeyboardRoot, colors: dev.pboard.ime.theme.KeyboardColors) {
        val prefs = PboardPrefs.get(this)
        if (prefs[Prefs.floatingKeyboard]) { applyFloating(r, prefs); return }
        r.floating = null
        val mode = prefs[Prefs.oneHandedMode]
        if (mode == OneHandedMode.OFF) {
            r.setSideMargins(0, 0); r.sideView = null
            // Resize overlay's bottom handle: the keyboard lifted above the navigation bar.
            r.extraBottom = (prefs[Prefs.geometry(KeyboardMode.NORMAL, orientationKey())].bottomOffsetDp * resources.displayMetrics.density).toInt()
            return
        }
        val g = prefs[Prefs.geometry(KeyboardMode.ONE_HANDED, orientationKey())]
        val w = resources.displayMetrics.widthPixels
        // Measured (Gboard 15.1, 420 dpi): the keyboard keeps 0.8 of the width, 4 dp from its edge; the rest is the
        // button column. Rows shrink by the same ratio and the space left under them stays empty.
        val edge = (4 * resources.displayMetrics.density).toInt()
        val kbW = (w * g.widthRatio).toInt()
        val free = (w - kbW - edge).coerceAtLeast(0)
        val right = mode == OneHandedMode.RIGHT
        r.setSideMargins(if (right) free else edge, if (right) edge else free)
        val full = Dimens(this, Config.current.heightScale)
        r.extraBottom = ((full.barHeight + full.lettersKeysHeight) - (dims.barHeight + dims.lettersKeysHeight)).toInt().coerceAtLeast(0)
        r.sideView = OneHandedSideView(this, dims, colors).apply {
            keyboardOnRight = right
            listener = object : OneHandedListener {
                override fun onOneHandedExit() { prefs[Prefs.oneHandedMode] = OneHandedMode.OFF }
                override fun onOneHandedSwap() {
                    prefs[Prefs.oneHandedMode] = if (right) OneHandedMode.LEFT else OneHandedMode.RIGHT
                }
                override fun onOneHandedResize() = openResize()
            }
        }
    }

    // ------------------------------------------------------------------ floating keyboard

    private var floatGeometry: dev.pboard.prefs.KeyboardGeometry? = null

    /**
     * Floating (Gboard 15.1, 420 dpi): a card at the width ratio (0.83) with the keyboard scaled to it, 21 px above the
     * bar, a 112 px strip under the keys holding the drag handle and the hide chevron, an elevation shadow; the app is
     * not resized and only the card takes touches.
     */
    private fun applyFloating(r: KeyboardRoot, prefs: PboardPrefs) {
        r.setSideMargins(0, 0); r.extraBottom = 0; r.sideView = null
        val g = floatGeometry ?: prefs[Prefs.geometry(KeyboardMode.FLOATING, orientationKey())].also { floatGeometry = it }
        val w = resources.displayMetrics.widthPixels
        val px = resources.displayMetrics.density / Dimens.REF_DENSITY
        val cardW = (w * g.widthRatio).toInt()
        val left = ((w - cardW) * g.horizontalBias).toInt()
        val bottomGap = (g.bottomOffsetDp * resources.displayMetrics.density).toInt()
        r.floating = KeyboardRoot.Floating(left, w - left - cardW, bottomGap, (112 * px).toInt(), (21 * px).toInt())
        r.floatingListener = object : KeyboardRoot.FloatingListener {
            override fun onFloatDrag(dx: Float, dy: Float) {
                val f = r.floating ?: return
                val cw = w - f.left - f.rightMargin
                val nl = (f.left + dx).toInt().coerceIn(0, w - cw)
                val maxGap = r.height - (r.panel.height) + f.bottomGap
                val ng = (f.bottomGap - dy).toInt().coerceIn(0, maxOf(0, maxGap))
                r.floating = KeyboardRoot.Floating(nl, w - nl - cw, ng, f.handleHeight, f.topPad)
            }
            override fun onFloatDragEnd() {
                val f = r.floating ?: return
                val cw = w - f.left - f.rightMargin
                val bias = if (w - cw > 0) f.left.toFloat() / (w - cw) else 0.5f
                val ng = g.copy(horizontalBias = bias, bottomOffsetDp = f.bottomGap / resources.displayMetrics.density)
                floatGeometry = ng
                prefs[Prefs.geometry(KeyboardMode.FLOATING, orientationKey())] = ng
            }
            override fun onFloatHide() = requestHideSelf(0)
        }
    }

    // ------------------------------------------------------------------ resize overlay

    private fun currentMode(prefs: PboardPrefs) = when {
        prefs[Prefs.floatingKeyboard] -> KeyboardMode.FLOATING
        prefs[Prefs.oneHandedMode] != OneHandedMode.OFF -> KeyboardMode.ONE_HANDED
        else -> KeyboardMode.NORMAL
    }

    /** Toolbar "Resize" (and the one-handed resize button): Gboard's overlay with grab bars, Reset and Done. */
    override fun openResize() {
        val r = root ?: return
        restoreKeyboard()
        keyboardView?.cancelAllTrackers()
        val prefs = PboardPrefs.get(this)
        r.resizeView = ResizeOverlayView(this, KeyboardTheme.colors(this)).apply {
            listener = object : ResizeListener {
                private val pref get() = Prefs.geometry(currentMode(prefs), orientationKey())
                override fun onResizeHeight(dy: Float) {
                    val g = prefs[pref]
                    val keys = dims.lettersKeysHeight / g.heightRatio.coerceAtLeast(0.1f)
                    val ratio = (g.heightRatio * (dims.lettersKeysHeight - dy) / dims.lettersKeysHeight)
                    if (keys > 0) prefs[pref] = g.copy(heightRatio = ratio).sanitized()
                }
                override fun onResizeBottom(dy: Float) {
                    val g = prefs[pref]
                    prefs[pref] = g.copy(bottomOffsetDp = (g.bottomOffsetDp - dy / resources.displayMetrics.density).coerceAtLeast(0f))
                }
                override fun onResizeReset() { prefs[pref] = dev.pboard.prefs.KeyboardGeometry.defaultFor(currentMode(prefs)) }
                override fun onResizeDone() { r.resizeView = null }
            }
        }
    }

    /** Toolbar "Floating": undock / dock the keyboard. */
    override fun toggleFloating() {
        val prefs = PboardPrefs.get(this)
        floatGeometry = null
        prefs[Prefs.floatingKeyboard] = !prefs[Prefs.floatingKeyboard]
    }

    /** The user's split choice: null = Gboard's default for this screen ([BuiltKeyboard.Split.wanted]). */
    private fun splitChoice(): Boolean? = when (PboardPrefs.get(this)[Prefs.splitMode]) {
        SplitMode.AUTO -> null
        SplitMode.ON -> true
        SplitMode.OFF -> false
    }

    /** Toolbar "Split": the opposite of what this screen shows now, stored as the user's choice. */
    override fun toggleSplit() {
        val split = BuiltKeyboard.Split.wanted(splitChoice(), resources.configuration.smallestScreenWidthDp)
        PboardPrefs.get(this)[Prefs.splitMode] = if (split) SplitMode.OFF else SplitMode.ON
    }

    /**
     * The split layout's halves (CX-11), or null for a full-width keyboard: by Gboard's default for the screen or the
     * user's choice ([BuiltKeyboard.Split.wanted]), never while the keyboard floats or is one-handed.
     */
    private fun splitHalves(width: Int): BuiltKeyboard.Split? {
        if (!BuiltKeyboard.Split.wanted(splitChoice(), resources.configuration.smallestScreenWidthDp)) return null
        val prefs = PboardPrefs.get(this)
        if (prefs[Prefs.floatingKeyboard] || prefs[Prefs.oneHandedMode] != OneHandedMode.OFF) return null
        return BuiltKeyboard.Split.gboard(width, resources.displayMetrics.density)
    }

    /** Toolbar item / comma long-press: one-handed on (right edge, Gboard's default) or back off. */
    override fun toggleOneHanded() {
        val prefs = PboardPrefs.get(this)
        prefs[Prefs.oneHandedMode] = if (prefs[Prefs.oneHandedMode] == OneHandedMode.OFF) OneHandedMode.RIGHT else OneHandedMode.OFF
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        controller.noteRebuild()
        super.onConfigurationChanged(newConfig)
        // Theme (dark mode) or size changes: rebuild the views with fresh colours.
        main.post { rebuildAll() }
    }

    private var prefsJob: Job? = null
    private var dictionaryJob: Job? = null

    /**
     * The personal dictionary (Settings > Dictionary) goes to the engine when it is created, when the language changes
     * and whenever the dictionary is edited or imported into: its words are valid words at once.
     */
    private fun pushPersonalDictionary() {
        val words = dev.pboard.prefs.PersonalDictionary.forTyping(dev.pboard.prefs.PersonalDictionary.get(this).entries.value, loadedTag ?: currentLocaleTag())
            .map { (word, shortcut) -> dev.pboard.engine.PersonalWord(word, shortcut) }
        controller.runner.run("personal-dictionary") { it.setPersonalDictionary(words) }
    }

    private fun watchPersonalDictionary() {
        Engines.live = controller.runner
        val entries = dev.pboard.prefs.PersonalDictionary.get(this).entries
        dictionaryJob = CoroutineScope(Dispatchers.Main.immediate).launch { entries.collect { pushPersonalDictionary() } }
    }
    private var lastClearRequest = 0L

    /** Settings changes (dev.pboard.prefs) rebuild the keyboard; a newer clear request wipes learned data. */
    private fun watchPrefs(cfg: PrefsKeyboardConfig) {
        lastClearRequest = cfg.learnedDataClearRequest
        prefsJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            cfg.prefs.changes.collect { key ->
                if (key == null || key == Prefs.suggestContacts.key) contacts.sync()
                // The language switch only changes which language is active: load it, no view rebuild.
                applyActiveLanguage()
                if (key == Prefs.activeLanguage.key) return@collect
                clipboard.enabled = cfg.clipboardHistory
                clipboard.retentionMs = cfg.clipboardRetentionHours * 3_600_000L
                val clear = cfg.learnedDataClearRequest
                if (clear > lastClearRequest) {
                    lastClearRequest = clear
                    clearLearnedData()
                }
                main.removeCallbacks(rebuildRunnable)
                main.postDelayed(rebuildRunnable, 50)
            }
        }
    }

    private val rebuildRunnable = Runnable { Config.notifyChanged() }

    private fun clearLearnedData() {
        val ctx = applicationContext
        controller.runner.run { Engines.clearUserData(ctx, it) }
        EmojiData.clearRecents(this)
        clipboard.clear()
    }

    private fun rebuildAll() {
        if (root == null) return
        // A toolbar rearranged in the "more" panel saves to prefs, which rebuilds the views: keep the panel open.
        val reopenToolbar = toolbarPanel != null
        val reopenResize = root?.resizeView != null
        toolbarPanel = null
        setInputView(onCreateInputView())
        themeExtractView()
        controller.noteRebuild()
        if (currentInputEditorInfo != null && isInputViewShown) startView(currentInputEditorInfo, true)
        if (reopenToolbar) toggleToolbarPanel()
        if (reopenResize) openResize()
    }

    /**
     * Like Gboard on phones: landscape uses the fullscreen extract editor above the keyboard, unless the app opts out
     * (IME_FLAG_NO_FULLSCREEN / NO_EXTRACT_UI) or the keyboard is floating.
     */
    override fun onEvaluateFullscreenMode(): Boolean {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        if (opts and (EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0) return false
        return !PboardPrefs.get(this)[Prefs.floatingKeyboard]
    }

    /** The extract editor's background (dev.pboard.ime.theme.ExtractBackground); null until the view exists. */
    private var extractBackground: dev.pboard.ime.theme.ExtractBackground? = null

    /** Gboard's extract editor sits on the light surface colour (surfaceBright), not the platform's grey gradient. */
    override fun onCreateExtractTextView(): View {
        val v = super.onCreateExtractTextView()
        val platform = v.background
        extractBackground = dev.pboard.ime.theme.ExtractBackground({ v.setBackgroundColor(it) }, { v.background = platform })
        themeExtractView()
        // Gboard's action button reads "DONE" (all caps), as on the platform's Material buttons.
        (v.findViewById<View>(android.R.id.inputExtractAction) as? android.widget.TextView)?.isAllCaps = true
        return v
    }

    /** The framework creates the extract view only with the window (start, rotation): rebuildAll re-themes it too. */
    private fun themeExtractView() {
        extractBackground?.apply(KeyboardTheme.colors(this))
    }

    /** The framework switches fullscreen (extract) mode here: re-apply our window layout for the new mode. */
    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        updateWindowLayout(isFullscreen)
    }

    /**
     * Like Gboard, keep showing the soft keyboard when a hardware keyboard is attached (the platform default hides
     * it unless the user enabled "show on-screen keyboard"), e.g. on emulators and with keyboard covers.
     */
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Translate open and the app merely restarted the same field: the keyboard keeps typing into the box.
        if (keepTranslate(attribute, restarting)) { appEditor = EditorState(attribute); return }
        editor = EditorState(attribute)
    }

    /** Whether the open Translate session survives this (re)start of input (see TranslateLanguages.keepOnRestart). */
    private fun keepTranslate(info: EditorInfo?, restarting: Boolean): Boolean {
        if (translate == null || info == null) return false
        val app = appEditor?.info ?: return false
        return TranslateLanguages.keepOnRestart(
            restarting, info.packageName == app.packageName, info.fieldId == app.fieldId, info.fieldName == app.fieldName,
            EditorState(info).isPassword,
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean): Unit = ImeTrace.section("pboard:startInputView") {
        if (!inputViewShown) ImeTrace.openStarted()
        inputViewShown = true
        super.onStartInputView(info, restarting)
        startView(info, restarting)
        syncSystemSubtypes()
    }

    /** Whether the keyboard is currently shown (between onStartInputView and onFinishInputView). */
    private var inputViewShown = false

    private fun startView(info: EditorInfo?, restarting: Boolean) {
        dropEmojiSearch()
        if (keepTranslate(info, restarting)) {
            // the app restarted its field while translating: Gboard stays in Translate with an empty box
            appEditor = EditorState(info)
            appSelStart = info?.initialSelStart ?: -1
            appSelEnd = info?.initialSelEnd ?: -1
            translate?.onAppRestarted()
            return
        }
        dropTranslate()
        appSelStart = info?.initialSelStart ?: -1
        appSelEnd = info?.initialSelEnd ?: -1
        editor = EditorState(info)
        clipboard.paused = editor.incognito || editor.isPassword
        // Like Gboard, the keyboard always (re)appears on its keys: a panel left open when it was hidden (the "more"
        // grid, emoji, clipboard, text editing, resize) must not come back with the keys view detached (blank keyboard).
        if (anyPanelOpen() || root?.panel?.getChildAt(0) !== topBar) restoreKeyboard()
        updateWindowLayout()
        updateNavigationBarAppearance()
        page = editor.initialPage
        built.clear()
        controller.startInput(editor, info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1, restarting)
        textIntel.startInput(editor)
        // Contacts permission granted or revoked since (the setting itself is watched): cheap when unchanged.
        contacts.sync()
        showPage(page)
        controller.updateShift(force = true)
        flashLanguageLabelIfNeeded()
        hardware.onStartInput()
        // Back from the microphone-permission screen the mic key opened: start listening now.
        if (voicePending) {
            voicePending = false
            if (dev.pboard.ime.voice.VoicePermissionActivity.granted(this) && editor.voiceAllowed) main.post { openVoice() }
        }
        // Text from "Scan text" started in this field.
        dev.pboard.ime.scan.ScanResult.take(info?.packageName, info?.fieldId ?: 0)?.let { t ->
            controller.insertText(if (editor.isMultiLine) t else t.replace(Regex("\\s*\n\\s*"), " "))
        }
        // Recently-copied chips (dev.pboard.ime.clipboard) live in the chip row, which needs Android 11's chip host.
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        if (inlineAutofill != null) clipChips.offer(cm, editor, info?.initialSelStart ?: -1) else controller.offerClipboardChip(cm)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputViewShown = false
        keyboardView?.cancelAllTrackers()
        // Hidden (e.g. back) with a panel open: reset to the keys now, so the next show never starts on a stale panel.
        if (anyPanelOpen()) restoreKeyboard()
        controller.finishInput()
        textIntel.finishInput()
        dropEmojiSearch()
        dropTranslate()
        super.onFinishInputView(finishingInput)
    }

    private fun saveLearned() {
        main.removeCallbacks(saveRunnable)
        val ctx = applicationContext
        controller.runner.run { Engines.saveUserData(ctx, it) }
    }

    private val saveRunnable = Runnable { saveLearned() }

    /** Learned data is written on a debounce after commits (and when input finishes / the IME is destroyed). */
    override fun learnedDataChanged() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
    }

    override fun onFinishInput() {
        controller.finishInput()
        saveLearned()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        appSelStart = newSelStart
        appSelEnd = newSelEnd
        textEditingPanel?.let { it.hasSelection = newSelStart != newSelEnd; refreshEditingState(it) }
        if (searchConnection == null && translate == null && voicePanel == null) {
            controller.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
            textIntel.onSelectionChanged(newSelStart, newSelEnd)
        }
        clipChips.onSelectionChanged(newSelStart, newSelEnd)
    }

    /** Full-height IME window so popups can draw above the keyboard (approach from AOSP LatinIME, Apache-2.0). */
    private fun updateWindowLayout(fullscreen: Boolean = isFullscreenMode) {
        val window = window?.window ?: return
        val lp = window.attributes
        if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            window.attributes = lp
        }
        // Fullscreen (landscape): the extract editor takes the space above, so the keyboard wraps its content and
        // previews may draw over the extract area instead.
        val h = if (fullscreen) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        val inputArea = window.findViewById<View>(android.R.id.inputArea)
        when (val ilp = inputArea?.layoutParams) {
            is FrameLayout.LayoutParams -> if (ilp.height != h || ilp.gravity != Gravity.BOTTOM) {
                ilp.height = h
                ilp.gravity = Gravity.BOTTOM
                inputArea.layoutParams = ilp
            }
            // The platform layout puts the input area in a vertical LinearLayout under the extract area.
            // It wraps its content (our root): full height normally, the keyboard's height in fullscreen.
            is android.widget.LinearLayout.LayoutParams -> if (ilp.height != ViewGroup.LayoutParams.WRAP_CONTENT || ilp.weight != 0f) {
                ilp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                ilp.weight = 0f
                inputArea.layoutParams = ilp
            }
        }
        root?.let { r ->
            r.wrapKeyboard = fullscreen
            val lpr = r.layoutParams
            if (lpr != null && lpr.height != h) {
                lpr.height = h
                r.layoutParams = lpr
            }
            if (fullscreen) {
                var parent = r.parent
                while (parent is ViewGroup) { parent.clipChildren = false; parent = parent.parent }
            }
        }
    }

    override fun onUpdateExtractingVisibility(ei: EditorInfo?) {
        super.onUpdateExtractingVisibility(ei)
        updateWindowLayout()
    }

    /** Dark navigation-bar buttons (back, IME switcher) on the light keyboard, light ones on the dark keyboard. */
    private fun updateNavigationBarAppearance() {
        val w = window?.window ?: return
        val light = !KeyboardTheme.isDark(this)
        w.insetsController?.setSystemBarsAppearance(
            if (light) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (isFullscreenMode) return
        if (hardware.computeInsets(outInsets)) return
        val r = root ?: return
        if (!r.isLaidOut) return
        val top = r.keyboardTop
        if (translateOverlay != null) {
            // language picker: a dialog over the app, so the whole window takes touches (the app keeps its size)
            outInsets.contentTopInsets = top
            outInsets.visibleTopInsets = top
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(0, 0, r.width, r.height)
            return
        }
        val card = r.floatingCard(android.graphics.Rect())
        if (card != null) {
            // Floating: the app keeps the whole screen; only the card is touchable.
            outInsets.contentTopInsets = r.height
            outInsets.visibleTopInsets = r.height
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(card)
            return
        }
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, r.width, r.height)
        textIntel.addTouchable(outInsets.touchableRegion)
        inlineAutofill?.addTouchable(outInsets.touchableRegion)
    }

    // ------------------------------------------------------------------ pages

    private fun buildPage(p: Page): BuiltKeyboard {
        built[p]?.let { return it }
        val lang = language ?: KeyboardLanguage.load(assets, "en-US").also { language = it }
        val spec = lang.build(p, editor.variant(multiLanguage() && Config.current.languageSwitchKey)
            .copy(symbolHints = Config.current.longPressSymbols))
        val full = root?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val width = full - (root?.panel?.paddingLeft ?: 0) - (root?.panel?.paddingRight ?: 0)
        return BuiltKeyboard.build(spec, width, dims, splitHalves(width)).also { built[p] = it }
    }

    private fun showPage(p: Page) {
        page = p
        val kv = keyboardView ?: return
        val pnt = painter ?: return
        val kb = buildPage(p)
        pnt.enterAction = editor.enterAction
        pnt.keyBorders = Config.current.keyBorders
        kv.keyboard = kb
        val bar = topBar
        // Gboard 18.6 shows the toolbar over number / phone / date pads and password keyboards too; its "Suggestion
        // strip" off hides the whole top row (suggestions and toolbar).
        bar?.visibility = if (!Config.current.suggestionStripVisible ||
            !Config.current.showToolbar && !editor.suggestions) View.GONE else View.VISIBLE
        bar?.toolbarItems = Config.current.toolbarItems.let { pinned ->
            // Landscape (Gboard): the wider bar shows one more item, the first one from the "more" panel.
            if (dims.landscape && pinned.size >= dev.pboard.ime.toolbar.ToolbarItem.BAR_CAPACITY) {
                val extra = ToolbarLayout.load(PboardPrefs.get(this)).panel.firstOrNull {
                    it.availability == dev.pboard.ime.toolbar.ToolbarItem.Availability.ALWAYS &&
                        it.isOffered(largeScreen = false, Config.current.voiceTyping)
                }
                if (extra != null) pinned + extra.id else pinned
            } else pinned
        }
        bar?.powerKey = Config.current.toolbarPowerKey
        bar?.disabledItems = buildSet {
            if (!editor.acceptsImages) { add(ToolbarItems.STICKER); add(ToolbarItems.GIF) }
            if (!editor.voiceAllowed) add(ToolbarItems.VOICE)
            if (editor.isPassword) add(ToolbarItems.TRANSLATE) // never translate (or show) password text
        }
        bar?.rtl = language?.rtl == true
        pushGeometry()
        controller.onPageChanged()
        root?.requestLayout()
    }

    private fun pushGeometry() {
        val kb = keyboardView?.keyboard ?: return
        if (kb.page == Page.LETTERS) {
            val g = kb.engineGeometry()
            controller.runner.run("geometry") { it.setGeometry(g); (it as? dev.pboard.engine.decode.PboardEngine)?.warmUp() }
        }
    }

    private var labelRunnable: Runnable? = null

    private fun flashLanguageLabelIfNeeded() {
        if (multiLanguage()) flashLanguageLabel()
    }

    /** Gboard shows the language name on the space bar briefly when the keyboard appears or the language changes. */
    private fun flashLanguageLabel() {
        val pnt = painter ?: return
        val lang = language ?: return
        if (!multiLanguage()) return
        pnt.spaceLabel = lang.name
        pnt.spaceLabelAlpha = 1f
        keyboardView?.invalidateKeys()
        labelRunnable?.let { main.removeCallbacks(it) }
        labelRunnable = Runnable {
            pnt.spaceLabelAlpha = 0f
            keyboardView?.invalidateKeys()
        }.also { main.postDelayed(it, 1500) }
    }

    // ------------------------------------------------------------------ KeyboardHost

    /** While emoji search or Translate is open the keyboard types into its box, not the app. */
    override val inputConnection: InputConnection?
        get() = searchConnection ?: translate?.connection ?: currentInputConnection

    override fun setPage(page: Page) = showPage(page)

    override fun currentPage(): Page = page

    override fun onShiftChanged(state: ShiftState) {
        val p = painter ?: return
        p.shiftState = when (state) {
            ShiftState.OFF -> 0
            ShiftState.ON, ShiftState.AUTO -> 1
            ShiftState.LOCKED -> 2
        }
        keyboardView?.invalidateKeys()
        keyboardView?.accessibility?.invalidateRoot(keepFocus = true)
    }

    override fun showStrip(items: List<StripItem?>) {
        hardware.onStrip(items)
        val bar = topBar ?: return
        if (!editor.suggestions) { inlineAutofill?.onStripChanged(null); bar.showToolbar(); return }
        inlineAutofill?.onStripChanged(items)
        bar.setSuggestions(items)
    }

    override fun showToolbar() { inlineAutofill?.onStripChanged(null); topBar?.showToolbar() }

    // ------------------------------------------------------------------ inline autofill (dev.pboard.ime.autofill)

    @androidx.annotation.RequiresApi(30)
    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): android.view.inputmethod.InlineSuggestionsRequest? {
        val inline = inlineAutofill ?: return null
        inline.clear()
        return inline.createRequest(dims, KeyboardTheme.colors(this), language?.rtl == true, language?.locale)
    }

    @androidx.annotation.RequiresApi(30)
    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean =
        inlineAutofill?.onResponse(response) ?: false

    override fun keyCenter(c: Char): TouchPoint? {
        val kb = built[Page.LETTERS] ?: return null
        val k = kb.engineKeys.firstOrNull { it.spec.label.equals(c.toString(), ignoreCase = true) } ?: return null
        return TouchPoint(k.hit.centerX(), k.hit.centerY())
    }

    private var emojiView: EmojiPanelView? = null
    private var clipboardView: ClipboardPanelView? = null
    private lateinit var clipboard: ClipboardHistory

    override fun openEmoji() {
        if (translate != null) closeTranslate()
        val r = root ?: return
        val colors = KeyboardTheme.colors(this)
        val v = EmojiPanelView(this, dims, colors).apply {
            recordRecents = !editor.incognito
            listener = object : EmojiPanelListener {
                override fun onEmoji(emoji: String) = controller.insertText(emoji)
                override fun onEmojiDelete() = controller.onDelete(false)
                override fun onEmojiClose() = restoreKeyboard()
                override fun onEmojiSearch() = openEmojiSearch()
                override fun onEmojiKeyFeedback() { emojiView?.let { feedback.keyDown(it, 0) } }
            }
        }
        emojiView = v
        val ctx = applicationContext
        worker.execute {
            val cats = EmojiData.categories(ctx)
            val recents = EmojiData.recents(ctx)
            main.post { if (emojiView === v) v.setData(cats, recents) }
        }
        keyboardView?.cancelAllTrackers()
        r.setPanelContent(v)
        r.bandColor = colors.emojiBackground
    }

    // ------------------------------------------------------------------ emoji search

    private var searchConnection: EmojiSearchConnection? = null
    private var emojiSearchView: EmojiSearchView? = null
    private var appEditor: EditorState? = null
    private var appSelStart = -1
    private var appSelEnd = -1
    @Volatile private var renderableEmoji: Set<String>? = null

    /**
     * Gboard's emoji search: the search card above the toolbar and the letters page (no emoji key, Search action);
     * typing goes into the search box, results fill the card, a tapped result goes into the app.
     */
    private fun openEmojiSearch() {
        val r = root ?: return
        val bar = topBar ?: return
        val kv = keyboardView ?: return
        controller.finishInput()
        val view = EmojiSearchView(this, dims, KeyboardTheme.colors(this))
        val conn = EmojiSearchConnection(kv) { q -> updateEmojiSearch(q) }
        view.listener = object : EmojiSearchListener {
            override fun onSearchEmoji(emoji: String) {
                currentInputConnection?.commitText(emoji, 1)
                if (appEditor?.incognito != true) EmojiData.addRecent(this@PboardIme, emoji)
            }
            override fun onSearchClose() { closeEmojiSearch(); openEmoji() }
            override fun onSearchClear() {
                controller.finishInput()
                conn.clear()
                controller.startInput(editor, 0, 0, true, imeInternal = true)
            }
        }
        appEditor = editor
        searchConnection = conn
        emojiSearchView = view
        emojiView = null
        val info = EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            packageName = this@PboardIme.packageName
        }
        editor = EditorState(info, internal = true)
        built.clear()
        kv.cancelAllTrackers()
        r.setPanelContent(view, bar, kv)
        controller.startInput(editor, 0, 0, false)
        showPage(Page.LETTERS)
        controller.updateShift(force = true)
        updateEmojiSearch("")
    }

    private fun updateEmojiSearch(q: String) {
        val v = emojiSearchView ?: return
        val ctx = applicationContext
        val locale = controller.locale
        worker.execute {
            val renderable = renderableEmoji
                ?: EmojiData.searchable(EmojiData.categories(ctx)).also { renderableEmoji = it }
            val res = if (q.isBlank()) EmojiData.recents(ctx)
                else EmojiAnnotations.get(ctx, locale).search(q).filter { it in renderable }
            main.post { if (emojiSearchView === v && searchConnection?.query == q) v.show(q, res) }
        }
    }

    /** Leaves emoji search and gives the keyboard back to the app's field (letters page). */
    private fun closeEmojiSearch() {
        if (searchConnection == null) return
        controller.finishInput()
        dropEmojiSearch()
        built.clear()
        controller.startInput(editor, appSelStart, appSelEnd, true, imeInternal = true)
        showPage(Page.LETTERS)
        controller.updateShift(force = true)
    }

    private fun dropEmojiSearch() {
        if (searchConnection == null) return
        searchConnection = null
        emojiSearchView = null
        editor = appEditor ?: EditorState(currentInputEditorInfo)
        appEditor = null
    }

    // ------------------------------------------------------------------ translate

    private var translate: TranslateMode? = null
    private var translateOverlay: View? = null

    /**
     * Toolbar › Translate (Gboard 18.6 layout): the translate band above the strip and the letters page; typing goes
     * into the band's box and the on-device translation is written into the app's field (see [TranslateMode]).
     */
    override fun openTranslate() {
        if (translate != null) { closeTranslate(); return }
        if (editor.isPassword || editor.isNull) return
        val r = root ?: return
        val bar = topBar ?: return
        val kv = keyboardView ?: return
        restoreKeyboard()
        controller.finishInput()
        val colors = TranslateColors.resolve(this)
        val mode = TranslateMode(this, dims, colors, object : TranslateMode.Host {
            override val appConnection: InputConnection? get() = currentInputConnection
            override fun boxView(): View = kv
            override fun showOverlay(view: View?) = showTranslateOverlay(view)
            override fun requestClose() = closeTranslate()
            override fun openSettings(route: String) { closeTranslate(); this@PboardIme.openSettings(route) }
            override fun restartBox() {
                controller.finishInput()
                controller.startInput(editor, 0, 0, true, imeInternal = true)
                controller.updateShift(force = true)
            }
        }, controller.locale.language)
        appEditor = editor
        translate = mode
        val info = EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or
                (if (appEditor?.incognito == true) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
            packageName = this@PboardIme.packageName
        }
        editor = EditorState(info, internal = true)
        built.clear()
        kv.cancelAllTrackers()
        r.setPanelContent(mode.bar, bar, kv)
        bar.activePill = colors.activePill
        bar.activeIcon = colors.activeIcon
        bar.activeItems = setOf(ToolbarItems.TRANSLATE)
        controller.startInput(editor, 0, 0, false)
        showPage(Page.LETTERS)
        controller.updateShift(force = true)
    }

    /** Leaves Translate: the translation stays in the app's field, the keyboard types into the app again. */
    private fun closeTranslate() {
        if (translate == null) return
        controller.finishInput()
        dropTranslate()
        built.clear()
        controller.startInput(editor, appSelStart, appSelEnd, true, imeInternal = true)
        root?.let { r -> topBar?.let { bar -> keyboardView?.let { kv -> r.setPanelContent(bar, kv) } } }
        showPage(Page.LETTERS)
        controller.updateShift(force = true)
    }

    private fun dropTranslate() {
        val t = translate ?: return
        translate = null
        t.close()
        showTranslateOverlay(null)
        topBar?.activeItems = emptySet()
        editor = appEditor ?: EditorState(currentInputEditorInfo)
        appEditor = null
    }

    /** A full-window view over the app and keyboard (the language picker); the whole window takes touches meanwhile. */
    private fun showTranslateOverlay(view: View?) {
        val r = root ?: return
        translateOverlay?.let { r.removeView(it) }
        translateOverlay = view
        if (view != null) {
            r.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            view.requestFocus()
        }
        r.requestLayout()
    }

    override fun openClipboard() {
        if (translate != null) closeTranslate()
        val r = root ?: return
        val height = (topBar?.height ?: 0) + (keyboardView?.height ?: 0)
        val v = ClipboardPanelView(this, dims, KeyboardTheme.colors(this), clipboard, height).apply {
            listener = object : ClipboardPanelListener {
                override fun onClipPaste(text: String) = controller.insertText(text)
                override fun onClipboardClose() = restoreKeyboard()
                override fun onClipboardToggle(enabled: Boolean) {
                    PboardPrefs.get(this@PboardIme)[Prefs.clipboardHistory] = enabled
                    clipboard.enabled = enabled
                    invalidate()
                }
            }
        }
        clipboardView = v
        keyboardView?.cancelAllTrackers()
        r.setPanelContent(v)
    }

    // ------------------------------------------------------------------ stickers and scan text (dev.pboard.ime.rich, .scan)

    private var stickerView: dev.pboard.ime.rich.StickerPanelView? = null

    /** Toolbar "Stickers": the user's own stickers, sent with commitContent where the app accepts images. */
    override fun openStickers() {
        val r = root ?: return
        val colors = KeyboardTheme.colors(this)
        val store = dev.pboard.ime.rich.StickerStore(this)
        val v = dev.pboard.ime.rich.StickerPanelView(this, dims, colors)
        fun reload() = worker.execute {
            val recents = store.recents()
            val packs = store.packs()
            main.post { if (stickerView === v) v.setData(recents, packs) }
        }
        v.listener = object : dev.pboard.ime.rich.StickerPanelListener {
            override fun onSticker(s: dev.pboard.ime.rich.StickerStore.Sticker) {
                val ok = dev.pboard.ime.rich.RichContent.commit(this@PboardIme, currentInputConnection, currentInputEditorInfo, s.file, s.mime, "Sticker")
                if (ok) { if (!editor.incognito) worker.execute { store.addRecent(s) } }
                else android.widget.Toast.makeText(this@PboardIme, "This app doesn't accept stickers here", android.widget.Toast.LENGTH_SHORT).show()
            }
            override fun onStickerRemove(s: dev.pboard.ime.rich.StickerStore.Sticker) {
                worker.execute { store.delete(s) }
                reload()
            }
            override fun onStickerAdd() {
                startActivity(Intent(this@PboardIme, dev.pboard.ime.rich.StickerImportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            override fun onStickerClose() = restoreKeyboard()
            override fun onStickerEmoji() { restoreKeyboard(); openEmoji() }
            override fun onStickerDelete() = controller.onDelete(false)
        }
        stickerView = v
        reload()
        keyboardView?.cancelAllTrackers()
        r.setPanelContent(v)
        r.bandColor = colors.emojiBackground
    }

    /** Toolbar "Scan text": the scan screen; its text comes back through ScanResult when this field reopens. */
    override fun openScanText() {
        if (!dev.pboard.ime.scan.Ocr.available(this)) {
            // The OCR engine and model ship in the separately installed scan pack (dev.pboard.pack.scan).
            android.widget.Toast.makeText(this, "Scan text needs the pboard scan pack", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        startActivity(dev.pboard.ime.scan.ScanTextActivity.intent(this, editor.info?.packageName, editor.info?.fieldId ?: 0))
    }

    /** Whether anything other than the keys is showing in the keyboard area. */
    private fun anyPanelOpen(): Boolean =
        toolbarPanel != null || textEditingPanel != null || emojiView != null || clipboardView != null ||
            stickerView != null || root?.resizeView != null || searchConnection != null || voicePanel != null

    private fun restoreKeyboard() {
        if (searchConnection != null) closeEmojiSearch()
        if (translate != null) closeTranslate()
        if (voicePanel != null) dropVoice()
        val r = root ?: return
        val bar = topBar ?: return
        val kv = keyboardView ?: return
        emojiView = null
        clipboardView = null
        stickerView = null
        toolbarPanel = null
        textEditingPanel = null
        r.resizeView = null
        r.overlay.setTooltip(null)
        bar.expanded = false
        r.setPanelContent(bar, kv)
    }

    private var toolbarPanel: ToolbarPanelView? = null

    /** Opens/closes the "more" panel of toolbar items that are not pinned on the bar. */
    override fun toggleToolbarPanel() {
        if (toolbarPanel != null || textEditingPanel != null) { restoreKeyboard(); return }
        if (translate != null) closeTranslate()
        val r = root ?: return
        val bar = topBar ?: return
        val kv = keyboardView ?: return
        val prefs = PboardPrefs.get(this)
        val items = ToolbarLayout.load(prefs).panel.filter { it.isOffered(largeScreen = false, Config.current.voiceTyping) }
        val panel = ToolbarPanelView(this, dims, KeyboardTheme.colors(this), items, keysHeight(kv)) { id ->
            restoreKeyboard()
            controller.onToolbarItem(id)
        }
        panel.dragHost = this
        toolbarPanel = panel
        kv.cancelAllTrackers()
        bar.expanded = true
        r.setPanelContent(bar, panel)
        // "Drag here to replace" points at the power key while customising. Gboard 15.1 showed it at most twice ever
        // and hid it after 4.2 s; 18.6 dropped it. Bug report:
        // pboard showed it on every opening.
        val hintState = createDeviceProtectedStorageContext().getSharedPreferences("pboard_hints", MODE_PRIVATE)
        val shown = hintState.getInt(POWER_KEY_HINT_SHOWN, 0)
        if (bar.powerKey != null && shown < POWER_KEY_HINT_MAX) bar.post {
            if (toolbarPanel !== panel) return@post
            val b = IntArray(2).also { bar.getLocationInWindow(it) }
            val o = IntArray(2).also { r.overlay.getLocationInWindow(it) }
            r.overlay.setTooltip("Drag here to replace", b[0] - o[0] + bar.powerKeyCenterX, (b[1] - o[1]).toFloat() - dims.m(2f))
            hintState.edit().putInt(POWER_KEY_HINT_SHOWN, shown + 1).apply()
            bar.postDelayed({ if (toolbarPanel === panel) r.overlay.setTooltip(null) }, POWER_KEY_HINT_MS)
        }
    }

    override fun openSettings() = openSettings(null)

    /** Height of the keys area (laid out, or as built when the views were just recreated). */
    private fun keysHeight(kv: KeyboardView) = kv.height.takeIf { it > 0 } ?: buildPage(page).height

    // ------------------------------------------------------------------ text editing panel

    private var textEditingPanel: TextEditingPanelView? = null

    /** Toolbar "Text editing": cursor pad, selection and clipboard actions in place of the keys. */
    override fun openTextEditing() {
        if (translate != null) closeTranslate()
        val r = root ?: return
        val bar = topBar ?: return
        val kv = keyboardView ?: return
        controller.finishInput()
        val panel = TextEditingPanelView(this, dims, KeyboardTheme.colors(this), dims.barHeight.toInt() + keysHeight(kv))
        panel.hasSelection = appSelStart != appSelEnd
        refreshEditingState(panel)
        panel.listener = object : TextEditingListener {
            private fun key(code: Int, meta: Int) {
                val ic = currentInputConnection ?: return
                val now = android.os.SystemClock.uptimeMillis()
                ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
                ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0, meta))
            }
            override fun onEditMove(direction: Int, select: Boolean) {
                val shift = if (select) android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON else 0
                val ctrl = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
                when (direction) {
                    TextEditingPanelView.UP -> key(android.view.KeyEvent.KEYCODE_DPAD_UP, shift)
                    TextEditingPanelView.DOWN -> key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, shift)
                    TextEditingPanelView.LEFT -> key(android.view.KeyEvent.KEYCODE_DPAD_LEFT, shift)
                    TextEditingPanelView.RIGHT -> key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, shift)
                    TextEditingPanelView.HOME -> key(android.view.KeyEvent.KEYCODE_MOVE_HOME, shift or ctrl)
                    TextEditingPanelView.END -> key(android.view.KeyEvent.KEYCODE_MOVE_END, shift or ctrl)
                }
            }
            override fun onEditSelectAll() { currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }
            override fun onEditCopy() { currentInputConnection?.performContextMenuAction(android.R.id.copy) }
            override fun onEditCut() { currentInputConnection?.performContextMenuAction(android.R.id.cut) }
            override fun onEditPaste() { currentInputConnection?.performContextMenuAction(android.R.id.paste) }
            override fun onEditDelete() = controller.onDelete(false)
            override fun onEditSelectEnd() {
                if (appSelEnd >= 0) currentInputConnection?.setSelection(appSelEnd, appSelEnd)
            }
            override fun onEditClose() = restoreKeyboard()
        }
        textEditingPanel = panel
        toolbarPanel = null
        kv.cancelAllTrackers()
        r.setPanelContent(panel)
    }

    /** Greys the text-editing keys that cannot act: empty field, empty clipboard. */
    private fun refreshEditingState(panel: TextEditingPanelView) {
        val ic = currentInputConnection
        panel.hasText = !ic?.getTextBeforeCursor(1, 0).isNullOrEmpty() || !ic?.getTextAfterCursor(1, 0).isNullOrEmpty()
        panel.canPaste = getSystemService(android.content.ClipboardManager::class.java)?.hasPrimaryClip() == true
    }

    // ------------------------------------------------------------------ toolbar drag-to-customise

    private sealed interface DropTarget {
        data class Bar(val index: Int) : DropTarget
        data class Panel(val index: Int) : DropTarget
        data object Power : DropTarget
        data object None : DropTarget
    }

    private var dragItem: dev.pboard.ime.toolbar.ToolbarItem? = null
    private var dragTarget: DropTarget = DropTarget.None

    override fun dragStart(id: String, fromBar: Boolean, rawX: Float, rawY: Float) {
        dragItem = dev.pboard.ime.toolbar.ToolbarItem.entries.firstOrNull { it.id == id } ?: return
        topBar?.barCapacity = dev.pboard.ime.toolbar.ToolbarItem.BAR_CAPACITY
        root?.overlay?.setTooltip(null)
        dragMove(rawX, rawY)
    }

    override fun dragMove(rawX: Float, rawY: Float) {
        val item = dragItem ?: return
        val bar = topBar ?: return
        val panel = toolbarPanel ?: return
        val r = root ?: return
        val barLoc = IntArray(2).also { bar.getLocationOnScreen(it) }
        val panelLoc = IntArray(2).also { panel.getLocationOnScreen(it) }
        val bx = rawX - barLoc[0]
        val by = rawY - barLoc[1]
        val px = rawX - panelLoc[0]
        val py = rawY - panelLoc[1]
        dragTarget = when {
            bar.isOverPowerKey(bx, by) -> DropTarget.Power
            by > -dims.m(30f) && by < bar.height + dims.m(10f) && bar.isInBarZone(bx) -> DropTarget.Bar(bar.barInsertIndex(bx))
            px >= 0 && py >= 0 && px < panel.width && py < panel.height -> DropTarget.Panel(panel.insertIndexAt(px, py))
            else -> DropTarget.None
        }
        bar.setDragPreview(item.id, (dragTarget as? DropTarget.Bar)?.index ?: -1, dragTarget == DropTarget.Power)
        panel.setDragPreview(item.id, (dragTarget as? DropTarget.Panel)?.index ?: -1)
        val rootLoc = IntArray(2).also { r.overlay.getLocationOnScreen(it) }
        r.overlay.setDragGhost(ToolbarPanelView.symbolFor(item.symbol), rawX - rootLoc[0], rawY - rootLoc[1])
    }

    /** Applies the drop with Gboard's rules (ToolbarLayout) and saves the arrangement. NaN = cancelled. */
    override fun dragEnd(rawX: Float, rawY: Float) {
        val item = dragItem ?: return
        if (!rawX.isNaN()) dragMove(rawX, rawY)
        val target = if (rawX.isNaN()) DropTarget.None else dragTarget
        dragItem = null
        dragTarget = DropTarget.None
        root?.overlay?.setDragGhost(null, 0f, 0f)
        topBar?.setDragPreview(null, -1, false)
        toolbarPanel?.setDragPreview(null, -1)
        val prefs = PboardPrefs.get(this)
        val layout = ToolbarLayout.load(prefs)
        // Indices from the views count only offered items; map them onto the full stored order.
        val offered = layout.offered(largeScreen = false, Config.current.voiceTyping)
        val next = when (target) {
            is DropTarget.Bar -> {
                val pinned = offered.pinned.filter { it != item }
                val before = pinned.getOrNull(target.index)
                layout.moveToBar(item, if (before == null) layout.pinned.filter { it != item }.size else layout.pinned.filter { it != item }.indexOf(before))
            }
            is DropTarget.Panel -> {
                val rest = offered.panel.filter { it != item }
                val before = rest.getOrNull(target.index)
                val full = layout.panel.filter { it != item }
                layout.moveToPanel(item, if (before == null) full.size else full.indexOf(before))
            }
            DropTarget.Power -> layout.replacePowerKey(item)
            DropTarget.None -> null
        } ?: return
        if (next == layout) return
        next.save(prefs)
        val shown = next.offered(largeScreen = false, Config.current.voiceTyping)
        topBar?.toolbarItems = shown.pinned.map { it.id }
        topBar?.powerKey = shown.powerKey?.id
        toolbarPanel?.items = shown.panel
    }

    /** Opens the settings app, optionally on a route ("theme", "toolbar", ...). */
    override fun openSettings(route: String?) {
        val i = Intent(this, dev.pboard.SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (route != null) i.putExtra(SETTINGS_ROUTE_EXTRA, route)
        startActivity(i)
    }

    /** Globe key / Shift+Space: the next keyboard enabled in Settings › Languages (en-GB <-> en-US). */
    override fun switchLanguage() {
        val next = KeyboardLanguages.next(LanguageCatalog.enabled(this), currentLocaleTag()) ?: return
        PboardPrefs.get(this)[Prefs.activeLanguage] = next.languageTag
        applyActiveLanguage()
    }

    override fun showInputMethodPicker() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun emojiCommitted(emoji: String) = EmojiData.addRecent(this, emoji)

    override fun performFeedbackKeyDown(code: Int) {
        keyboardView?.let { feedback.keyDown(it, code) }
    }

    override fun tick() { keyboardView?.let { feedback.tick(it) } }

    override fun sendKey(keyCode: Int) = sendDownUpKeyEvents(keyCode)

    override fun performEditorAction(action: Int) {
        if (searchConnection != null) return
        translate?.let { it.connection.performEditorAction(action); return }
        currentInputConnection?.performEditorAction(action)
    }

    override fun hideKeyboard() = requestHideSelf(0)

    /**
     * The mic key. With the voice pack installed (dev.pboard.ime.voice.VoicePack) pboard listens itself: on-device
     * streaming recognition, words appearing in the field as they are spoken. Without it the key hands over to an
     * enabled voice-input IME if the user has one, the way Gboard switches to its voice mode.
     */
    override fun startVoiceInput() {
        if (!Config.current.voiceTyping) return // Settings › Voice typing off: no mic anywhere (Alt+D included)
        if (dev.pboard.ime.voice.VoicePack.available(this)) { openVoice(); return }
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == packageName) continue
            val voice = imm.getEnabledInputMethodSubtypeList(imi, true).firstOrNull { it.mode == "voice" } ?: continue
            switchInputMethod(imi.id, voice)
            return
        }
        android.widget.Toast.makeText(this, "Voice typing needs the pboard voice pack", android.widget.Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ voice typing

    private var voicePanel: dev.pboard.ime.voice.VoicePanelView? = null
    private var voiceSession: dev.pboard.ime.voice.VoiceSession? = null
    /** Text left of the cursor when the current utterance started (spacing and capitalisation of what comes next). */
    private var voiceBefore: CharSequence = ""
    /** The mic key asked for the microphone permission: start listening when the keyboard is next shown. */
    private var voicePending = false

    /** Opens the voice panel and starts listening; first asks for the microphone if it was never granted. */
    private fun openVoice(wav: java.io.File? = null) {
        if (!editor.voiceAllowed) return
        if (wav == null && !dev.pboard.ime.voice.VoicePermissionActivity.granted(this)) {
            voicePending = true
            startActivity(dev.pboard.ime.voice.VoicePermissionActivity.intent(this))
            return
        }
        if (translate != null) closeTranslate()
        val r = root ?: return
        val bar = topBar ?: return
        val kv = keyboardView ?: return
        if (anyPanelOpen()) restoreKeyboard()
        controller.finishInput()
        val panel = dev.pboard.ime.voice.VoicePanelView(this, dims, KeyboardTheme.colors(this), keysHeight(kv))
        panel.listener = object : dev.pboard.ime.voice.VoicePanelView.Listener {
            override fun onVoiceToggle() {
                if (voiceSession != null) voiceSession?.stop() else startVoiceSession(panel, wav)
            }
            override fun onVoiceDelete() {
                currentInputConnection?.finishComposingText()
                controller.onDelete(false)
            }
            override fun onVoiceClose() = restoreKeyboard()
        }
        voicePanel = panel
        kv.cancelAllTrackers()
        r.setPanelContent(bar, panel)
        startVoiceSession(panel, wav)
    }

    private fun startVoiceSession(panel: dev.pboard.ime.voice.VoicePanelView, wav: java.io.File?) {
        voiceSession?.let { it.cancel(); it.join(1000) }
        val engine = runCatching { dev.pboard.ime.voice.VoicePack.engine(this) }.getOrElse {
            android.util.Log.w("pboard", "voice pack could not be loaded", it)
            panel.message = "Voice pack could not be loaded"
            return
        }
        main.removeCallbacks(releaseVoiceModel)
        voiceEngine = engine
        val ic = currentInputConnection
        voiceBefore = ic?.getTextBeforeCursor(VOICE_CONTEXT, 0) ?: ""
        val autoCaps = editor.kind != dev.pboard.ime.layout.FieldKind.URL && editor.kind != dev.pboard.ime.layout.FieldKind.EMAIL
        panel.message = null
        panel.state = dev.pboard.ime.voice.VoicePanelView.State.LOADING
        val source = if (wav != null) dev.pboard.ime.voice.WavSource(wav) else dev.pboard.ime.voice.MicSource()
        lateinit var session: dev.pboard.ime.voice.VoiceSession
        session = dev.pboard.ime.voice.VoiceSession(engine, source, main, object : dev.pboard.ime.voice.VoiceSession.Listener {
            override fun onListening() { panel.state = dev.pboard.ime.voice.VoicePanelView.State.LISTENING }
            override fun onPartial(text: String) {
                currentInputConnection?.setComposingText(dev.pboard.ime.voice.VoiceText.fit(text, voiceBefore, autoCaps), 1)
            }
            override fun onFinal(text: String) {
                val ic2 = currentInputConnection ?: return
                val t = dev.pboard.ime.voice.VoiceText.fit(text, voiceBefore, autoCaps)
                if (t.isEmpty()) ic2.setComposingText("", 1) else ic2.commitText(t, 1)
                ic2.finishComposingText()
                voiceBefore = ic2.getTextBeforeCursor(VOICE_CONTEXT, 0) ?: (voiceBefore.toString() + t)
            }
            override fun onLevel(level: Float) = panel.setLevel(level)
            override fun onStopped(error: String?) {
                if (error != null) android.util.Log.w("pboard", "voice typing stopped: $error")
                if (session.loadMs > 0) android.util.Log.i("pboard", "voice model loaded in ${session.loadMs} ms")
                voiceSession = null
                keepVoiceModelWarm()
                currentInputConnection?.finishComposingText()
                panel.state = dev.pboard.ime.voice.VoicePanelView.State.STOPPED
                if (error != null) panel.message = if (error.contains("microphone")) "Microphone unavailable" else "Voice typing stopped"
            }
        })
        voiceSession = session
        session.start()
    }

    /** Ends voice typing without waiting for the recogniser: what is shown in the field stays. */
    private fun dropVoice() {
        voiceSession?.cancel()
        voiceSession = null
        voicePanel = null
        currentInputConnection?.finishComposingText()
        keepVoiceModelWarm()
    }

    /** The loaded speech model (hundreds of MB): kept for [VOICE_KEEP_WARM_MS] after use, never loaded before a mic tap. */
    private var voiceEngine: dev.pboard.ime.voice.VoicePack.Engine? = null
    private val releaseVoiceModel = Runnable {
        if (voiceSession != null) return@Runnable
        voiceEngine?.let { runCatching { it.release() } }
        voiceEngine = null
    }

    private fun keepVoiceModelWarm() {
        main.removeCallbacks(releaseVoiceModel)
        if (voiceEngine != null) main.postDelayed(releaseVoiceModel, VOICE_KEEP_WARM_MS)
    }

    companion object {
        private const val POWER_KEY_HINT_SHOWN = "power_key_hint_shown_times"
        private const val POWER_KEY_HINT_MAX = 2
        private const val POWER_KEY_HINT_MS = 4200L
        const val DEBUG_ACTION = "dev.pboard.DEBUG"
        const val SAVE_DEBOUNCE_MS = 5000L
        const val SETTINGS_ROUTE_EXTRA = "dev.pboard.extra.SETTINGS_ROUTE"
        /** Characters of field text the voice-typing text fitting looks at. */
        const val VOICE_CONTEXT = 64
        /** How long the speech model stays loaded after voice typing ends (a second dictation starts instantly). */
        const val VOICE_KEEP_WARM_MS = 3 * 60 * 1000L
    }
}
