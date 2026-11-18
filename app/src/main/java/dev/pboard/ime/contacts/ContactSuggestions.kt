package dev.pboard.ime.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.provider.ContactsContract
import dev.pboard.ime.SerialWorker
import dev.pboard.prefs.PboardPrefs
import dev.pboard.prefs.Prefs

/**
 * Feeds the engine the user's contact names while "Suggest contact names" is on and READ_CONTACTS is granted
 * ([ContactNames]). The keyboard never asks for the permission (the settings switch does); without it, or with the
 * setting off, the engine gets an empty list. Names are read off the main thread when the keyboard starts and when
 * the contacts change, and go straight to the engine: nothing is written to disk or logged here.
 *
 * All methods are called on the main thread; [push] is called there too.
 */
class ContactSuggestions(
    private val context: Context,
    private val worker: SerialWorker,
    private val main: Handler,
    private val push: (List<String>) -> Unit,
) {
    private var on = false
    private var observing = false
    /** Bumped by every state change and reload: a slower, older read never overwrites a newer state. */
    private var generation = 0

    private val reloadRunnable = Runnable { if (on) reload() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reloadRunnable)
            main.postDelayed(reloadRunnable, DEBOUNCE_MS)
        }
    }

    /**
     * Brings the feed in line with the setting and the permission (cheap when nothing changed). [force] pushes the
     * names again even so: for an engine that was just created.
     */
    fun sync(force: Boolean = false) {
        val want = ContactNames.active(PboardPrefs.get(context)[Prefs.suggestContacts], granted(context))
        if (want == on && !force) return
        on = want
        observe(want)
        if (want) reload() else { generation++; push(emptyList()) }
    }

    fun stop() {
        on = false
        generation++
        observe(false)
        main.removeCallbacks(reloadRunnable)
    }

    private fun observe(want: Boolean) {
        if (want == observing) return
        val cr = context.contentResolver
        if (want) observing = runCatching { cr.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer) }.isSuccess
        else { runCatching { cr.unregisterContentObserver(observer) }; observing = false }
    }

    private fun reload() {
        val g = ++generation
        worker.execute {
            val tokens = loadDisplayNames(context)?.let { ContactNames.tokens(it) }
            main.post {
                if (g != generation) return@post
                if (tokens != null) push(tokens)
                else { // not readable (permission just revoked, provider not available yet): off until the next sync
                    on = false
                    observe(false)
                    push(emptyList())
                }
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 3_000L

        fun granted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        /** Display names of up to [ContactNames.MAX_CONTACTS] contacts (the only column read), or null if not readable. */
        private fun loadDisplayNames(context: Context): List<String>? {
            if (!granted(context)) return null
            return try {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null,
                )?.use { c ->
                    val out = ArrayList<String>()
                    while (out.size < ContactNames.MAX_CONTACTS && c.moveToNext()) c.getString(0)?.let { out.add(it) }
                    out
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
