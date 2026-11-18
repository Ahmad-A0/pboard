// Scan pack APK (dev.pboard.pack.scan): Tesseract 5 + Leptonica native code (JavaCPP presets) and the tessdata_fast
// English model for pboard's toolbar "Scan text". Installed separately, so a pboard user who never scans carries none
// of it (~13 MB per ABI). pboard loads ScanEngine from this APK with its own class loader after checking that the pack
// is signed with pboard's key (dev.pboard.ime.scan.Ocr). Like the other packs it is signed with pboard's release key
// and lineage when built with -Ppboard.release=true (tools/release/packs.sh), else with the debug key.
//
//   ./gradlew :scanpack:assembleRelease      arm64-v8a (phones)
//   ./gradlew :scanpack:assembleDebug        arm64-v8a + x86_64 (also the emulators)
plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "dev.pboard.pack.scan"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pboard.pack.scan"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // The engine is called by reflection from pboard; nothing here may be renamed or removed.
            isMinifyEnabled = false
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Licence texts of everything bundled (third_party/scanpack/README.md) ship inside the pack.
    sourceSets["main"].assets.directories.add("../third_party/scanpack")
    packaging {
        // Extracted at install time: pboard's class loader finds them in the pack's nativeLibraryDir.
        jniLibs { useLegacyPackaging = true }
        // The JavaCPP jars also carry GraalVM metadata, C headers, CMake/pkg-config files and the tesseract
        // command-line binary, none of which belong in an APK (a stray lib/<abi>/tesseract even fails installation).
        resources {
            excludes += listOf("META-INF/native-image/**", "lib/*/include/**", "lib/*/lib/**", "lib/*/tesseract")
        }
    }
}
dependencies {
    // Tesseract 5.5 + Leptonica 1.85 through the JavaCPP presets (Apache-2.0; natives Apache-2.0 / BSD-2 and the
    // permissive image libraries listed in third_party/scanpack/). No network.
    implementation(libs.bytedeco.tesseract)
    for (abi in listOf("android-arm64", "android-x86_64")) {
        implementation(variantOf(libs.bytedeco.tesseract) { classifier(abi) })
        implementation(variantOf(libs.bytedeco.leptonica) { classifier(abi) })
        implementation(variantOf(libs.bytedeco.javacpp) { classifier(abi) })
    }
}

// Release signing (-Ppboard.release=true): pboard's release key, read once for all APK modules by the root build script
// (build.gradle.kts); tools/release/packs.sh adds the key-rotation lineage afterwards, as tools/release.sh does for the
// app. A pack must be signed like pboard or pboard ignores it.
@Suppress("UNCHECKED_CAST")
val releaseKey = rootProject.extra["pboardReleaseKey"] as Map<String, String>?
val releaseMode = providers.gradleProperty("pboard.release").map(String::toBoolean).getOrElse(false)
android {
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getValue("storeFile"))
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey.getValue("keyPassword")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug") }
    }
}
// A release-built pack must be release-signed and must carry its model files.
val packInputsPresent = file("src/main/assets/ocr/eng.traineddata").isFile
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val keyMissing = releaseMode && releaseKey == null
    val inputsMissing = releaseMode && !packInputsPresent
    doFirst {
        if (keyMissing) throw GradleException("pboard.release=true but no release key (docs/release.md)")
        if (inputsMissing) throw GradleException("release scan pack without its OCR model (scanpack/src/main/assets/ocr/eng.traineddata)")
    }
}
