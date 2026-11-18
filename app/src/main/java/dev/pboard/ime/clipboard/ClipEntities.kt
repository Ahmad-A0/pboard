package dev.pboard.ime.clipboard

import com.google.i18n.phonenumbers.PhoneNumberUtil

/** What a clipboard entity chip holds; [ClipEntities] finds them in recently copied text (Gboard 18.6 ED-07). */
enum class EntityType { OTP, LINK, EMAIL, PHONE, ADDRESS }

data class ClipEntity(val type: EntityType, val text: String, val start: Int, val end: Int)

/**
 * On-device entity detection for clipboard chips: one-time codes, links, e-mail addresses, phone numbers and postal
 * addresses inside a copied text, so the strip can offer just the part the user wants (Gboard uses a downloaded ML
 * text classifier for this; pboard uses regexes and libphonenumber (Apache-2.0), nothing leaves the device).
 *
 * Rules (precision over recall: a wrong chip is worse than none):
 * * E-mail and links (http(s)/www./bare domain with a common TLD) by pattern; trailing punctuation is not part of them.
 * * One-time codes: 4-8 digits (optionally split once by a space or dash, or with a short letter prefix like
 *   `G-`) within a few words of a code keyword ("code", "OTP", "PIN", "passcode", "verification", ...).
 * * Phone numbers: libphonenumber's matcher for the given regions in order (device, keyboard and system locales, then
 *   the common English-speaking ones), plus international numbers; a number must have at
 *   least 7 digits and is not a code or part of a link.
 * * Addresses: a house number, up to four capitalised words and a street type (US/UK/IE/AU style), optionally
 *   followed by town, state/county and ZIP or postcode; or a UK postcode on its own.
 * * Results are in text order, without duplicates and without an entity that is the whole clip (the clip chip
 *   already pastes that).
 */
object ClipEntities {
    /** Most entity chips offered for one clip. */
    const val MAX = 3

    private val EMAIL = Regex("""(?<![\w.%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,24}(?![\w-])""")
    private const val TLDS = "com|org|net|edu|gov|io|co|uk|de|fr|es|it|nl|eu|info|biz|app|dev|me|ly|tv|us|ca|au|in|jp|ie|be|ch|at|se|no|dk|fi|pl|pt|br|ru|cn|ai|gg|xyz"
    private val LINK = Regex(
        """(?i)(?<![\w@./-])(?:(?:https?://|www\.)[^\s<>"']+|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:$TLDS)(?::\d{2,5})?(?:/[^\s<>"']*)?)""",
    )
    private val CODE_KEYWORD = Regex(
        """(?i)\b(?:codes?|otp|pin|passcode|pass code|password|verification|verify|security|one[- ]time|login|log-in|sign[- ]in|2fa|auth\w*|token|kod|código|codice|code de)\b""",
    )
    private val CODE = Regex("""(?<![\w+.,/:-])(?:[A-Z]{1,3}-)?(\d{3,4}[ -]\d{3,4}|\d{4,8})(?![\w.,/:-]\d|\w|[ -]\d)""")
    private const val STREET_TYPES = "Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Court|Ct|Way|Place|Pl|Terrace|Ter|Close|Crescent|Cres|Square|Sq|Parkway|Pkwy|Highway|Hwy|Gardens|Gdns|Grove|Hill|Mews|Row|Walk|Circle|Cir|Trail|Parade|Esplanade"
    private const val UK_POSTCODE = """[A-Z]{1,2}\d[A-Z\d]?\s?\d[ABD-HJLNP-UW-Z]{2}"""
    private val ADDRESS = Regex(
        """\b\d{1,5}[A-Za-z]?,?\s+(?:[A-Z][\w'’.-]*\s+){1,4}(?:$STREET_TYPES)\b\.?""" +
            """(?:,?\s+[A-Z][a-z][\w'’.-]*(?:\s[A-Z][a-z][\w'’.-]*){0,2})?""" + // town (capitalised words, not a postcode)
            """(?:,?\s+(?:[A-Z]{2}\s+\d{5}(?:-\d{4})?|$UK_POSTCODE|[A-Z][a-z]+\s+\d{4,5}))?""", // state + ZIP, postcode
    )
    private val POSTCODE_ONLY = Regex("""\b$UK_POSTCODE\b""")

    private val phoneUtil by lazy { PhoneNumberUtil.getInstance() }

    fun extract(text: String, region: String = "US"): List<ClipEntity> = extract(text, listOf(region))

