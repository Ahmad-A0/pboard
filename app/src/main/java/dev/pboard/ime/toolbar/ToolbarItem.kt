package dev.pboard.ime.toolbar

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pboard.R

/**
 * Everything that can sit on the keyboard toolbar ("bar") or in its "more" panel.
 *
 * Data only — the keyboard UI renders it. [symbol] is the Material Symbols name (for anyone drawing with the
 * Material Symbols font/vectors), [icon] the equivalent Material icon as a Compose [ImageVector] (Apache-2.0,
 * `material-icons-extended`). [activeIcon] is shown while the item's mode is on (e.g. floating).
 *
 * Gboard items deliberately absent: GIF search, Search, Share, Proofread — they need Google servers.
 */
enum class ToolbarItem(
    val id: String,
    @StringRes val label: Int,
    val symbol: String,
    private val iconProvider: () -> ImageVector,
    val availability: Availability = Availability.ALWAYS,
    private val activeIconProvider: (() -> ImageVector)? = null,
) {
    CLIPBOARD("clipboard", R.string.toolbar_clipboard, "assignment", { Icons.Outlined.Assignment }),
    SETTINGS("settings", R.string.toolbar_settings, "settings", { Icons.Filled.Settings }),
    THEME("theme", R.string.toolbar_theme, "palette", { Icons.Filled.Palette }),
    ONE_HANDED("one_handed", R.string.toolbar_one_handed, "back_hand", { Icons.Outlined.BackHand }),
    TEXT_EDITING("text_editing", R.string.toolbar_text_editing, "settings_ethernet", { Icons.Outlined.SettingsEthernet }),
    FLOATING(
        "floating", R.string.toolbar_floating, "keyboard", { Icons.Outlined.Keyboard },
        activeIconProvider = { Icons.Outlined.KeyboardHide },
    ),
    RESIZE("resize", R.string.toolbar_resize, "crop_free", { Icons.Outlined.CropFree }),
    EMOJI("emoji", R.string.toolbar_emoji, "sentiment_satisfied", { Icons.Outlined.SentimentSatisfied }),
    UNDO("undo", R.string.toolbar_undo, "undo", { Icons.AutoMirrored.Outlined.Undo }),
    SPLIT("split", R.string.toolbar_split, "vertical_split", { Icons.Outlined.VerticalSplit }, Availability.LARGE_SCREEN),

    /** Local, user-imported stickers only (no sticker downloads); dev.pboard.ime.rich. */
    STICKER("sticker", R.string.toolbar_sticker, "sticky_note_2", { Icons.AutoMirrored.Outlined.StickyNote2 }),

    /** On-device translation (dev.pboard.ime.translate); without an installed language pack it offers to add one. */
    TRANSLATE("translate", R.string.toolbar_translate, "translate", { Icons.Outlined.Translate }),

    /** The system camera app or photo picker plus on-device OCR (dev.pboard.ime.scan); no CAMERA permission. */
    SCAN_TEXT("scan_text", R.string.toolbar_scan_text, "document_scanner", { Icons.Outlined.DocumentScanner }),

    /** Voice typing; the default occupant of the right-hand "power key" slot. Needs a local speech model. */
    VOICE("voice", R.string.toolbar_voice, "mic", { Icons.Filled.Mic }),
    ;

    val icon: ImageVector get() = iconProvider()
    val activeIcon: ImageVector get() = (activeIconProvider ?: iconProvider)()

    enum class Availability {
        /** Offered everywhere. */
        ALWAYS,

        /** Only on tablets / unfolded foldables (smallest width ≥ 600 dp). */
        LARGE_SCREEN,

        /** In the catalogue for completeness but not offered until its on-device implementation exists. */
        PLANNED,
    }

    /** Whether the keyboard shows this item: on this screen size, and (the mic) while voice typing is on. */
    fun isOffered(largeScreen: Boolean, voiceTyping: Boolean = true) = when (availability) {
        Availability.ALWAYS -> this != VOICE || voiceTyping
        Availability.LARGE_SCREEN -> largeScreen
        Availability.PLANNED -> false
    }

    companion object {
        fun byId(id: String): ToolbarItem? = entries.firstOrNull { it.id == id }

        /**
         * Default order. The first [DEFAULT_BAR_ITEMS] are on the bar; the rest fill the "more" panel.
         * Gboard's order is search · sticker · GIF · clipboard · settings · theme · one-handed · text editing · …;
         * pboard drops the server-backed items (search, GIF; stickers are local-only and not offered yet) and keeps
         * the rest of Gboard's order.
         */
        val DEFAULT_ORDER: List<ToolbarItem> = listOf(
            CLIPBOARD, SETTINGS, THEME, ONE_HANDED, TEXT_EDITING,
            FLOATING, RESIZE, EMOJI, UNDO, SPLIT, STICKER, TRANSLATE, SCAN_TEXT,
        )

        /**
         * Items on the bar on a fresh install. Gboard 18.6 caps a new phone user's bar at 3 (sticker · GIF ·
         * clipboard) until the user customises it; pboard shows clipboard · settings · theme.
         */
        const val DEFAULT_BAR_ITEMS = 3

        /** Most items the bar holds in portrait on a phone (Gboard's theme limit); the user can drag up to this. */
        const val BAR_CAPACITY = 5

        @Deprecated(
            "Ambiguous since Gboard 18.6: use BAR_CAPACITY (most items the bar holds) or DEFAULT_BAR_ITEMS (fresh install).",
            ReplaceWith("BAR_CAPACITY"),
        )
        const val DEFAULT_PINNED_COUNT = BAR_CAPACITY

        /** Upper bound for pinned items (wide screens); the renderer may show fewer if they do not fit. */
        const val MAX_PINNED = 8

        val DEFAULT_POWER_KEY = VOICE
    }
}
