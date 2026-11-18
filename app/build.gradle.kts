import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

// ------------------------------------------------------------------------------------------------ version (from git)
// versionCode = number of commits reachable from HEAD (monotonic along main, which only moves forward).
// versionName = the last `vX.Y.Z` tag ("0.2.0" exactly on the tag, "0.2.0-5-gabc1234567" after it, "-dirty" with
// uncommitted changes); before the first tag the base is `pboard.versionBase` (gradle.properties).
// Without git (e.g. a source tarball) the build still works, as versionCode 1 / "<base>-nogit".
fun git(vararg args: String): String = providers.exec {
    commandLine("git", *args)
    workingDir = rootDir
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

val versionBase = providers.gradleProperty("pboard.versionBase").getOrElse("0.1.0")
val gitCount = git("rev-list", "--count", "HEAD").toIntOrNull()
val gitVersionName: String = run {
    val dirty = if (git("status", "--porcelain", "--untracked-files=no").isNotEmpty()) "-dirty" else ""
    val describe = Regex("""^v(\d+\.\d+\.\d+[^-]*)-(\d+)-g([0-9a-f]+)$""")
        .find(git("describe", "--tags", "--match", "v[0-9]*", "--long", "--abbrev=10"))
    val sha = git("rev-parse", "--short=10", "HEAD")
    when {
        gitCount == null -> "$versionBase-nogit"
        describe == null -> "$versionBase-$gitCount-g$sha$dirty"
        describe.groupValues[2] == "0" -> describe.groupValues[1] + dirty
        else -> "${describe.groupValues[1]}-${describe.groupValues[2]}-g${describe.groupValues[3]}$dirty"
    }
}

// ------------------------------------------------------------------------------------------------ release signing
// Ordinary builds (dev, bench, conformance, the dev phone) are signed with the machine's debug key, exactly as before:
// the dev phone's installed pboard is debug-signed and must not receive a differently-signed build (that would need an
// uninstall, which wipes learned data). Release signing is opt-in: -Ppboard.release=true (set by tools/release.sh)
// signs `release` with the key described by, in order:
//   - env PBOARD_KEYSTORE, PBOARD_KEYSTORE_PASSWORD, PBOARD_KEY_ALIAS, PBOARD_KEY_PASSWORD (CI secrets), or
//   - the properties file at $PBOARD_SIGNING_PROPERTIES, default ~/.config/pboard/signing.properties
//     (storeFile, storePassword, keyAlias, keyPassword; see docs/release.md). Secrets never live in the repo.
// Without a key, a release build FAILS unless -Ppboard.allowDebugSigning=true (CI fallback): it is then signed with
// the debug key and labelled as such in its versionName ("+debugsigned") and by tools/release.sh's file names.
val releaseMode = providers.gradleProperty("pboard.release").map(String::toBoolean).getOrElse(false)
val allowDebugSigning = providers.gradleProperty("pboard.allowDebugSigning").map(String::toBoolean).getOrElse(false)

data class ReleaseKey(val storeFile: File, val storePassword: String, val alias: String, val keyPassword: String)

// The key is read once for all APK modules by the root build script (build.gradle.kts, extra["pboardReleaseKey"]).
@Suppress("UNCHECKED_CAST")
fun releaseKey(): ReleaseKey? = (rootProject.extra["pboardReleaseKey"] as Map<String, String>?)?.let {
    ReleaseKey(File(it.getValue("storeFile")), it.getValue("storePassword"), it.getValue("keyAlias"), it.getValue("keyPassword"))
}

val releaseKey: ReleaseKey? = if (releaseMode) releaseKey() else null
if (releaseMode && releaseKey == null && !allowDebugSigning) {
    throw GradleException(
        "pboard.release=true but no release key: create ~/.config/pboard/signing.properties (docs/release.md), set " +
            "PBOARD_SIGNING_PROPERTIES / PBOARD_KEYSTORE*, or pass -Ppboard.allowDebugSigning=true for a debug-signed CI build"
    )
}
if (releaseMode && releaseKey == null) {
    logger.warn("pboard: RELEASE BUILD SIGNED WITH THE DEBUG KEY (no release key found; pboard.allowDebugSigning=true)")
}
releaseKey?.let { if (!it.storeFile.isFile) throw GradleException("release keystore not found: ${it.storeFile}") }

android {
    namespace = "dev.pboard"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pboard"
        minSdk = 29
        targetSdk = 36
        versionCode = gitCount ?: 1
        versionName = gitVersionName + if (releaseMode && releaseKey == null) "+debugsigned" else ""
        // Benchmark idle probe (dev.pboard.ime.bench.BenchProbe): compiled out everywhere but `bench`.
        buildConfigField("boolean", "BENCH_PROBE", "false")
    }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = releaseKey.storeFile
            storePassword = releaseKey.storePassword
            keyAlias = releaseKey.alias
            keyPassword = releaseKey.keyPassword
            // minSdk 29: APK Signature Scheme v2 + v3 (v3 carries a key-rotation lineage when tools/release.sh adds one)
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // Releases go to phones: 64-bit ARM code only (debug and bench builds keep x86_64 for the emulators).
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        // = release + the benchmark harness's explicit idle signal (content://dev.pboard.benchprobe,
        // declared in src/bench/AndroidManifest.xml). Same code and shrinking otherwise; tools/bench
        // checks item-for-item that it decodes exactly like release. Always debug-signed (never distributed).
        create("bench") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters.clear(); abiFilters += listOf("arm64-v8a", "x86_64") }
            buildConfigField("boolean", "BENCH_PROBE", "true")
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // language packs are memory-mapped straight from the APK (AssetFileDescriptor), so keep them uncompressed
    androidResources { noCompress += listOf("bin", "lm", "lmo", "nnlm") }
}