    fun extract(text: String, regions: List<String>): List<ClipEntity> {
        if (text.isBlank() || text.length > MAX_TEXT) return emptyList()
        val found = ArrayList<ClipEntity>()
        fun overlaps(s: Int, e: Int) = found.any { s < it.end && e > it.start }
        fun add(type: EntityType, s: Int, e: Int) {
            var end = e
            while (end > s && text[end - 1] in TRAILING) end--
            if (end - s < 3 || overlaps(s, end)) return
            found += ClipEntity(type, text.substring(s, end), s, end)
        }
        for (m in EMAIL.findAll(text)) add(EntityType.EMAIL, m.range.first, m.range.last + 1)
        for (m in LINK.findAll(text)) add(EntityType.LINK, m.range.first, m.range.last + 1)
        // Phone numbers before codes: a code is never a piece of a longer number.
        findPhones(text, regions) { s, e -> add(EntityType.PHONE, s, e) }
        for (m in CODE.findAll(text)) {
            val g = m.groups[1] ?: continue
            if (!nearKeyword(text, m.range.first, m.range.last + 1)) continue
            add(EntityType.OTP, g.range.first, g.range.last + 1)
        }
        for (m in ADDRESS.findAll(text)) add(EntityType.ADDRESS, m.range.first, m.range.last + 1)
        if (found.none { it.type == EntityType.ADDRESS }) {
            for (m in POSTCODE_ONLY.findAll(text)) if (looksLikePostcodeContext(text, m.range.first)) {
                add(EntityType.ADDRESS, m.range.first, m.range.last + 1)
            }
        }
        val whole = text.trim()
        return found.asSequence()
            .sortedBy { it.start }
            .filter { it.text != whole }
            .distinctBy { it.text }
            .take(MAX)
            .toList()
    }

    /**
     * What the whole clip is, when it is one entity (Gboard shows such a clip as a single chip with the entity's icon):
     * an e-mail address, a link, a phone number or a bare 4-8 digit code; null for anything else.
     */
    fun classifyWhole(text: String, region: String = "US"): EntityType? = classifyWhole(text, listOf(region))

    fun classifyWhole(text: String, regions: List<String>): EntityType? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 200 || t.any { it == '\n' }) return null
        if (EMAIL.matchEntire(t) != null) return EntityType.EMAIL
        if (LINK.matchEntire(t) != null) return EntityType.LINK
        if (BARE_CODE.matches(t)) return EntityType.OTP
        var phone = false
        findPhones(t, regions) { s, e -> if (s == 0 && e == t.length) phone = true }
        return if (phone) EntityType.PHONE else null
    }

    private val BARE_CODE = Regex("""\d{4,8}|\d{3,4}[ -]\d{3,4}""")

    private fun findPhones(text: String, regionList: List<String>, add: (Int, Int) -> Unit) {
        val regions = regionList.map { it.uppercase() }.filter { it.length == 2 }.ifEmpty { listOf("US") }.distinct()
        for ((ri, r) in regions.withIndex()) {
            val matches = runCatching {
                phoneUtil.findNumbers(text, r, PhoneNumberUtil.Leniency.POSSIBLE, Long.MAX_VALUE)
            }.getOrNull() ?: continue
            for (m in matches) {
                val raw = m.rawString()
                val digits = raw.count { it.isDigit() }
                if (digits < 7) continue
                // A bare run of digits is a phone number only when valid in the first (the device's) region.
                val formatted = raw.any { it == ' ' || it == '-' || it == '(' || it == '+' || it == '.' }
                if (!formatted && (ri > 0 || !phoneUtil.isValidNumber(m.number()))) continue
                add(m.start(), m.end())
            }
        }
    }

    /** A code keyword within ~40 characters before or after the code ("G-123456 is your Google verification code"). */
    private fun nearKeyword(text: String, s: Int, e: Int): Boolean {
        val before = text.substring((s - 40).coerceAtLeast(0), s)
        val after = text.substring(e, (e + 40).coerceAtMost(text.length))
        return CODE_KEYWORD.containsMatchIn(before) || CODE_KEYWORD.containsMatchIn(after)
    }

    /** A UK postcode on its own counts after a comma or line break or at the start of a line (an address's last line). */
    private fun looksLikePostcodeContext(text: String, s: Int): Boolean {
        var i = s - 1
        while (i >= 0 && text[i] == ' ') i--
        return i < 0 || text[i] == ',' || text[i] == '\n'
    }

    private const val TRAILING = ".,;:!?)]}'\"”’"
    /** Longer clips are not scanned (cost; Gboard also limits the text it classifies). */
    private const val MAX_TEXT = 5000
}
