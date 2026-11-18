plugins {
    alias(libs.plugins.kotlin.jvm)
    // JMH lives in its own source set (src/jmh/kotlin) and never reaches the app or the APK.
    alias(libs.plugins.jmh)
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    maxHeapSize = "3g"
    // the shipped-pack tests read the language assets directly; rerun them when an asset is rebuilt
    inputs.dir(rootProject.file("app/src/main/assets/lm")).withPropertyName("lmAssets").optional()
    // -PlmDir=<dir> runs the shipped-pack tests (ShippedPackTest) against another pack directory
    providers.gradleProperty("lmDir").orNull?.let {
        systemProperty("pboard.lmDir", it)
        inputs.dir(it).withPropertyName("lmDirOverride")
    }
}

// Synthetic-trace evaluation of the decoders against the dev stand-in LM (needs tools/dev/fetch_devdata.sh).
// Pass options with -Peval.args="--set tap.lmWeight=1.1 --limit 2000".
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Runs the typing/swipe evaluation harness on tools/bench/sets."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.pboard.engine.eval.EvalMainKt")
    workingDir = rootProject.projectDir
    maxHeapSize = "3g"
    args(providers.gradleProperty("eval.args").orElse("").get().split(' ').filter { it.isNotBlank() })
}

// Automated search over the decoder constants on the human TUNE splits (TuneMain.kt):
// ./gradlew :engine:tune -Ptune.args="--mode tap --lm-dir <packs> --threads 16 --pop 16 --gens 30 --out tune.tsv"
tasks.register<JavaExec>("tune") {
    group = "verification"
    description = "Separable CMA-ES over decoder constants on the human TUNE splits."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.pboard.engine.eval.TuneMainKt")
    workingDir = rootProject.projectDir
    maxHeapSize = providers.gradleProperty("tune.heap").orElse("12g").get()
    args(providers.gradleProperty("tune.args").orElse("").get().split(' ').filter { it.isNotBlank() })
}

// Self-contained classpath for running the tuner elsewhere (e.g. a many-core cloud box):
// java -cp 'build/tune-bundle/*' dev.pboard.engine.eval.TuneMainKt ... (run from a directory with settings.gradle.kts,
// tools/data/human/{splits/v1/*.tune.*,geometry} and the LM packs)
val testJar = tasks.register<Jar>("testJar") { archiveClassifier.set("tests"); from(sourceSets["test"].output) }
tasks.register<Sync>("tuneBundle") {
    from(sourceSets["test"].runtimeClasspath.filter { it.isFile })
    from(tasks.named("jar"))
    from(testJar)
    into(layout.buildDirectory.dir("tune-bundle"))
}

// Language-model benchmark: ./gradlew :engine:lmEval -PlmArgs="<pack.lm> <evalDir> [overlay.lmo ...]"
tasks.register<JavaExec>("lmEval") {
    group = "verification"
    description = "Top-k / keystroke-savings / latency benchmark of a language pack (see LmEval.kt)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.pboard.engine.lm.LmEvalKt")
    args = (project.findProperty("lmArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
    jvmArgs("-Xmx1g")
}

// Grammar-check evaluation dump (textintel/GecEval.kt):
// ./gradlew :engine:gecEval -PgecArgs="<out.jsonl> en-GB <set.jsonl> [more sets]"
tasks.register<JavaExec>("gecEval") {
    group = "verification"
    description = "Scores every grammar-check candidate with the n-gram and n-gram+LSTM models (see GecEval.kt)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.pboard.engine.textintel.GecEvalKt")
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
    args(providers.gradleProperty("gecArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}

// Smart Compose offline evaluation (textintel/ComposeEval.kt):
// ./gradlew :engine:composeEval -PcomposeArgs="<set.txt> [more sets]"
tasks.register<JavaExec>("composeEval") {
    group = "verification"
    description = "Inline-completion exact-match rate and coverage on held-out text (see ComposeEval.kt)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.pboard.engine.textintel.ComposeEvalKt")
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
    args(providers.gradleProperty("composeArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}

// Swipe decoder comparison on the human splits (template matcher vs. neural model), production LM:
// ./gradlew :engine:swipeEval -PswipeArgs="--split tune --model tools/swipe/work/run/model.pbsw"
tasks.register<JavaExec>("swipeEval") {
    group = "verification"
    description = "Template vs. neural gesture decoding on the human swipe splits (see NeuralSwipeEval.kt)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.pboard.engine.swipe.NeuralSwipeEvalKt")
    workingDir = rootProject.projectDir
    maxHeapSize = "3g"
    args(providers.gradleProperty("swipeArgs").orElse("").get().split(' ').filter { it.isNotBlank() })
}

// Latency benchmarks: ./gradlew :engine:jmh [-Pjmh.includes=TapKeystroke]
// Benchmarks reuse the test fixtures (dev LM, trace sets), hence includeTests.
jmh {
    jmhVersion.set(libs.versions.jmh)
    includeTests.set(true)
    resultFormat.set("JSON")
    providers.gradleProperty("jmh.includes").orNull?.let { includes.set(listOf(it)) }
    // e.g. -Pjmh.profilers=gc for allocation rates (gc.alloc.rate.norm, bytes per call)
    providers.gradleProperty("jmh.profilers").orNull?.let { profilers.set(it.split(',')) }
    // -Pjmh.neural=true [-Pjmh.rerank=<N>|full]: with the LSTM rescorer, re-ranking the final top N or everywhere
    jvmArgsAppend.set(listOf("-Xmx3g") + listOfNotNull(providers.gradleProperty("jmh.neural").orNull?.let { "-Dpboard.neural=$it" },
        providers.gradleProperty("jmh.rerank").orNull?.let { "-Dpboard.rerank=$it" }))
}
