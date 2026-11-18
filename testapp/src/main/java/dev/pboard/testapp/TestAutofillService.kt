package dev.pboard.testapp

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * A password-manager stand-in: for username / e-mail, password and one-time-code fields it offers fixed datasets as
 * inline suggestions (styled with whatever the IME asked for), plus one pinned icon chip, as Google Password Manager
 * and Bitwarden do. Behaviour knobs (set with [ConfigReceiver]): number of datasets, response delay, pinned chip.
 */
class TestAutofillService : AutofillService() {
    private enum class Kind { USER, PASSWORD, OTP }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.last().structure
        val fields = LinkedHashMap<AutofillId, Kind>()
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, fields)
        val inline = request.inlineSuggestionsRequest
        Log.i(TAG, "fill request: ${fields.values} inline=${inline != null} specs=${inline?.inlinePresentationSpecs?.size} max=${inline?.maxSuggestionCount}")
        inline?.inlinePresentationSpecs?.firstOrNull()?.let { s -> Log.i(TAG, "spec min=${s.minSize} max=${s.maxSize} style=${s.style}") }
        if (fields.isEmpty()) { callback.onSuccess(null); return }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val count = prefs.getInt("count", 3)
        val delay = prefs.getLong("delay", 0L)
        val pinned = prefs.getBoolean("pinned", true)
        val response = FillResponse.Builder()
        val specs = inline?.inlinePresentationSpecs.orEmpty()
        val max = inline?.maxSuggestionCount ?: Int.MAX_VALUE
        val accounts = ACCOUNTS.take(count)
        var added = 0
        val focusedKind = fields[request.fillContexts.last().focusedId] ?: fields.values.first()
        for ((i, acc) in accounts.withIndex()) {
            if (added >= max - (if (pinned) 1 else 0)) break
            val ds = Dataset.Builder()
            val (title, subtitle) = when (focusedKind) {
                Kind.OTP -> acc.otp to "From Messages"
                Kind.PASSWORD -> acc.user to "••••••••"
                Kind.USER -> acc.user to "Test vault"
            }
            for ((id, kind) in fields) {
                val value = when (kind) { Kind.USER -> acc.user; Kind.PASSWORD -> acc.password; Kind.OTP -> acc.otp }
                val menu = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, title) }
                val spec = specs.getOrNull(minOf(i, specs.size - 1))
                val ip = spec?.let { inlineChip(it, title, subtitle, pinned = false) }
                @Suppress("DEPRECATION")
                if (ip != null) ds.setValue(id, AutofillValue.forText(value), menu, ip) else ds.setValue(id, AutofillValue.forText(value), menu)
            }
            response.addDataset(ds.build())
            added++
        }
        if (pinned && specs.isNotEmpty()) {
            // The pinned "open my vault" icon chip (always shown at the strip's end by keyboards).
            val ds = Dataset.Builder()
            val id = fields.keys.first()
            val ip = inlineChip(specs.last(), null, null, pinned = true)
            val menu = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, "Open vault") }
            @Suppress("DEPRECATION")
            if (ip != null) ds.setValue(id, null, menu, ip) else ds.setValue(id, null, menu)
            ds.setAuthentication(PendingIntent.getActivity(this, 1, Intent(this, LoginActivity::class.java), PendingIntent.FLAG_IMMUTABLE).intentSender)
            response.addDataset(ds.build())
        }
        val r = runCatching { response.build() }.getOrNull()
        if (delay > 0) Handler(Looper.getMainLooper()).postDelayed({ callback.onSuccess(r) }, delay) else callback.onSuccess(r)
    }

    private fun inlineChip(spec: InlinePresentationSpec, title: String?, subtitle: String?, pinned: Boolean): InlinePresentation? {
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val attribution = PendingIntent.getActivity(this, 0, Intent(this, LoginActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = InlineSuggestionUi.newContentBuilder(attribution)
        if (title != null) b.setTitle(title)
        if (subtitle != null) b.setSubtitle(subtitle)
        b.setStartIcon(Icon.createWithResource(this, if (pinned) android.R.drawable.ic_lock_lock else android.R.drawable.ic_menu_myplaces))
        b.setContentDescription(if (pinned) "Test vault" else title ?: "")
        return InlinePresentation(b.build().slice, spec, pinned)
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableMap<AutofillId, Kind>) {
        val id = node.autofillId
        if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val hints = node.autofillHints.orEmpty().map { it.lowercase() }
            val name = (node.idEntry ?: "").lowercase()
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            val kind = when {
                hints.any { "otp" in it || "onetimecode" in it } || "otp" in name -> Kind.OTP
                hints.any { "password" in it } || "password" in name ||
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> Kind.PASSWORD
                hints.any { "user" in it || "email" in it } || "user" in name || "email" in name -> Kind.USER
                else -> null
            }
            if (kind != null) out[id] = kind
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    private class Account(val user: String, val password: String, val otp: String)

    companion object {
        const val TAG = "TestAutofill"
        const val PREFS = "config"
        private val ACCOUNTS = listOf(
            Account("alice@example.com", "alice-pass", "482913"),
            Account("bob.smith@example.org", "bob-pass", "105577"),
            Account("carol", "carol-pass", "730146"),
            Account("dave.jones.long.address@example.net", "dave-pass", "661204"),
            Account("erin", "erin-pass", "390817"),
            Account("frank", "frank-pass", "222333"),
            Account("grace", "grace-pass", "444555"),
            Account("heidi", "heidi-pass", "666777"),
            Account("ivan", "ivan-pass", "888999"),
            Account("judy", "judy-pass", "121212"),
        )
    }
}

/**
 * Puts text on the clipboard like a copying app would (clipboard chip tests, ED-06/ED-07):
 * am broadcast -n dev.pboard.testapp/.ClipReceiver --es text "Call me on 07700 900123" [--ez sensitive true]
 */
class ClipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val clip = android.content.ClipData.newPlainText("test", text)
        if (intent.getBooleanExtra("sensitive", false)) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        resultData = "ok"
    }
}

/** am broadcast -n dev.pboard.testapp/.ConfigReceiver --ei count 3 --el delay 0 --ez pinned true */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val e = context.getSharedPreferences(TestAutofillService.PREFS, Context.MODE_PRIVATE).edit()
        if (intent.hasExtra("count")) e.putInt("count", intent.getIntExtra("count", 3))
        if (intent.hasExtra("delay")) e.putLong("delay", intent.getLongExtra("delay", 0L))
        if (intent.hasExtra("pinned")) e.putBoolean("pinned", intent.getBooleanExtra("pinned", true))
        e.apply()
        resultData = "ok"
    }
}
