// Baseline Profile generator and keyboard macrobenchmarks for :app.
//
// Runs on one connected device or emulator: select it with ANDROID_SERIAL, because the connected tasks would
// otherwise install and run on every attached device. A non-rooted API 33+ image is enough.
//   ANDROID_SERIAL=emulator-5560 ./gradlew :app:generateBaselineProfile
//   ANDROID_SERIAL=emulator-5560 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
//       -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.pboard.baselineprofile"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Macrobenchmark refuses emulators by default; numbers from an emulator are relative (with vs without
        // the profile), not absolute device timings.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
