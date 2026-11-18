package dev.pboard.ime.rich

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread

/**
 * "Add stickers" from the keyboard's sticker panel: opens the system photo picker (no storage permission; the user
 * picks exactly what pboard may read), copies the picked images into the app-private [StickerStore] and closes.
 * No UI of its own (translucent).
 */
class StickerImportActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isEmpty()) { finish(); return@registerForActivityResult }
        val app = applicationContext
        thread(name = "sticker-import") {
            val n = StickerStore(app).import(uris)
            runOnUiThread {
                Toast.makeText(app, if (n > 0) "Added $n sticker${if (n == 1) "" else "s"}" else "Couldn't add those images",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    companion object {
        const val MAX_PICK = 30
    }
}
