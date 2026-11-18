// Optional "accurate" voice-typing pack APK (dev.pboard.pack.voice.en.accurate): a data-only APK carrying NVIDIA
// parakeet-tdt_ctc-110m (TDT head, int8, ORT format; CC-BY-4.0, attribution in assets/voice/accurate/ATTRIBUTION.txt).
// When it is installed next to the voice pack, pboard re-decodes each finished utterance with it (the streaming model
// keeps drawing the live words): CV 11.1 % vs 18.7 % WER, about 0.4 s more after each pause (docs/voice-eval.md).
// Off unless installed. It runs on the voice pack's engine, so it carries no code. Signed like the other packs:
// pboard's release key and lineage with -Ppboard.release=true (tools/release/packs.sh), else the debug key.
//
//   tools/voice/fetch_voicepack.sh                 # also prepares tools/voice/work/voicepack-accurate (sha256-pinned)
//   ./gradlew :voicepackaccurate:assembleRelease
//
// Without the fetched inputs the module builds an empty placeholder.
plugins {
    alias(libs.plugins.android.application)
}
val inputs = rootProject.file("tools/voice/work/voicepack-accurate/assets")

android {
    namespace = "dev.pboard.pack.voice.accurate"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pboard.pack.voice.en.accurate"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    sourceSets["main"].assets.directories.add("../third_party/voicepackaccurate")
    if (File(inputs, "voice/accurate/encoder.int8.ort").isFile) sourceSets["main"].assets.directories.add(inputs.path)
    androidResources { noCompress += listOf("ort") }
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
val packInputsPresent = File(inputs, "voice/accurate/encoder.int8.ort").isFile
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val keyMissing = releaseMode && releaseKey == null
    val inputsMissing = releaseMode && !packInputsPresent
    doFirst {
        if (keyMissing) throw GradleException("pboard.release=true but no release key (docs/release.md)")
        if (inputsMissing) throw GradleException("release accurate voice pack without its model files: run tools/voice/fetch_voicepack.sh")
    }
}
