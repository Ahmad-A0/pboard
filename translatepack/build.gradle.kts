// Translation pack APK: a code-free APK that only carries one pack under assets/translate/ (see docs/translate.md and
// dev.pboard.ime.translate.TranslatePacks). Built per language from the output of tools/translate/build_pack.py:
//
//   python3 tools/translate/build_pack.py es out/
//   ./gradlew :translatepack:assembleRelease -PtranslatePack=out/es -PtranslatePackId=es [-PtranslatePackVersion=1]
//
// It must be signed with the same key as pboard (pboard ignores pack APKs signed by anyone else). Without the
// properties the module builds an empty placeholder (so a plain `./gradlew assemble` keeps working).
plugins {
    alias(libs.plugins.android.application)
}
val packDir = providers.gradleProperty("translatePack").orNull
val packId = providers.gradleProperty("translatePackId").orElse("none").get()
val packVersion = providers.gradleProperty("translatePackVersion").orElse("1").get().toInt()
require(Regex("[a-z]{2,3}|none").matches(packId)) { "translatePackId must be a language code" }

android {
    namespace = "dev.pboard.pack.translate"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pboard.pack.translate.$packId"
        minSdk = 29
        targetSdk = 36
        versionCode = packVersion
        versionName = "$packVersion"
        manifestPlaceholders["packLabel"] = "pboard translation: $packId"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    sourceSets["main"].assets.directories.apply { if (packDir != null) add(packDir) }
    // the models are read as streams; deflating them in the APK makes the download ~30 % smaller
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
// packDir is resolved like the assets directory above: relative to this module
val packInputsPresent = packDir != null && packId != "none" && file(packDir).resolve("translate/manifest.json").isFile
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val keyMissing = releaseMode && releaseKey == null
    val inputsMissing = releaseMode && !packInputsPresent
    doFirst {
        if (keyMissing) throw GradleException("pboard.release=true but no release key (docs/release.md)")
        if (inputsMissing) throw GradleException("release translation pack without its model files: pass -PtranslatePack=<built pack dir> -PtranslatePackId=<lang> (tools/translate/build_pack.py)")
    }
}
