package dev.pboard.ime.textintel

import android.content.Context
import dev.pboard.engine.lm.LanguagePack
import dev.pboard.engine.lm.NeuralRescoringLm
import dev.pboard.engine.lm.NgramLanguageModel
import dev.pboard.engine.textintel.EnglishMorphology
import dev.pboard.engine.textintel.GrammarChecker
import dev.pboard.engine.textintel.InlineCompleter
import dev.pboard.ime.engine.Engines
import java.util.Locale

/**
 * The text-intelligence models for one typing language: the grammar checker and the inline completer, built over
 * the same memory-mapped language pack and LSTM as the decoder (no second copy), each with its own LSTM state cache
 * so it never evicts the decoder's. Everything runs on the device. English only.
 *
 * Grammar uses the static model (pack + dialect overlay, no learned data), so a mistake the user keeps making is
 * still flagged; inline completion and the spelling check use the learned model too (the user's own words and
 * phrases).
 */
class TextIntelModels private constructor(
    val grammar: GrammarChecker,
    val completer: InlineCompleter,
    private val learned: NgramLanguageModel,
    val tag: String,
) {
    /** The vocabulary (pack + words learned on this device) has [word], in any capitalisation. */
    fun knows(word: String): Boolean = learned.isKnown(word) || learned.isKnown(word.lowercase())

    companion object {
        const val MORPH_ASSET = "textintel/en_morph.txt"

        @Volatile private var morph: EnglishMorphology? = null

        private fun morphology(context: Context): EnglishMorphology = morph ?: synchronized(this) {
            morph ?: context.assets.open(MORPH_ASSET).use { EnglishMorphology.read(it) }.also { morph = it }
        }

        /** Builds the models for [locale] (maps/loads assets on first use: call off the main thread). */
        fun load(context: Context, locale: Locale): TextIntelModels? {
            if (locale.language != "en") return null
            val spec = Engines.spec(context, locale)
            val pack = spec.lexicon as? LanguagePack ?: return null
            val learned = pack.model
            val static = NgramLanguageModel(learned.pack, learned.overlay)
            val nn = (spec.lm as? NeuralRescoringLm)?.nn
            val grammarLm = if (nn != null) NeuralRescoringLm(static, nn, cacheSize = 64) else static
            val composeLm = if (nn != null) NeuralRescoringLm(learned, nn, cacheSize = 24) else learned
            return TextIntelModels(
                GrammarChecker(grammarLm, static, morphology(context)),
                InlineCompleter(composeLm, learned),
                learned,
                spec.tag,
            )
        }
    }
}
