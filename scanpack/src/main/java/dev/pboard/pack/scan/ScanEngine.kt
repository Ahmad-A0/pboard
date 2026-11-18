package dev.pboard.pack.scan

import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract

/**
 * The OCR entry point pboard calls (by reflection, through its own class loader for this APK, so only framework types
 * cross the boundary). Tesseract 5 LSTM with the model directory pboard prepared from this pack's assets.
 */
object ScanEngine {
    /** Bumped when [recognize]'s contract changes; pboard checks it. */
    const val API = 1

    /**
     * Recognises the text in an 8-bit grayscale image ([gray], [width] x [height], one byte per pixel, rows packed) with
     * the `<lang>.traineddata` in [tessdataDir]; lines separated by '\n'.
     */
    @JvmStatic
    fun recognize(gray: ByteArray, width: Int, height: Int, tessdataDir: String, lang: String): String {
        val api = TessBaseAPI()
        try {
            check(api.Init(tessdataDir, lang) == 0) { "OCR model did not load" }
            api.SetPageSegMode(tesseract.PSM_AUTO)
            api.SetImage(gray, width, height, 1, width)
            api.SetSourceResolution(300)
            val out = api.GetUTF8Text()
            val text = out?.string.orEmpty()
            out?.deallocate()
            return text
        } finally {
            api.End()
            api.close()
        }
    }
}
