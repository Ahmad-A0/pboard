plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ------------------------------------------------------------------------------------------------ release signing key
// One source for every APK module (app and the pack APKs: scanpack, translatepack, voicepack, voicepackaccurate), so
// packs verify against pboard's signature check. Only with -Ppboard.release=true (tools/release.sh,
// tools/release/packs.sh); ordinary builds stay debug-signed. The key comes from, in order:
//   - env PBOARD_KEYSTORE, PBOARD_KEYSTORE_PASSWORD, PBOARD_KEY_ALIAS, PBOARD_KEY_PASSWORD (CI secrets), or
//   - the properties file at $PBOARD_SIGNING_PROPERTIES, default ~/.config/pboard/signing.properties
//     (storeFile, storePassword, keyAlias, keyPassword[, lineage]; docs/release.md). Secrets never live in the repo.
// The key-rotation lineage is applied after Gradle by apksigner (tools/release.sh, tools/release/packs.sh).
// Exposed as extra["pboardReleaseKey"]: map storeFile/storePassword/keyAlias/keyPassword, or null.
run {
    val releaseMode = providers.gradleProperty("pboard.release").map(String::toBoolean).getOrElse(false)
    val env = { name: String -> providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } }
    fun read(): Map<String, String>? {
        env("PBOARD_KEYSTORE")?.let { ks ->
            val pw = env("PBOARD_KEYSTORE_PASSWORD") ?: throw GradleException("PBOARD_KEYSTORE set without PBOARD_KEYSTORE_PASSWORD")
            return mapOf("storeFile" to ks, "storePassword" to pw, "keyAlias" to (env("PBOARD_KEY_ALIAS") ?: "pboard"),
                "keyPassword" to (env("PBOARD_KEY_PASSWORD") ?: pw))
        }
        val path = env("PBOARD_SIGNING_PROPERTIES") ?: "${System.getProperty("user.home")}/.config/pboard/signing.properties"
        val f = File(path)
        if (!f.isFile) return null
        val p = java.util.Properties().apply { f.inputStream().use { load(it) } }
        fun prop(k: String) = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() } ?: throw GradleException("$path: missing $k")
        val store = File(prop("storeFile")).let { if (it.isAbsolute) it else File(f.parentFile, it.path) }
        return mapOf("storeFile" to store.path, "storePassword" to prop("storePassword"), "keyAlias" to prop("keyAlias"),
            "keyPassword" to (p.getProperty("keyPassword")?.trim() ?: prop("storePassword")))
    }
    val key = if (releaseMode) read() else null
    key?.let { if (!File(it.getValue("storeFile")).isFile) throw GradleException("release keystore not found: ${it["storeFile"]}") }
    extra["pboardReleaseKey"] = key
}

// Machine-local settings (never tracked): environment variables first, then the first existing of $PBOARD_LOCAL_ENV,
// <root>/local.env, the main checkout's local.env (when this tree is a git worktree) and ~/.config/pboard/local.env.
// Same lookup as tools/dev/local_env.sh; keys are listed in local.env.example.
val pboardLocal: (String) -> String? = run {
    val env = { name: String -> providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } }
    val mainCheckout = rootDir.resolve(".git").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("gitdir: ") && "/.git/worktrees/" in it }
        ?.removePrefix("gitdir: ")?.substringBeforeLast("/.git/worktrees/")
    val file = listOfNotNull(env("PBOARD_LOCAL_ENV")?.let(::File), rootDir.resolve("local.env"),
        mainCheckout?.let { File(it, "local.env") }, File(System.getProperty("user.home"), ".config/pboard/local.env"))
        .firstOrNull { it.isFile }
    val values = file?.readLines().orEmpty().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    val lookup = { key: String -> env(key) ?: values[key]?.takeIf { it.isNotEmpty() } }
    lookup
}

// Device-task safety. AGP installs on / runs against EVERY attached adb device when ANDROID_SERIAL is unset, which
// includes other jobs' emulators and the dev phone. Device tasks therefore require ANDROID_SERIAL, and may target the
// dev phone (PBOARD_PHONE_SERIAL in local.env; no phone rule when it is not configured) only from inside
// tools/phone/guard.sh (which sets PBOARD_GUARD_HELD=1 while holding the phone lock).
subprojects {
    val phoneHost = (pboardLocal("PBOARD_PHONE_SERIAL") ?: pboardLocal("PBOARD_PHONE"))?.substringBefore(':')
    val serial = providers.environmentVariable("ANDROID_SERIAL")
    val guardHeld = providers.environmentVariable("PBOARD_GUARD_HELD")
    val deviceTask = Regex("^(install|uninstall|connected|generate\\w*BaselineProfile).*")
    tasks.configureEach {
        if (deviceTask.matches(name)) {
            doFirst {
                val s = serial.orNull
                if (s.isNullOrBlank() || s.contains(',')) {
                    throw GradleException("$name: set ANDROID_SERIAL to exactly one device (your own emulator); " +
                        "without it AGP targets every attached device, including other jobs' emulators and the dev phone")
                }
                if (phoneHost != null && s.substringBefore(':') == phoneHost && guardHeld.orNull != "1") {
                    throw GradleException("$name: the dev phone may only be targeted inside tools/phone/guard.sh")
                }
            }
        }
    }
}
