package dev.pboard.testapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * A login form for the inline-autofill tests: username (e-mail), password and one-time-code fields with autofill
 * hints, plus a plain note field (no autofill). `am broadcast -a dev.pboard.testapp.GET -p dev.pboard.testapp`
 * returns the field contents as JSON in the result data.
 */
class LoginActivity : Activity() {
    private lateinit var fields: Map<String, EditText>

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val o = JSONObject()
            for ((k, v) in fields) o.put(k, v.text.toString())
            o.put("focus", fields.entries.firstOrNull { it.value.hasFocus() }?.key ?: "")
            resultData = o.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 160, 48, 48) }
        fun field(name: String, hint: String, type: Int, autofill: String?): EditText {
            root.addView(TextView(this).apply { text = hint })
            return EditText(this).apply {
                tag = name
                this.hint = hint
                inputType = type
                if (autofill != null) setAutofillHints(autofill) else importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                root.addView(this)
            }
        }
        fields = linkedMapOf(
            "user" to field("user", "Email or username", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, View.AUTOFILL_HINT_USERNAME),
            "password" to field("password", "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, View.AUTOFILL_HINT_PASSWORD),
            "otp" to field("otp", "One-time code", InputType.TYPE_CLASS_NUMBER, "smsOTPCode"),
            "note" to field("note", "Note (no autofill)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, null),
        )
        setContentView(root)
        // Extra `bg` (hex RGB): a loud window background, to see what shows through transparent IME pixels.
        intent.getStringExtra("bg")?.let { window.decorView.setBackgroundColor(0xFF000000.toInt() or it.toInt(16)) }
        intent.getStringExtra("focus")?.let { fields[it]?.requestFocus() }
        registerReceiver(receiver, IntentFilter("dev.pboard.testapp.GET"), RECEIVER_EXPORTED)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("focus")?.let { fields[it]?.requestFocus() }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
