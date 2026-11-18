package dev.pboard.engine.testutil

import dev.pboard.engine.decode.EngineConfig
import dev.pboard.engine.decode.LanguageSlot
import dev.pboard.engine.decode.Lexicon
import dev.pboard.engine.decode.LexiconEntry
import dev.pboard.engine.decode.PboardEngine
import dev.pboard.engine.decode.TextRules
import dev.pboard.engine.devlm.CountBigramLM
import kotlin.math.exp

/**
 * Small hand-written vocabularies for deterministic unit tests (no downloaded data needed).
 * Words are listed most frequent first; frequencies follow a Zipf curve.
 */
object Fixtures {
    private val COMMON = """
        the I to you a and it of is that in what we me this he for my on have your do was don't not be are
        I'm it's know can with just but all no so there they like get here go if this right out up now
        about how one would well was him she her there's want will his see were come think can't going
        good yeah back then from them really time okay us did why when as could some say tell who our
        let's an or by need take way been man oh something did look make thank more over too let had
        where only never little very sure much yes love thing these people any day two her mean love
        should into first help hello great nothing anything said life ever after again off those because
        home last before letter better still work long night down call old other many leave stop wait
        find through always give talk mother father new year even happen everything feel place every
        hear made money next same kind while friend true stay house school name keep hand believe
        I'll we're you're he's they're we'll I've I'd won't didn't doesn't isn't wasn't couldn't wouldn't went their dame damp damn
        Tom Mary John English Monday iPhone OK met
        ill its form from fro four for filled filed lets hell shell were wed id
        definitely necessary receive separate tomorrow letters little hello happy apple
        in on at it is if of as an am to so do go no be he me my by up us we
        tea ten tag the then than that this those these there their
        bird birds girl good god dog
    """.trimIndent()

    /** Builds a [CountBigramLM] from a frequency-ordered word list plus a few bigrams. */
    fun lm(words: List<String>, bigrams: List<Pair<String, String>> = DEFAULT_BIGRAMS, boost: Map<String, Double> = emptyMap()): CountBigramLM {
        val b = CountBigramLM.Builder()
        for ((rank, w) in words.withIndex()) b.addUnigram(w, (1.0 / (rank + 10)) * exp(boost[w] ?: 0.0))
        for ((a, c) in bigrams) { val ia = if (a == "<s>") b.bos else b.id(a); b.addBigram(ia, b.id(c), 2) }
        return b.build()
    }

    val DEFAULT_BIGRAMS = listOf(
        "<s>" to "I", "<s>" to "I'm", "<s>" to "I'll", "<s>" to "don't", "<s>" to "it's", "<s>" to "we're",
        "I" to "don't", "I" to "think", "I" to "was", "I" to "love", "the" to "letter", "a" to "letter", "a" to "bird",
        "we" to "were", "they" to "were", "I'm" to "ill", "is" to "ill", "the" to "form", "a" to "form", "of" to "the",
        "wagged" to "its", "hello" to "there", "my" to "iPhone", "new" to "iPhone", "I" to "have",
    )

    fun words(): List<String> = COMMON.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()

    val en: TextRules = TextRules.forLanguageTag("en")

    fun englishLexicon(lm: CountBigramLM = lm(words()), rules: TextRules = en): Lexicon = Lexicon.build(lm.vocabulary(), rules)

    fun englishEngine(config: EngineConfig = EngineConfig()): PboardEngine {
        val lm = lm(words())
        return PboardEngine(lm, Lexicon.build(lm.vocabulary(), en), config).also { it.setGeometry(Layouts.pixel8Qwerty()) }
    }

    /** British/American pairs (GB first). */
    val DIALECT_PAIRS = listOf(
        "colour" to "color", "favourite" to "favorite", "organisation" to "organization", "travelling" to "traveling",
        "realise" to "realize", "centre" to "center", "neighbour" to "neighbor", "cancelled" to "canceled",
        "catalogue" to "catalog", "defence" to "defense", "analyse" to "analyze", "grey" to "gray",
    )

    /**
     * Dialect-flavoured engine: both spellings are known, the other dialect's ranked ~4 nats lower
     * (how the real per-dialect lexicons mark them).
     */
    fun dialectEngine(tag: String): PboardEngine {
        val british = tag.equals("en-GB", ignoreCase = true)
        val base = words() + DIALECT_PAIRS.flatMap { listOf(it.first, it.second) } + listOf("my", "favourite", "colour", "is")
        val boost = HashMap<String, Double>()
        for ((gb, us) in DIALECT_PAIRS) {
            boost[if (british) gb else us] = 3.0
            boost[if (british) us else gb] = -1.0
        }
        val bigrams = DEFAULT_BIGRAMS + listOf("my" to "favourite", "my" to "favorite", "the" to "colour", "the" to "color", "the" to "organisation", "the" to "organization")
        val lm = lm(base.distinct(), bigrams, boost)
        val rules = TextRules.forLanguageTag(tag)
        return PboardEngine(listOf(LanguageSlot(tag, lm, Lexicon.build(lm.vocabulary(), rules))), EngineConfig()).also { it.setGeometry(Layouts.pixel8Qwerty()) }
    }

    /** A tiny French vocabulary for multilingual and layout tests. */
    val FRENCH = listOf("je", "de", "la", "le", "et", "à", "déjà", "être", "très", "bonjour", "merci", "français", "l'homme", "c'est", "voilà", "où", "ça", "garçon", "élève")

    fun frenchLexicon(): Pair<CountBigramLM, Lexicon> {
        val lm = lm(FRENCH, emptyList())
        return lm to Lexicon.build(lm.vocabulary(), TextRules.forLanguageTag("fr"))
    }

    fun entries(vararg words: String): List<LexiconEntry> = words.mapIndexed { i, w -> LexiconEntry(w, -1f - i) }
}
