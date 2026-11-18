// Benchmark harness APK: a self-targeting Instrumentation that replays touch traces with exact
// MotionEvent timestamps through UiAutomation.injectInputEvent. No dependencies on purpose.
plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "dev.pboard.bench"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pboard.bench"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
