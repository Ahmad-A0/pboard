package dev.pboard.ime.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * The opt-in microphone permission for voice typing. A keyboard cannot show the runtime-permission dialog itself, so the
 * mic key opens this transparent screen: a short explanation first, then the system dialog. It is requested only when
 * the user taps the mic key, never at install or start-up; when it was denied for good, the explanation leads to the
 * app's system settings instead. After a grant the keyboard starts listening the next time it is shown.
 */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { finish(); return }
        if (savedInstanceState != null) return // the system dialog is already showing
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val askedBefore = prefs.getBoolean(KEY_ASKED, false)
        // Asked before and the system will not show its dialog again: only the app's settings page can grant it.
        val blocked = askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        AlertDialog.Builder(this)
            .setTitle("Voice typing")
            .setMessage(
                "To type with your voice, pboard needs access to the microphone. Speech is recognised on this device " +
                    "by the voice pack; audio is never stored or sent anywhere (pboard has no internet access)." +
                    if (blocked) "\n\nAllow \"Microphone\" in pboard's app settings." else ""
            )
            .setPositiveButton(if (blocked) "Open settings" else "Continue") { _, _ ->
                if (blocked) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                } else {
                    prefs.edit().putBoolean(KEY_ASKED, true).apply()
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
                }
            }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    companion object {
        private const val PREFS = "voice"
        private const val KEY_ASKED = "mic_asked"
        private const val REQUEST = 1

        fun intent(context: Context): Intent = Intent(context, VoicePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        fun granted(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
