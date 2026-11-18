// Test fixtures for system-integration work (never shipped): a minimal autofill service that answers with inline
// suggestions (CX-14), a login form, and a rich-content receiver (commitContent / image keyboard API). Lets us
// compare pboard and Gboard on an emulator without a Google account or a third-party password manager.
plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "dev.pboard.testapp"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pboard.testapp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.core.ktx)
}
