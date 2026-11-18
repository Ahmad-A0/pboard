package dev.pboard.ime.contacts

/**
 * "Suggest contact names" (Settings › Text correction, off by default): the pure rules. The names of the user's
 * contacts become name tokens for the engine ([dev.pboard.engine.KeyboardEngine.setContactNames]).
 *
 * Privacy: only display names are read (no numbers, e-mail addresses, photos), only while the setting is on and
 * READ_CONTACTS is granted, and the tokens live in memory only: not stored, not in the learned-data export, not logged.
 */
object ContactNames {
    /** Contacts read at most (the rest are ignored). */
    const val MAX_CONTACTS = 5_000
    /** Name tokens handed to the engine at most. */
    const val MAX_TOKENS = 12_000
    private const val MAX_TOKEN_LENGTH = 32

    /** The feature runs only while the setting is on and the permission is held (a revoked permission turns it off). */
    fun active(settingOn: Boolean, permissionGranted: Boolean): Boolean = settingOn && permissionGranted

    enum class Toggle { ON, OFF, ASK_PERMISSION }

    /** A tap on the settings switch: turning it on asks for the permission first unless it is already granted. */
    fun onToggle(wantOn: Boolean, permissionGranted: Boolean): Toggle = when {
        !wantOn -> Toggle.OFF
        permissionGranted -> Toggle.ON
        else -> Toggle.ASK_PERMISSION
    }

    /** The setting after the permission dialog: on when granted, back off when denied. */
    fun afterPermissionResult(granted: Boolean): Boolean = granted

    /** The setting as the settings page should store and show it: off once the permission is gone. */
    fun reconciled(settingOn: Boolean, permissionGranted: Boolean): Boolean = settingOn && permissionGranted

    /** Distinct name tokens of [displayNames], in order, at most [maxTokens]. */
    fun tokens(displayNames: Iterable<String>, maxTokens: Int = MAX_TOKENS): List<String> {
        val out = LinkedHashSet<String>()
        for (name in displayNames) {
            for (t in tokensOf(name)) { if (out.size >= maxTokens) return out.toList(); out.add(t) }
        }
        return out.toList()
    }

    /**
     * The name tokens of one display name: runs of letters (apostrophes inside kept: "O'Brien"), two letters or more,
     * capitalised as names. A contact saved without a name shows as its e-mail address or number: it has no tokens.
     * In a name written with capitals, lower-case parts are particles ("van", "de") and are left out; a name written
     * all in lower case ("tolu adebayo") or all in capitals ("JOHN SMITH") gets ordinary name capitals.
     */
    fun tokensOf(displayName: String): List<String> {
        if ('@' in displayName) return emptyList()
        val raw = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val t = sb.trim('\'').toString()
            sb.setLength(0)
            if (t.count { it.isLetter() } >= 2 && t.length <= MAX_TOKEN_LENGTH) raw.add(t)
        }
        for (ch in displayName) {
            val c = if (ch == '’') '\'' else ch
            if (c.isLetter() || (c == '\'' && sb.isNotEmpty())) sb.append(c) else flush()
        }
        flush()
        val anyCapital = raw.any { it.first().isUpperCase() }
        val out = ArrayList<String>(raw.size)
        for (t in raw) {
            val upper = t.count { it.isUpperCase() }
            val cased = t.count { it.isUpperCase() || it.isLowerCase() }
            when {
                cased == 0 -> out.add(t) // a script without case
                upper == 0 -> if (!anyCapital) out.add(t.replaceFirstChar { it.uppercaseChar() })
                upper == cased && cased >= 4 -> out.add(t.substring(0, 1) + t.substring(1).lowercase())
                else -> out.add(t)
            }
        }
        return out
    }
}