// ------------------------------------------------------------------------------------------------ permission gate
// Privacy invariant: pboard has no network access and requests no permission outside the allowlist below. Every variant's merged
// manifest (i.e. including whatever a dependency adds) is checked before its APK is packaged; the build FAILS if a
// network permission appears or any permission outside ALLOWED_PERMISSIONS is requested.
val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.NEARBY_WIFI_DEVICES",
    "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_ADMIN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.NFC",
    "android.permission.UWB_RANGING",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "com.google.android.c2dm.permission.RECEIVE",
    "com.google.android.gms.permission.AD_ID",
)
// Requested permissions that are expected. `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is androidx
// core's signature-level permission guarding the app's own non-exported dynamic receivers. RECORD_AUDIO is voice
// typing's microphone: opt-in, requested at runtime only from the mic key (dev.pboard.ime.voice), recognised on-device.
// READ_CONTACTS is "Suggest contact names": opt-in (off by default), requested at runtime only from that settings
// switch; display names are read into memory (dev.pboard.ime.contacts) and never stored.
val allowedPermissions = listOf(
    "\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_CONTACTS",
)

abstract class CheckManifestPermissions : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val forbidden: ListProperty<String>
    @get:Input abstract val allowed: ListProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        // <uses-permission>, <uses-permission-sdk-23>, ... with an android:name attribute (merged manifests are tidy XML)
        val requested = Regex("""<uses-permission[\w-]*\s[^>]*?android:name="([^"]+)"""").findAll(text)
            .map { it.groupValues[1] }.toSortedSet()
        val allowed = this.allowed.get().map { it.replace("\${applicationId}", applicationId.get()) }.toSet()
        val network = requested.filter { it in forbidden.get() }
        val unexpected = requested.filter { it !in allowed && it !in network }
        report.get().asFile.writeText(requested.joinToString("\n", postfix = "\n"))
        if (network.isNotEmpty() || unexpected.isNotEmpty()) {
            throw GradleException(
                "pboard privacy gate: the merged manifest ${manifest.get().asFile} requests " +
                    (if (network.isNotEmpty()) "FORBIDDEN network/radio permissions $network " else "") +
                    (if (unexpected.isNotEmpty()) "unexpected permissions $unexpected " else "") +
                    "(pboard must never gain network access; see app/build.gradle.kts). Remove them, e.g. with " +
                    "<uses-permission android:name=\"...\" tools:node=\"remove\"/> in src/main/AndroidManifest.xml."
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar(Char::uppercase)
        val gate = tasks.register<CheckManifestPermissions>("check${cap}ManifestPermissions") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            applicationId.set(variant.applicationId)
            forbidden.set(forbiddenPermissions)
            allowed.set(allowedPermissions)
            report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
        }
        tasks.configureEach { if (name == "package$cap" || name == "check") dependsOn(gate) }
    }
}

// Baseline Profile (ahead-of-time compiled hot paths: keyboard open, tap/glide decoding, panels). Generated by
// :baselineprofile on an emulator and checked in under src/main/generated/baselineProfiles; profileinstaller
// applies it on sideloaded installs, Play applies it at install time.
baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
}
dependencies {
    implementation(project(":engine"))
    implementation(project(":translate"))
    implementation(libs.androidx.core.ktx)
    // Inline autofill (CX-14): the v1 chip style the autofill service renders with. Apache-2.0, no network.
    implementation(libs.androidx.autofill)
    // Clipboard entity chips (ED-07): phone numbers in copied text. Apache-2.0, no network.
    implementation(libs.libphonenumber)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit)
}
