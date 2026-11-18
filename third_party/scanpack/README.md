# Third-party components of the scan pack (dev.pboard.pack.scan)

The scan pack (module `:scanpack`, a separate APK) carries Scan text's OCR engine; pboard's own APK contains none of
it. Everything below is permissively licensed; nothing is GPL-only. Licence texts are in the subdirectories and are
packaged into the pack's assets.

| component | version | licence | how it is in the pack |
|---|---|---|---|
| Tesseract OCR | 5.5.0 | Apache-2.0 | `libtesseract.so` (JavaCPP preset build) |
| tessdata_fast `eng.traineddata` | main @ 87416418 | Apache-2.0 | `assets/ocr/eng.traineddata`, unmodified |
| Leptonica | 1.85.0 | BSD-2-Clause | `libleptonica.so` |
| JavaCPP + JavaCPP Presets (tesseract, leptonica) | 1.5.11 | Apache-2.0, or GPLv2+ with the Classpath exception, at the user's choice: **pboard uses them under Apache-2.0** | Java classes, `libjni*.so` |
| zlib | 1.2.11 (as linked) | zlib | statically linked into `libleptonica.so` |
| libpng | 1.6.44 | PNG Reference Library License v2 | statically linked |
| libjpeg-turbo | 3.0.1 | IJG + BSD-3-Clause + zlib | statically linked |
| libtiff | 4.7.0 | libtiff (BSD-like) | statically linked |
| libwebp | 1.4.0 | BSD-3-Clause (+ patent grant) | statically linked |
| OpenJPEG | 2.5.2 | BSD-2-Clause | statically linked |
| GIFLIB | 5.2.2 | MIT | statically linked |
| LLVM libc++ (Android NDK) | NDK r27 era | Apache-2.0 WITH LLVM-exception | statically linked C++ runtime |

Checked 2026-11-08: the arm64 libraries need only the platform's `libc`, `libm`, `libdl` and `liblog` (no shared C++ runtime
or other third-party library); version strings in `libleptonica.so` confirm libpng 1.6.44, libjpeg-turbo 3.0.1, libtiff
4.7.0 and zlib 1.2.11; the libraries contain no GPL text. Library versions come from the presets' `cppbuild.sh`
(github.com/bytedeco/javacpp-presets, tag 1.5.11).
