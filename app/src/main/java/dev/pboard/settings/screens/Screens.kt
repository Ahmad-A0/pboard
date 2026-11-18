package dev.pboard.settings.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pboard.R
import dev.pboard.ime.contacts.ContactNames
import dev.pboard.ime.contacts.ContactSuggestions
import dev.pboard.prefs.HandwritingSpeed
import dev.pboard.prefs.Prefs
import dev.pboard.prefs.SmartComposeScope
import dev.pboard.prefs.StrokeWidth
import dev.pboard.settings.Navigator
import dev.pboard.settings.Route
import dev.pboard.settings.ui.BodyText
import dev.pboard.settings.ui.Category
import dev.pboard.settings.ui.ChoiceDialog
import dev.pboard.settings.ui.ConfirmDialog
import dev.pboard.settings.ui.InlineStepSliderPref
import dev.pboard.settings.ui.MainSwitchBar
import dev.pboard.settings.ui.PrefRow
import dev.pboard.settings.ui.SettingsPage
import dev.pboard.settings.ui.StepSliderPref
import dev.pboard.settings.ui.SwitchPref
import dev.pboard.settings.ui.SwitchRow
import dev.pboard.settings.ui.TwoTargetSwitchRow
import dev.pboard.settings.ui.collectAsState
import dev.pboard.settings.ui.rememberPrefs

/**
 * Smart Compose: messages only (default) / everywhere / off. Choosing a scope also clears the old on/off switch
 * (`smart_compose`), which still turns it off when a store from before the scope has it off.
 */
