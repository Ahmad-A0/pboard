// English voice-typing pack APK (dev.pboard.pack.voice.en): the streaming speech recogniser chosen in
// docs/voice-eval.md (X-ASR 480 ms streaming Zipformer transducer with punctuation and casing, int8, Apache-2.0)
// run by sherpa-onnx (Apache-2.0) on ONNX Runtime (MIT). Installed separately, like the scan and translation packs,
// so a pboard user who never dictates carries none of it (~170 MB model + ~27 MB native code per ABI). pboard loads
// VoiceEngine from this APK with its own class loader after checking that the pack is signed with pboard's key
// (dev.pboard.ime.voice.VoicePack). Release builds (-Ppboard.release=true, tools/release/packs.sh) carry pboard's
// release key and rotation lineage; other builds the debug key, matching debug pboard builds.
//
//   tools/voice/fetch_voicepack.sh            # sherpa-onnx AAR + model into tools/voice/work/voicepack (sha256-pinned)
//   ./gradlew :voicepack:assembleRelease      # arm64-v8a (phones)
//   ./gradlew :voicepack:assembleDebug        # arm64-v8a + x86_64 (also the emulators)
//
// Without the fetched inputs the module builds an empty placeholder (so a plain `./gradlew assemble` keeps working).
plugins {
    alias(libs.plugins.android.application)
}
val inputs = rootProject.file("tools/voice/work/voicepack")
val aar = File(inputs, "sherpa-onnx-2.0.1.aar")
val hasEngine = aar.isFile && File(inputs, "assets/voice/encoder.int8.ort").isFile

android {
    namespace = "dev.pboard.pack.voice"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pboard.pack.voice.en"
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
    // Licence texts of everything bundled (third_party/voicepack/README.md) ship inside the pack.
    sourceSets["main"].assets.directories.add("../third_party/voicepack")
    if (hasEngine) {
        sourceSets["main"].kotlin.directories.add("src/engine/java")
        sourceSets["main"].assets.directories.add(File(inputs, "assets").path)
    }
    // the recogniser reads the models through the AssetManager: stored uncompressed they are mapped, not inflated
    androidResources { noCompress += listOf("onnx", "ort") }
    packaging {
        // Extracted at install time: pboard's class loader finds them in the pack's nativeLibraryDir.
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}
dependencies {
    if (hasEngine) implementation(files(aar))
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
val packInputsPresent = hasEngine
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val keyMissing = releaseMode && releaseKey == null
    val inputsMissing = releaseMode && !packInputsPresent
    doFirst {
        if (keyMissing) throw GradleException("pboard.release=true but no release key (docs/release.md)")
        if (inputsMissing) throw GradleException("release voice pack without its model files: run tools/voice/fetch_voicepack.sh")
    }
}
