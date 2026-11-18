// On-device machine translation: Marian (Firefox Translations / Bergamot) model inference, SentencePiece tokenizer,
// translation-pack format. Pure Kotlin/JVM with no Android dependencies, so it is unit-testable on the host.
plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    maxHeapSize = "2g"
    // -PtranslateModels=<dir> runs the model tests (TranslatorModelTest) against real packs: <dir>/<from>-<to>/{model,vocab,lex}
    providers.gradleProperty("translateModels").orNull?.let {
        systemProperty("pboard.translateModels", it)
        inputs.dir(it).withPropertyName("translateModels")
    }
}

// Jars for the on-phone benchmark (PhoneBench via app_process; dexed by tools/translate/phone_bench.sh).
val benchTestJar = tasks.register<Jar>("benchTestJar") { archiveClassifier.set("tests"); from(sourceSets["test"].output) }
tasks.register<Sync>("phoneBenchBundle") {
    from(sourceSets["main"].runtimeClasspath.filter { it.isFile && it.name.startsWith("kotlin-stdlib") })
    from(tasks.named("jar"))
    from(benchTestJar)
    into(layout.buildDirectory.dir("phone-bench"))
}

// Quality/latency harness: ./gradlew :translate:evalMt -PevalArgs="<modelDir> <src.txt> <out.txt>"
tasks.register<JavaExec>("evalMt") {
    group = "verification"
    description = "Translates a file line by line with a model directory and reports latency (see EvalMain.kt)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.pboard.translate.EvalMainKt")
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
    args(providers.gradleProperty("evalArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}
