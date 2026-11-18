package dev.pboard.testapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.ContentInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * A messaging-style field that accepts images through the platform receive-content API (what keyboards reach with
 * InputConnection.commitContent). Extra `mime` (default: all images) sets the accepted types; "none" accepts none.
 * Every received item is decoded (proving the keyboard's URI grant works) and listed;
 * `am broadcast -a dev.pboard.testapp.RICH -p dev.pboard.testapp` returns them as JSON.
 */
class RichContentActivity : Activity() {
    private val received = JSONArray()
    private lateinit var field: EditText
    private lateinit var image: ImageView
    private lateinit var status: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            resultData = JSONObject().put("text", field.text.toString()).put("received", received).toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 160, 48, 48) }
        status = TextView(this).apply { text = "nothing received" }
        image = ImageView(this).apply { adjustViewBounds = true; maxHeight = 400 }
        field = EditText(this).apply { hint = "Message" }
        val mime = intent.getStringExtra("mime") ?: "image/*"
        if (mime != "none") {
            field.setOnReceiveContentListener(mime.split(',').toTypedArray()) { _, payload -> accept(payload) }
        }
        root.addView(status); root.addView(image); root.addView(field)
        setContentView(root)
        field.requestFocus()
        registerReceiver(receiver, IntentFilter("dev.pboard.testapp.RICH"), RECEIVER_EXPORTED)
    }

    /** Takes the items that carry a URI (images); plain text goes to the platform's default handling. */
    private fun accept(payload: ContentInfo): ContentInfo? {
        val clip = payload.clip
        if ((0 until clip.itemCount).none { clip.getItemAt(it).uri != null }) return payload
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = contentResolver.getType(uri)
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) image.setImageBitmap(bmp)
            val o = JSONObject().put("uri", uri.toString()).put("type", type).put("bytes", bytes?.size ?: -1)
                .put("w", bmp?.width ?: -1).put("h", bmp?.height ?: -1).put("source", payload.source)
                .put("linkUri", payload.linkUri?.toString() ?: "")
            received.put(o)
            Log.i("RichContent", o.toString())
            status.text = "received ${received.length()}: $type ${bytes?.size ?: -1} bytes ${bmp?.width}x${bmp?.height}"
        }
        return null
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
