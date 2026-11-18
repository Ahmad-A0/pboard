package dev.pboard.ime.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.pboard.ime.rich.RichContent
import dev.pboard.settings.ui.PboardSettingsTheme
import java.io.File
import kotlin.concurrent.thread

/**
 * Gboard's toolbar "Scan text", without a CAMERA permission: the system camera app takes the photo
 * (ACTION_IMAGE_CAPTURE into a file pboard provides) or the user picks one with the system photo picker; pboard reads
 * the text on the device ([Ocr]) and shows it for review and editing; "Insert" hands it to the keyboard, which types it
 * into the field the scan was started from ([ScanResult]). The photo is deleted when the screen closes.
 */
class ScanTextActivity : ComponentActivity() {
    private enum class Phase { CHOOSE, READING, REVIEW, FAILED, NO_PACK }

    private var phase by mutableStateOf(Phase.CHOOSE)
    private var preview by mutableStateOf<Bitmap?>(null)
    private var text by mutableStateOf("")
    private val photo by lazy { File(cacheDir, "scan/photo.jpg").apply { parentFile?.mkdirs() } }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && photo.length() > 0) read(Uri.fromFile(photo)) else phase = Phase.CHOOSE
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) read(uri) else phase = Phase.CHOOSE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PboardSettingsTheme { Screen() } }
        if (savedInstanceState != null) return
        if (!Ocr.available(this)) { phase = Phase.NO_PACK; return }
        // Debug builds: a test image already in the app's cache (adb run-as) skips the camera.
        intent.getStringExtra(EXTRA_TEST_IMAGE)?.takeIf { isDebuggable() }?.let { read(Uri.fromFile(File(cacheDir, it))); return }
        // Like Gboard, the camera opens straight away; cancelling it leaves the choice screen.
        if (!startCamera()) phase = Phase.CHOOSE
    }

    private fun isDebuggable() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun startCamera(): Boolean {
        val uri = runCatching { FileProvider.getUriForFile(this, RichContent.AUTHORITY, photo) }.getOrNull() ?: return false
        return runCatching { takePhoto.launch(uri); true }.getOrDefault(false)
    }

    private fun read(uri: Uri) {
        phase = Phase.READING
        val app = applicationContext
        thread(name = "scan-ocr") {
            val bmp = runCatching { decode(app, uri) }.getOrNull()
            val result = bmp?.let { b -> runCatching { Ocr.recognize(app, b) }.getOrNull() }
            runOnUiThread {
                preview = bmp
                if (result.isNullOrBlank()) phase = Phase.FAILED else { text = result; phase = Phase.REVIEW }
            }
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = Ocr.MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
            if (s < 1f) dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
        }

    private fun insert() {
        ScanResult.offer(intent.getStringExtra(EXTRA_PACKAGE), intent.getIntExtra(EXTRA_FIELD, 0), text)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) photo.delete()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Scan text", style = MaterialTheme.typography.headlineSmall)
                preview?.let { b ->
                    Image(b.asImageBitmap(), contentDescription = "Scanned image", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
                }
                when (phase) {
                    Phase.CHOOSE -> Choose()
                    Phase.READING -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Reading text…")
                        }
                    }
                    Phase.NO_PACK -> {
                        Text("Scan text reads the photo on this device with the pboard scan pack (dev.pboard.pack.scan), " +
                            "which is not installed. Install it to use Scan text; nothing is downloaded by pboard.")
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                    Phase.FAILED -> {
                        Text("No text found. Try again with the text filling the photo, in good light.")
                        Choose()
                    }
                    Phase.REVIEW -> {
                        Text("Check the text, then insert it.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("Recognised text") })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                            OutlinedButton(onClick = { if (!startCamera()) phase = Phase.CHOOSE }) { Text("Retake") }
                            Button(onClick = { insert() }, enabled = text.isNotBlank()) { Text("Insert") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Choose() {
        Text("The text is read on this device; the photo is not kept.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (!startCamera()) phase = Phase.CHOOSE }) { Text("Take photo") }
            OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text("Choose image")
            }
            TextButton(onClick = { finish() }) { Text("Cancel") }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "dev.pboard.extra.SCAN_PACKAGE"
        const val EXTRA_FIELD = "dev.pboard.extra.SCAN_FIELD"
        const val EXTRA_TEST_IMAGE = "test_image"

        fun intent(context: Context, packageName: String?, fieldId: Int) =
            Intent(context, ScanTextActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_PACKAGE, packageName).putExtra(EXTRA_FIELD, fieldId)
    }
}

/**
 * Text from a scan waiting for the keyboard to come back to the field it was scanned for (same app and field id);
 * dropped after [TTL_MS] or when another field opens first.
 */
object ScanResult {
    private var pending: Triple<String?, Int, String>? = null
    private var at = 0L
    private const val TTL_MS = 5 * 60 * 1000L
    private const val SELF = "dev.pboard"

    @Synchronized
    fun offer(pkg: String?, field: Int, text: String) {
        pending = Triple(pkg, field, text)
        at = android.os.SystemClock.elapsedRealtime()
    }

    /** The text to insert into this field, if a scan was made for it. */
    @Synchronized
    fun take(pkg: String?, field: Int): String? {
        val p = pending ?: return null
        if (pkg == SELF && p.first != SELF) return null // the scan screen's own field while it closes
        pending = null
        if (android.os.SystemClock.elapsedRealtime() - at > TTL_MS) return null
        return if (p.first == pkg && p.second == field) p.third else null
    }
}
