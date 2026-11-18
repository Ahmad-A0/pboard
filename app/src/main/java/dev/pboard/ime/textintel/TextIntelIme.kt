package dev.pboard.ime.textintel

import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import dev.pboard.ime.EditorState
import dev.pboard.ime.InputController
import dev.pboard.ime.SerialWorker
import dev.pboard.ime.config.Config
import java.util.Locale

/**
 * The keyboard service's side of text intelligence, so [dev.pboard.ime.PboardIme] needs only one call per lifecycle
 * event: loads the models for the typing language, owns Smart Compose ([ComposeController] and its [GhostTextView]),
 * and wires the space-bar accept gesture into the [InputController]. The grammar chip lives in the controller
 * ([GrammarAssist]); the grammar marks come from [PboardSpellCheckerService].
 */
class TextIntelIme(private val ime: InputMethodService, private val controller: InputController) : ComposeController.Host {
    private val loader = SerialWorker("pboard-textintel-load")
    @Volatile override var models: TextIntelModels? = null
        private set
    override var ghost: GhostTextView? = null
        private set
    private val compose = ComposeController(ime, this)
    private var locale: Locale = Locale.US

    init {
        // a rightward swipe on the space bar accepts a shown completion (Gboard: "Swipe on space bar to accept")
        controller.acceptCompletion = { compose.accept() }
    }

    override val inputConnection: InputConnection? get() = ime.currentInputConnection

    /** The typing language changed: (re)load its models in the background. */
    fun setLocale(l: Locale) {
        locale = l
        compose.locale = l
        loader.execute { TextIntel.backgroundPriority(); models = TextIntel.models(ime, l) }
    }

    /** Adds the ghost-text layer to the keyboard's full-window root. */
    fun attach(root: ViewGroup) {
        val g = GhostTextView(ime) { compose.accept() }
        ghost = g
        root.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun startInput(editor: EditorState) {
        // Android 14+: the app reports its text size, font and colour (TextAppearanceInfo), so the overlay matches the
        // field in any app. Gboard limits Smart Compose to an allow-list of messaging apps; by default pboard limits it
        // to message-like fields (MessageFields), or shows it everywhere if the user chose that.
        val scope = Config.current.smartComposeScope
        val fieldOk = scope == "everywhere" ||
            scope == "messages" && MessageFields.isMessage(editor.inputType, editor.info?.imeOptions ?: 0)
        val on = Build.VERSION.SDK_INT >= 34 && Config.current.smartCompose && fieldOk && editor.suggestions &&
            !editor.noLearning && !editor.exactKeys && !editor.internal && locale.language == "en" && !disabledByApp(editor)
        compose.active = on
        if (TextIntel.debug) android.util.Log.d(TextIntel.TAG, "compose active=$on scope=$scope inputType=${editor.inputType} imeOptions=${editor.info?.imeOptions}")
        if (on) {
            val ic = ime.currentInputConnection ?: return
            if (Build.VERSION.SDK_INT >= 34) {
                ic.requestCursorUpdates(
                    InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE,
                    InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER or InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS or
                        InputConnection.CURSOR_UPDATE_FILTER_TEXT_APPEARANCE,
                )
            } else {
                ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE)
            }
        }
    }

    /** Gboard honours `disableSmartCompose` in the app's private IME options (also package-prefixed). */
    private fun disabledByApp(editor: EditorState): Boolean =
        editor.info?.privateImeOptions?.split(',', ' ', ';')?.any { it.trim().endsWith("disableSmartCompose") } == true

    fun onSelectionChanged(selStart: Int, selEnd: Int) = compose.onSelectionChanged(selStart, selEnd)

    fun onCursorAnchor(info: CursorAnchorInfo) = compose.onCursorAnchor(info)

    fun finishInput() {
        compose.active = false
    }

    /** Adds the ghost text to the window's touchable area while it is shown (tap or swipe on it to accept). */
    fun addTouchable(region: Region) {
        ghost?.bounds?.let { region.op(it, Region.Op.UNION) }
    }

    override fun insertCompletion(text: String) = controller.insertCompletion(text)

    override fun touchableChanged() {
        ghost?.requestLayout()
    }

    fun shutdown() {
        compose.shutdown()
        loader.shutdown()
    }
}
