package dev.pboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * A bare text field used for screenshot comparison and benchmarking (tools/bench).
 *
 * It is a plain [EditText] with a normal (non-password) text input type, so whatever IME is active
 * autocorrects and predicts as it would in a messaging app. The benchmark harness drives it with
 * ordered broadcasts (action [ACTION], explicit package `dev.pboard`), extra `cmd`:
 *  - `reset`: set the field to extra `text` (default ""), cursor at the end, optionally switch the
 *    input variant (extra `variant`, see [VARIANTS]) or raw `inputType`/`imeOptions` ints, hide or show the cursor
 *    (boolean extra `cursor`; slowed-down screen recordings hide it, its blinking would be sped up with them), then
 *    restart input so the IME re-reads the preceding context.
 *  - `get`: report the field's full text, selection and composing region.
 *  - `probe_start` / `probe_get`: record / dump every MotionEvent the activity window receives
 *    (used by the injection timing test; touches on the IME window are not seen here).
 *  - `hist` (conformance harness, tools/conformance): every change of text, selection or composing
 *    region, every editor action and every key event the field received, with its uptime, since
 *    extra `since` (long uptime, default 0); at most the last [HISTORY_MAX] entries are kept.
 *  - `settext` / `setsel`: the app changes the text (extra `text`, cursor to extras `selStart`/`selEnd`
 *    or the end) or only the selection, *without* restarting input, as a chat app does after Send.
 *  - `pos`: screen coordinates of the caret position before character extra `offset`.
 *  - `spans`: like `get`, plus every span on the text (class, range, flags and, where the span has them, its
 *    colours, suggestions and SuggestionSpan flags): how an IME styles composing text, grammar marks or
 *    inline completions.
 * The reply is a JSON object in the broadcast result data. Every text change is also logged to
 * logcat under tag `PboardBench` for manual debugging. The same extras work on an `am start` intent.
 */
class PlaygroundActivity : ComponentActivity() {
    private lateinit var field: EditText
    private var variant = "default"
    private val probe = ArrayList<JSONObject>()
    private var probing = false
    private val history = ArrayDeque<JSONObject>()
    /** Field changes (text, selection, actions) so far; the bench's quiescence check reads it with lastChangeUptime. */
    private var changeCount = 0L

