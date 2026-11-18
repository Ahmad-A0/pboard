package dev.pboard.ime.textintel

import android.os.Build
import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Prefs
import java.util.Locale

/**
 * pboard as the system spell checker (Settings › System › Languages › Spell checker), which is how grammar marks reach
 * other apps: the app's text view sends its sentences here and Android itself draws the underline (grammar: blue,
 * spelling: red) - the same split as Gboard 18.6, whose grammar check also lives in its spell checker service.
 *
 *  - Grammar ([Prefs.grammarCheck]): [dev.pboard.engine.textintel.GrammarChecker] over each sentence, reported as
 *    RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR with the fix as the single suggestion, on the range Gboard would mark
 *    ("a apple" -> "an apple").
 *  - Spelling ([Prefs.spellCheck]): words the language model does not know (nor the personal dictionary, nor words
 *    the user taught it) are reported as typos. Corrections come from the keyboard's strip when the cursor is put in
 *    the word, as with Gboard, so the span carries none.
 * Both are reported with RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS: like Gboard, the fix is offered by the keyboard's
 * chip, not by the text view's popup. Everything runs on the device; nothing is learned from what is checked.
 */
class PboardSpellCheckerService : SpellCheckerService() {
    override fun createSession(): Session = PboardSession()

    private inner class PboardSession : Session() {
        private var models: TextIntelModels? = null
        private var loc: Locale = Locale.UK

        override fun onCreate() {
            // the session's locale ("en_GB", "en-US"; empty = the system's)
            val tag = locale
            loc = if (tag.isNullOrEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag.replace('_', '-'))
            val old = TextIntel.backgroundPriority()
            try { models = TextIntel.models(this@PboardSpellCheckerService, loc) } finally { TextIntel.restorePriority(old) }
        }

        private val prefs get() = PboardPrefs.get(this@PboardSpellCheckerService)

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo {
            val word = textInfo.text
            val m = models
            val typo = m != null && prefs[Prefs.spellCheck] && TextIntel.isTypo(this@PboardSpellCheckerService, m, word, loc)
            val attrs = if (typo) SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or dontShowUi() else SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY
            return SuggestionsInfo(attrs, emptyArray(), textInfo.cookie, textInfo.sequence)
        }

        /**
         * Called on one of the service's binder threads (never the keyboard's UI or decoding thread), at background
         * priority for its duration: checking yields the CPU to typing. Each request is independent; the text view
         * sends a new one when the text changes and drops answers to old ones.
         */
        override fun onGetSentenceSuggestionsMultiple(textInfos: Array<TextInfo>, suggestionsLimit: Int): Array<SentenceSuggestionsInfo> {
            val old = TextIntel.backgroundPriority()
            try {
                return check(textInfos)
            } finally {
                TextIntel.restorePriority(old)
            }
        }

        private fun check(textInfos: Array<TextInfo>): Array<SentenceSuggestionsInfo> {
            val m = models ?: return Array(textInfos.size) { SentenceSuggestionsInfo(emptyArray(), IntArray(0), IntArray(0)) }
            val spell = prefs[Prefs.spellCheck]
            val grammar = prefs[Prefs.grammarCheck] && Build.VERSION.SDK_INT >= 31
            return Array(textInfos.size) { k ->
                val ti = textInfos[k]
                val marks = TextIntel.marks(this@PboardSpellCheckerService, m, ti.text, loc, spell, grammar)
                SentenceSuggestionsInfo(
                    Array(marks.size) { i ->
                        val mk = marks[i]
                        val attrs = (if (mk.grammar) GRAMMAR else SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) or dontShowUi()
                        SuggestionsInfo(attrs, if (mk.suggestion != null) arrayOf(mk.suggestion) else emptyArray(), ti.cookie, ti.sequence)
                    },
                    IntArray(marks.size) { marks[it].start },
                    IntArray(marks.size) { marks[it].end - marks[it].start },
                )
            }
        }
    }

    companion object {
        /** SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR (API 31). */
        private const val GRAMMAR = 0x0008
        private fun dontShowUi() = if (Build.VERSION.SDK_INT >= 31) SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS else 0
    }
}
