package dev.pboard.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Keyboard macrobenchmarks, each without ahead-of-time compilation (JIT only, a fresh sideloaded install) and
 * with the Baseline Profile.
 * - keyboardOpenCold: keyboard process killed first; the playground's field shows the keyboard. Metrics: the
 *   app's trace sections (pboard:keyboardOpen = show request to the first key-surface frame, the IME service
 *   creation and the input-view creation).
 * - keyboardReopen: hide the keyboard, tap the field again (process alive).
 * - typingFrames: frame timing while tapping two sentences and gliding words.
 *
 * ANDROID_SERIAL=emulator-5560 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(Parameterized::class)
@LargeTest
class KeyboardBenchmarks(private val compilation: CompilationMode) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<CompilationMode> = listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))

        private const val OPEN = "pboard:keyboardOpen"

        /** Instrumentation argument pboard.iterations scales every iteration count (e.g. 0.5 on the shared phone). */
        private val iterationScale: Double = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("pboard.iterations")?.toDoubleOrNull() ?: 1.0

        private fun iterations(n: Int) = maxOf(2, (n * iterationScale).toInt())
    }

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun keyboardOpenCold() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            TraceSectionMetric(OPEN, TraceSectionMetric.Mode.First),
            TraceSectionMetric("pboard:imeCreate", TraceSectionMetric.Mode.First),
            TraceSectionMetric("pboard:createInputView", TraceSectionMetric.Mode.First),
        ),
        compilationMode = compilation,
        // Not StartupMode.COLD: the system restarts the current input method as soon as it is killed. Instead the
        // setup switches to another installed keyboard and kills pboard; the measurement selects pboard again
        // (starting its process: IME service creation is part of it) and opens the playground.
        startupMode = null,
        iterations = iterations(10),
        setupBlock = {
            val other = device.executeShellCommand("ime list -s").lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith(PACKAGE + "/") }
            if (other != null) device.executeShellCommand("ime set $other")
            killProcess()
        },
    ) {
        val driver = KeyboardDriver(device)
        driver.selectIme()
        openPlayground(driver)
    }

    /** Leaves pboard as the current keyboard whatever happened in a benchmark. */
    @org.junit.After
    fun restoreIme() {
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("ime set $IME_ID")
    }

    @Test
    fun keyboardReopen() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(TraceSectionMetric(OPEN, TraceSectionMetric.Mode.First)),
        compilationMode = compilation,
        startupMode = null,
        iterations = iterations(15),
        setupBlock = {
            val driver = KeyboardDriver(device)
            driver.selectIme()
            openPlayground(driver)
            device.pressBack()
            Thread.sleep(500)
        },
    ) {
        val driver = KeyboardDriver(device)
        val field = driver.fieldCentre()
        device.click(field.x, field.y)
        driver.waitForKeyboard()
    }

    @Test
    fun typingFrames() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = null,
        iterations = iterations(5),
        setupBlock = {
            val driver = KeyboardDriver(device)
            driver.selectIme()
            openPlayground(driver)
        },
    ) {
        val driver = KeyboardDriver(device)
        driver.type(KeyboardDriver.SENTENCES[0])
        driver.space()
        driver.type(KeyboardDriver.SENTENCES[2])
        driver.space()
        for (w in KeyboardDriver.GLIDES.take(5)) driver.glide(w)
        driver.enter()
    }
}