@Composable
private fun SmartComposeRow() {
    val prefs = rememberPrefs()
    val on by Prefs.smartCompose.collectAsState()
    val scope by Prefs.smartComposeScope.collectAsState()
    val current = if (!on) SmartComposeScope.OFF else scope
    val values = SmartComposeScope.entries
    val labels = values.map {
        when (it) {
            SmartComposeScope.MESSAGES -> stringResource(R.string.smart_compose_messages)
            SmartComposeScope.EVERYWHERE -> stringResource(R.string.smart_compose_everywhere)
            SmartComposeScope.OFF -> stringResource(R.string.smart_compose_off)
        }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    PrefRow(stringResource(R.string.smart_compose), summary = labels[values.indexOf(current)], onClick = { open = true })
    if (open) {
        ChoiceDialog(stringResource(R.string.smart_compose), labels, values.indexOf(current), {
            prefs[Prefs.smartComposeScope] = values[it]
            prefs[Prefs.smartCompose] = true
        }) { open = false }
    }
}

@Composable
fun TextCorrectionScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val prefs = rememberPrefs()
    val stripVisible by Prefs.suggestionStripVisible.collectAsState()
    val words by Prefs.showSuggestionStrip.collectAsState()
    val ac by Prefs.autoCorrection.collectAsState()
    // Suggest contact names: turning it on asks for READ_CONTACTS here (the keyboard never asks); a denial leaves it
    // off, and a permission revoked since shows as off when the page opens.
    val contacts by Prefs.suggestContacts.collectAsState()
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs[Prefs.suggestContacts] = ContactNames.afterPermissionResult(granted)
        if (!granted) Toast.makeText(ctx, R.string.suggest_contacts_denied, Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) {
        val on = prefs[Prefs.suggestContacts]
        if (on != ContactNames.reconciled(on, ContactSuggestions.granted(ctx))) prefs[Prefs.suggestContacts] = false
    }
    // Gboard 18.6 "Corrections & suggestions": Automatic corrections · Spelling & grammar · Suggestions.
    // (Double-space period and auto-space after punctuation moved to Preferences › Shortcuts.)
    SettingsPage(stringResource(R.string.header_text_correction), onBack = { nav.pop() }) {
        item { Category(stringResource(R.string.cat_corrections)) }
        item {
            TwoTargetSwitchRow(stringResource(R.string.auto_correction), ac, { prefs[Prefs.autoCorrection] = it }) {
                nav.push(Route.AutoCorrection)
            }
        }
        item { SwitchPref(Prefs.autoCapitalization, stringResource(R.string.auto_cap), stringResource(R.string.auto_cap_summary)) }
        item { Category(stringResource(R.string.cat_spelling)) }
        item {
            PrefRow(stringResource(R.string.spell_checker_link), summary = stringResource(R.string.spell_checker_link_summary),
                onClick = { runCatching { ctx.startActivity(Intent("android.settings.SPELL_CHECKER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
        item { SwitchPref(Prefs.spellCheck, stringResource(R.string.spell_check), stringResource(R.string.spell_check_summary)) }
        item { SwitchPref(Prefs.grammarCheck, stringResource(R.string.grammar_check), stringResource(R.string.grammar_check_summary)) }
        item { Category(stringResource(R.string.cat_suggestions)) }
        item { SmartComposeRow() }
        item { SwitchPref(Prefs.blockOffensiveWords, stringResource(R.string.block_offensive), stringResource(R.string.block_offensive_summary)) }
        item { SwitchPref(Prefs.suggestionStripVisible, stringResource(R.string.suggestion_strip), stringResource(R.string.suggestion_strip_summary)) }
        item {
            SwitchPref(Prefs.showSuggestionStrip, stringResource(R.string.show_suggestion_strip),
                stringResource(R.string.show_suggestion_strip_summary), enabled = stripVisible)
        }
        item {
            SwitchPref(Prefs.nextWordSuggestions, stringResource(R.string.next_word), stringResource(R.string.next_word_summary),
                enabled = stripVisible && words)
        }
        item {
            SwitchRow(stringResource(R.string.suggest_contacts), contacts, { want ->
                when (ContactNames.onToggle(want, ContactSuggestions.granted(ctx))) {
                    ContactNames.Toggle.ON -> prefs[Prefs.suggestContacts] = true
                    ContactNames.Toggle.OFF -> prefs[Prefs.suggestContacts] = false
                    ContactNames.Toggle.ASK_PERMISSION -> askContacts.launch(android.Manifest.permission.READ_CONTACTS)
                }
            }, stringResource(R.string.suggest_contacts_summary))
        }
        // No "Auto-space after suggestions" row: Gboard 18.6 does not show it by default and
        // always adds the space, as pboard does; Prefs.autoSpaceAfterSuggestions is not read by the keyboard.
    }
}

/** Gboard 18.6 Auto-correction sub-page: main switch + Undo category. */
@Composable
fun AutoCorrectionScreen(nav: Navigator) {
    val prefs = rememberPrefs()
    val ac by Prefs.autoCorrection.collectAsState()
    SettingsPage(stringResource(R.string.auto_correction), onBack = { nav.pop() }) {
        item { MainSwitchBar(stringResource(R.string.use_auto_correction), ac) { prefs[Prefs.autoCorrection] = it } }
        item { BodyText(stringResource(R.string.auto_correction_summary)) }
        item { Category(stringResource(R.string.cat_undo)) }
        item {
            SwitchPref(Prefs.undoAutoCorrectOnBackspace, stringResource(R.string.undo_autocorrect),
                stringResource(R.string.undo_autocorrect_summary), enabled = ac)
        }
    }
}

@Composable
fun GlideScreen(nav: Navigator) {
    val glide by Prefs.glideTyping.collectAsState()
    SettingsPage(stringResource(R.string.header_glide_typing), onBack = { nav.pop() }) {
        item { SwitchPref(Prefs.glideTyping, stringResource(R.string.glide_typing), stringResource(R.string.glide_typing_summary)) }
        item { SwitchPref(Prefs.gestureTrail, stringResource(R.string.gesture_trail), enabled = glide) }
        item { SwitchPref(Prefs.gestureDelete, stringResource(R.string.gesture_delete), stringResource(R.string.gesture_delete_summary)) }
        item { SwitchPref(Prefs.gestureCursorControl, stringResource(R.string.gesture_cursor), stringResource(R.string.gesture_cursor_summary)) }
    }
}

@Composable
fun VoiceScreen(nav: Navigator) {
    val prefs = rememberPrefs()
    val on by Prefs.voiceTyping.collectAsState()
    SettingsPage(stringResource(R.string.header_voice_typing), onBack = { nav.pop() }) {
        item { MainSwitchBar(stringResource(R.string.use_voice_typing), on) { prefs[Prefs.voiceTyping] = it } }
        item { BodyText(stringResource(R.string.voice_typing_note)) }
    }
}

@Composable
fun StylusScreen(nav: Navigator) {
    val on by Prefs.stylusHandwriting.collectAsState()
    val showKeyboard by Prefs.stylusShowKeyboard.collectAsState()
    val speed = mapOf(
        HandwritingSpeed.VERY_SLOW to R.string.speed_very_slow, HandwritingSpeed.SLOW to R.string.speed_slow,
        HandwritingSpeed.MID_SLOW to R.string.speed_mid_slow, HandwritingSpeed.NORMAL to R.string.speed_normal,
        HandwritingSpeed.MID_FAST to R.string.speed_mid_fast, HandwritingSpeed.FAST to R.string.speed_fast,
        HandwritingSpeed.VERY_FAST to R.string.speed_very_fast,
    )
    val width = mapOf(
        StrokeWidth.EXTRA_THIN to R.string.width_extra_thin, StrokeWidth.THIN to R.string.width_thin,
        StrokeWidth.MID_THIN to R.string.width_mid_thin, StrokeWidth.NORMAL to R.string.width_normal,
        StrokeWidth.MID_THICK to R.string.width_mid_thick, StrokeWidth.THICK to R.string.width_thick,
        StrokeWidth.EXTRA_THICK to R.string.width_extra_thick,
    )
    val ctx = LocalContext.current
    SettingsPage(stringResource(R.string.header_stylus), onBack = { nav.pop() }) {
        item { SwitchPref(Prefs.stylusHandwriting, stringResource(R.string.stylus_use)) }
        item { BodyText(stringResource(R.string.stylus_intro)) }
        item { InlineStepSliderPref(Prefs.stylusSpeed, stringResource(R.string.stylus_speed), { ctx.getString(speed.getValue(it)) }, enabled = on) }
        item { InlineStepSliderPref(Prefs.stylusStrokeWidth, stringResource(R.string.stylus_width), { ctx.getString(width.getValue(it)) }, enabled = on) }
        item { SwitchPref(Prefs.stylusShowKeyboard, stringResource(R.string.stylus_show_keyboard), stringResource(R.string.stylus_show_keyboard_summary), enabled = on) }
        item { SwitchPref(Prefs.stylusEmojiButton, stringResource(R.string.emojis_on_toolbar), stringResource(R.string.emojis_on_toolbar_summary), enabled = on && !showKeyboard) }
        item { SwitchPref(Prefs.stylusCommitImmediately, stringResource(R.string.stylus_commit), stringResource(R.string.stylus_commit_summary), enabled = on) }
    }
}

@Composable
fun PhysicalKeyboardScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val vk by Prefs.pkShowOnScreenKeyboard.collectAsState()
    SettingsPage(stringResource(R.string.header_physical_keyboard), onBack = { nav.pop() }) {
        item { Category(stringResource(R.string.cat_general)) }
        item { PrefRow(stringResource(R.string.header_languages), onClick = { nav.push(Route.Languages) }) }
        item {
            PrefRow(stringResource(R.string.system_pk_settings), summary = stringResource(R.string.system_pk_settings_summary),
                onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS)) } })
        }
        // No "Emoji with the physical keyboard" row: Gboard 18.6 dropped it; Alt+. opens the emoji panel (HardwareKeyboard).
        item { Category(stringResource(R.string.cat_toolbar)) }
        item { SwitchPref(Prefs.pkShowOnScreenKeyboard, stringResource(R.string.pk_show_vk), stringResource(R.string.pk_show_vk_summary)) }
        item { SwitchPref(Prefs.pkShowToolbar, stringResource(R.string.pk_show_toolbar), stringResource(R.string.pk_show_toolbar_summary), enabled = !vk) }
        // No "Emojis on toolbar" row: Gboard hides it too, and pboard's physical-keyboard tools are a menu that always
        // lists the emoji panel (Prefs.pkEmojiButton is not read).
        item { Category(stringResource(R.string.cat_suggestions)) }
        item { SwitchPref(Prefs.pkSuggestions, stringResource(R.string.pk_suggestions), stringResource(R.string.pk_suggestions_summary)) }
        item { Category(stringResource(R.string.cat_corrections)) }
        item { SwitchPref(Prefs.pkAutoCorrection, stringResource(R.string.auto_correction), stringResource(R.string.auto_correction_summary)) }
        item { SwitchPref(Prefs.pkAutoCapitalization, stringResource(R.string.auto_cap), stringResource(R.string.pk_auto_cap_summary)) }
        // No "Long press for diacritical marks" row until holding a physical key opens an accent popup (unbuilt;
        // Prefs.pkLongPressDiacritics is not read).
    }
}

@Composable
fun ClipboardScreen(nav: Navigator) {
    val ctx = LocalContext.current
    SettingsPage(stringResource(R.string.header_clipboard), onBack = { nav.pop() }) {
        item { SwitchPref(Prefs.clipboardSuggestions, stringResource(R.string.clip_suggestions)) }
        item { SwitchPref(Prefs.clipboardEntities, stringResource(R.string.clip_entities)) }
        item {
            StepSliderPref(Prefs.clipboardRetentionHours, Prefs.CLIPBOARD_RETENTION_STEPS, stringResource(R.string.clip_retention)) {
                ctx.resources.getQuantityString(R.plurals.hours_n, it, it)
            }
        }
    }
}

@Composable
fun EmojiScreen(nav: Navigator) {
    val ctx = LocalContext.current
    SettingsPage(stringResource(R.string.header_emoji), onBack = { nav.pop() }) {
        item { Category(stringResource(R.string.cat_layout)) }
        // No "Emoji fast-access row" / "Emojis in symbols keyboard" rows until the keyboard has a recent-emoji row
        // (unbuilt; Prefs.emojiFastAccessRow and Prefs.emojiInSymbols are not read).
        item {
            StepSliderPref(Prefs.emojiScale, Prefs.SCALE_STEPS, stringResource(R.string.emoji_size),
                summary = stringResource(R.string.emoji_size_summary)) { ctx.getString(R.string.value_percent, it) }
        }
        // No Suggestions category ("Show button to add multiple emojis") until Emojify exists (unbuilt; Prefs.emojifyButton
        // is not read).
        item { Category(stringResource(R.string.suggestions_while_typing)) }
        item { SwitchPref(Prefs.emojiSuggestions, stringResource(R.string.emojis)) }
    }
}

@Composable
fun PrivacyScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val prefs = rememberPrefs()
    var confirm by rememberSaveable { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.header_privacy), onBack = { nav.pop() }) {
        item { Category(stringResource(R.string.cat_learning)) }
        item { SwitchPref(Prefs.personalization, stringResource(R.string.personalize), stringResource(R.string.personalize_summary)) }
        item {
            PrefRow(stringResource(R.string.delete_learned), summary = stringResource(R.string.delete_learned_summary),
                onClick = { confirm = true })
        }
    }
    if (confirm) {
        ConfirmDialog(stringResource(R.string.delete_learned_confirm), stringResource(R.string.delete), onConfirm = {
            prefs[Prefs.learnedDataClearRequest] = System.currentTimeMillis()
            Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
        }) { confirm = false }
    }
}

@Composable
fun AboutScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val version = remember(ctx) {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    var licences by rememberSaveable { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.header_about), onBack = { nav.pop() }) {
        item { PrefRow(stringResource(R.string.privacy_statement), summary = stringResource(R.string.privacy_statement_summary)) }
        item { PrefRow(stringResource(R.string.licenses), summary = stringResource(R.string.licenses_summary), onClick = { licences = !licences }) }
        if (licences) item { BodyText(stringResource(R.string.licenses_text), Modifier.padding(start = 0.dp)) }
        item { PrefRow(stringResource(R.string.current_version), summary = version) }
        item {
            PrefRow(stringResource(R.string.crash_reports), summary = stringResource(R.string.crash_reports_summary),
                onClick = { nav.push(Route.CrashReports) })
        }
    }
}