    /** Appends the field's current state to [history] (`ev`: what changed). */
    private fun record(ev: String) {
        if (!::field.isInitialized) return
        val e = field.text ?: return
        val entry = JSONObject().put("t", SystemClock.uptimeMillis()).put("ev", ev).put("text", e.toString())
            .put("s", field.selectionStart).put("e", field.selectionEnd)
            .put("cs", BaseInputConnection.getComposingSpanStart(e)).put("ce", BaseInputConnection.getComposingSpanEnd(e))
        changeCount++
        synchronized(history) {
            history.addLast(entry)
            while (history.size > HISTORY_MAX) history.removeFirst()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reply = handle(intent)
            if (isOrderedBroadcast) setResultData(reply.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = object : EditText(this) {
            // Selection-only changes (cursor moves) are not seen by a TextWatcher.
            override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                super.onSelectionChanged(selStart, selEnd)
                record("sel")
            }
        }.apply {
            id = android.R.id.edit
            hint = "Type here"
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            inputType = VARIANTS.getValue("default").first
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable) {
                    Log.i(TAG, "text=" + JSONObject.quote(s.toString()))
                    record("text")
                }
            })
            // Logged only; returning false keeps the platform's default handling of the action.
            setOnEditorActionListener { _, actionId, _ -> record("action:$actionId"); false }
        }
        val status = TextView(this).apply { text = "pboard playground" ; alpha = 0.5f }
        actionBar?.hide()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(field, LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -1))
            // targetSdk 35+ is edge-to-edge: keep the field clear of the status bar.
            if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, 0)
                insets
            }
        })
        field.requestFocus()
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
        intent?.let { if (it.hasExtra("cmd")) field.post { handle(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("cmd")) handle(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Key events an IME sends (e.g. DEL in an empty field, TP-17) are part of the history.
        record("key:${event.action}:${event.keyCode}")
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (probing) {
            val now = SystemClock.uptimeMillis()
            val hist = JSONArray()
            for (h in 0 until ev.historySize) {
                hist.put(JSONArray().put(ev.getHistoricalX(h)).put(ev.getHistoricalY(h)).put(ev.getHistoricalEventTime(h)))
            }
            probe.add(JSONObject().put("action", ev.actionMasked).put("t", ev.eventTime).put("down", ev.downTime)
                .put("recv", now).put("x", ev.x).put("y", ev.y).put("hist", hist))
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handle(intent: Intent): JSONObject {
        val out = JSONObject()
        when (intent.getStringExtra("cmd")) {
            "reset" -> {
                val v = intent.getStringExtra("variant")
                var type = field.inputType
                var opts = field.imeOptions
                if (v != null) {
                    val p = VARIANTS[v] ?: return out.put("error", "unknown variant $v")
                    variant = v; type = p.first; opts = p.second
                }
                if (intent.hasExtra("inputType")) type = intent.getIntExtra("inputType", type)
                if (intent.hasExtra("imeOptions")) opts = intent.getIntExtra("imeOptions", opts)
                if (type != field.inputType) field.inputType = type
                if (opts != field.imeOptions) field.imeOptions = opts
                if (intent.hasExtra("privateImeOptions")) field.privateImeOptions = intent.getStringExtra("privateImeOptions")
                if (intent.hasExtra("cursor")) field.isCursorVisible = intent.getBooleanExtra("cursor", true)
                val text = intent.getStringExtra("text") ?: ""
                field.setText(text)
                field.setSelection(text.length)
                field.requestFocus()
                val imm = getSystemService(InputMethodManager::class.java)
                imm.restartInput(field)
                imm.showSoftInput(field, 0)
                describe(out)
            }
            "get" -> describe(out)
            "hist" -> {
                val since = intent.getLongExtra("since", 0L)
                val list = JSONArray()
                synchronized(history) { for (h in history) if (h.getLong("t") >= since) list.put(h) }
                describe(out).put("hist", list)
            }
            "settext" -> {
                val text = intent.getStringExtra("text") ?: ""
                field.setText(text)
                val s = intent.getIntExtra("selStart", text.length).coerceIn(0, text.length)
                field.setSelection(s, intent.getIntExtra("selEnd", s).coerceIn(s, text.length))
                describe(out)
            }
            "setsel" -> {
                val n = field.text.length
                val s = intent.getIntExtra("selStart", n).coerceIn(0, n)
                field.setSelection(s, intent.getIntExtra("selEnd", s).coerceIn(s, n))
                describe(out)
            }
            "pos" -> {
                val layout = field.layout
                if (layout == null) out.put("error", "no layout") else {
                    val off = intent.getIntExtra("offset", 0).coerceIn(0, field.text.length)
                    val line = layout.getLineForOffset(off)
                    val loc = IntArray(2)
                    field.getLocationOnScreen(loc)
                    val x = loc[0] + field.totalPaddingLeft - field.scrollX + layout.getPrimaryHorizontal(off)
                    val y = loc[1] + field.totalPaddingTop - field.scrollY + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                    out.put("x", x.toDouble()).put("y", y.toDouble()).put("line", line)
                }
                describe(out)
            }
            "spans" -> describe(out).put("spans", spans())
            "spell" -> {
                // The system spell checker's state on the field: words still being checked (SpellCheckSpan) and
                // words it marked (red underline; tapping one opens the TextView's suggestion popup). The
                // conformance runner waits for this to settle before tapping into text.
                val e = field.text
                var pending = 0
                var marked = 0
                for (sp in e.getSpans(0, e.length, Any::class.java)) {
                    if (sp.javaClass.simpleName == "SpellCheckSpan") pending++
                    if (sp is android.text.style.SuggestionSpan && sp.flags and
                        (android.text.style.SuggestionSpan.FLAG_EASY_CORRECT or android.text.style.SuggestionSpan.FLAG_MISSPELLED) != 0
                    ) marked++
                }
                describe(out).put("spellPending", pending).put("spellMarked", marked)
            }
            "probe_start" -> { probe.clear(); probing = true; out.put("ok", true) }
            "probe_get" -> { probing = false; out.put("events", JSONArray(probe)); probe.clear() }
            else -> out.put("error", "unknown cmd")
        }
        return out
    }

    /** Every span on the field's text, IME-set ones included (see the `spans` command). */
    private fun spans(): JSONArray {
        val e = field.text
        val list = JSONArray()
        for (sp in e.getSpans(0, e.length, Any::class.java)) {
            val o = JSONObject().put("type", sp.javaClass.name).put("start", e.getSpanStart(sp)).put("end", e.getSpanEnd(sp))
                .put("flags", e.getSpanFlags(sp))
            when (sp) {
                is android.text.style.ForegroundColorSpan -> o.put("color", String.format("#%08X", sp.foregroundColor))
                is android.text.style.BackgroundColorSpan -> o.put("color", String.format("#%08X", sp.backgroundColor))
                is android.text.style.UnderlineSpan -> o.put("underline", true)
                is android.text.style.SuggestionSpan -> {
                    o.put("suggestionFlags", sp.flags).put("suggestions", JSONArray(sp.suggestions.toList()))
                    o.put("underlineColor", String.format("#%08X", sp.underlineColor))
                }
            }
            list.put(o)
        }
        return list
    }

    private fun describe(out: JSONObject): JSONObject {
        val e = field.text
        return out.put("text", e.toString())
            .put("selStart", field.selectionStart).put("selEnd", field.selectionEnd)
            .put("composingStart", BaseInputConnection.getComposingSpanStart(e))
            .put("composingEnd", BaseInputConnection.getComposingSpanEnd(e))
            .put("variant", variant).put("inputType", field.inputType).put("imeOptions", field.imeOptions)
            .put("focused", field.hasFocus()).put("windowFocus", hasWindowFocus()).put("uptime", SystemClock.uptimeMillis())
            .put("lastChangeUptime", synchronized(history) { history.lastOrNull()?.optLong("t") ?: 0L })
            .put("changeCount", changeCount)
    }

    companion object {
        const val TAG = "PboardBench"
        const val HISTORY_MAX = 4000
        const val ACTION = "dev.pboard.bench.PLAYGROUND"
        private const val TEXT = InputType.TYPE_CLASS_TEXT
        /** Named field variants: inputType to imeOptions. "default" behaves like a chat message box. */
        val VARIANTS: Map<String, Pair<Int, Int>> = mapOf(
            "default" to Pair(TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_NONE),
            "shortmessage" to Pair(TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, EditorInfo.IME_ACTION_SEND),
            "nocap" to Pair(TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_NONE),
            "plain" to Pair(TEXT, EditorInfo.IME_ACTION_DONE),
            "nosuggest" to Pair(TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_DONE),
            "email" to Pair(TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_NEXT),
            "uri" to Pair(TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO),
            "search" to Pair(TEXT, EditorInfo.IME_ACTION_SEARCH),
            "incognito" to Pair(TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
                EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        )
    }
}
