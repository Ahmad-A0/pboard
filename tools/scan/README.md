# Scan text: OCR engine choice

pboard's toolbar "Scan text" (dev.pboard.ime.scan) reads text from a photo on the device. Gboard sends the photo to
Google Lens; pboard needs a permissively licensed on-device engine and no CAMERA permission (the system camera app
takes the photo through ACTION_IMAGE_CAPTURE, or the user picks one with the system photo picker).

## Evaluation (2026-11-08, `tools/scan/eval_ocr.py 40`)

40 synthetic phone photos of printed English text (3-6 lines from tools/bench/sentences plus addresses, numbers,
e-mail; 5 fonts; paper on a table, perspective, rotation, uneven light, blur, noise, JPEG 60-85, 1600 px), 9,542
characters. CER = edit distance over the whole text (reading order counts); word recall ignores order. Host x86_64.

| engine | model size | CER | word recall | time / image |
|---|---|---|---|---|
| **Tesseract 5.5, `tessdata_fast` eng (LSTM)** | **4.1 MB** | **11.4 %** | **94.3 %** | 0.61 s |
| Tesseract 5.5, `tessdata` eng (LSTM + legacy) | 23 MB | 11.8 % | 92.4 % | 0.90 s |
| PaddleOCR PP-OCRv3 mobile (det + rec + cls, ONNX via RapidOCR) | 14 MB + ONNX Runtime | 12.4 % | 67.8 % | 1.09 s |

PaddleOCR's recogniser often drops the spaces between English words (low word recall) and needs ONNX Runtime;
Tesseract's fast model is the most accurate here and by far the smallest, so pboard ships it.

## What ships

The engine and model live in the **scan pack**, a separately installed APK (module `:scanpack`, package
`dev.pboard.pack.scan`), like the translation packs, so a user who never scans carries none of it:

* `scanpack/src/main/assets/ocr/eng.traineddata`: tessdata_fast eng (Apache-2.0, github.com/tesseract-ocr/tessdata_fast,
  sha256 7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2), 4.1 MB.
* Tesseract 5.5.0 and Leptonica 1.85.0 native libraries through the JavaCPP presets 1.5.11 (Maven Central, org.bytedeco),
  with their statically linked image libraries; about 13 MB (arm64) and 15 MB (x86_64) uncompressed per ABI.
  Licences: third_party/scanpack/README.md (all permissive; JavaCPP is used under Apache-2.0), texts packaged in the pack.
* `dev.pboard.pack.scan.ScanEngine.recognize(gray, width, height, tessdataDir, lang)`, API version 1.

pboard (`dev.pboard.ime.scan.Ocr`) checks that the pack is installed and signed with pboard's own key, copies the model to
no-backup storage once per pack update, and loads `ScanEngine` from the pack's APK with its own class loader (natives from
the pack's extracted `nativeLibraryDir`). Without the pack, the toolbar's Scan text says so; the scan screen shows the same.

Sizes (2026-11-08):

| APK | size |
|---|---|
| pboard release, OCR bundled (arm64-v8a + x86_64) | 68 MB |
| **pboard release now** (arm64-v8a only, OCR in the pack) | **36.7 MB** (Scan text itself adds < 0.1 MB) |
| scan pack release (arm64-v8a) | 10 MB |
| scan pack debug (arm64-v8a + x86_64, for the emulators) | 17 MB |

Release builds of both APKs are arm64-v8a only; debug and bench builds add x86_64 for the emulators. The libraries' 4 KB
ELF alignment loads in Android 15+'s 16 KB-page compatibility mode on 16 KB devices.

    ./gradlew :scanpack:assembleRelease && adb install scanpack/build/outputs/apk/release/scanpack-release.apk

On the farm emulator the scan of a test photo runs end to end (`ScanTextActivity` debug hook `test_image`): the review
screen shows the text, Insert types it into the field the scan was started from.
