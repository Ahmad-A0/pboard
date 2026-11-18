package dev.pboard.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile for :app from the keyboard's real hot paths: opening the keyboard in the
 * playground, tapping sentences (with typos, so autocorrect and suggestions run), glide typing, picking and
 * long-pressing suggestions, shift, the symbols pages and the emoji panel.
 *
 * Run on a single device: ANDROID_SERIAL=emulator-5560 ./gradlew :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        val driver = KeyboardDriver(device)
        driver.selectIme()
        pressHome()
        openPlayground(driver)
        driver.exerciseHotPaths()
        // Hide and show again: the warm open path.
        driver.hideAndShow(driver.fieldCentre())
        driver.type("done")
    }
}
