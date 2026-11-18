package dev.pboard.baselineprofile

import android.content.ComponentName
import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

const val PACKAGE = "dev.pboard"
const val IME_ID = "dev.pboard/.ime.PboardIme"

/**
 * Drives the pboard keyboard from UiAutomator. Keys are found through the key surface's accessibility virtual
 * views (one node per key, class [KEY_CLASS], described by its label or name: "q", "Capital Q", "Space",
 * "Delete", "Symbols", ...), so taps land on the right keys for any size set, density or layout; suggestion
 * slots are the top bar's Button nodes. Builds without those nodes fall back to fixed positions of the default
 * QWERTY layout on a 1080x2400 @420dpi phone, anchored to the bottom of the screen.
 */
class KeyboardDriver(private val device: UiDevice) {
    private val sx = device.displayWidth / 1080f
    private val sy = device.displayWidth.toFloat() / device.displaySizeDp.x / (420f / 160f)
    private val bottom = device.displayHeight

    private fun at(x: Int, fromBottom: Int) = Point((x * sx).toInt(), (bottom - fromBottom * sy).toInt())

    /** Fixed positions (fallback): letters, ' ', ',', '.', plus named keys. */
    private val fallback: Map<String, Point> = buildMap {
        fun row(letters: String, xs: IntArray, fromBottom: Int) = letters.forEachIndexed { i, c -> put(c.toString(), at(xs[i], fromBottom)) }
        row("qwertyuiop", intArrayOf(58, 166, 272, 380, 486, 594, 700, 808, 914, 1022), 684)
        row("asdfghjkl", intArrayOf(112, 218, 326, 432, 540, 646, 754, 860, 968), 530)
        row("zxcvbnm", intArrayOf(218, 326, 432, 540, 646, 754, 860), 375)
        put(" ", at(593, 220)); put(",", at(218, 220)); put(".", at(860, 220))
        put("shift", at(85, 375)); put("delete", at(994, 375)); put("symbols", at(85, 220)); put("letters", at(85, 220))
        put("emoji", at(326, 220)); put("enter", at(994, 220)); put("more symbols", at(85, 375))
    }

    /** Key centres on the page currently shown, from the accessibility nodes (refreshed when the page changes). */
    private var page: Map<String, Point> = emptyMap()

    /** Normalised key name of an accessibility description ("Capital Q" -> "q", "Comma" -> ",", "Caps lock on" -> "shift"). */
    private fun keyName(desc: String): String = when {
        desc.startsWith("Capital ") -> desc.removePrefix("Capital ").lowercase()
        desc == "Space" -> " "
        desc == "Comma" -> ","
        desc == "Period" -> "."
        desc.startsWith("Shift") || desc == "Caps lock on" -> "shift"
        desc in ENTER_NAMES -> "enter"
        desc.length == 1 -> desc.lowercase()
        else -> desc.lowercase()
    }

    /** Re-reads the key nodes of the page now shown. */
    fun refreshKeys() {
        page = device.findObjects(By.clazz(KEY_CLASS)).mapNotNull { o ->
            val d = runCatching { o.contentDescription }.getOrNull() ?: return@mapNotNull null
            keyName(d) to runCatching { o.visibleCenter }.getOrNull()
        }.filter { it.second != null }.associate { it.first to it.second!! }
        android.util.Log.i("PboardDriver", "key nodes found: ${page.size}" + if (page.isEmpty()) " (using fixed positions)" else "")
    }

    private fun key(name: String): Point? {
        if (page.isEmpty()) refreshKeys()
        return page[name] ?: fallback[name].takeIf { page.isEmpty() }
    }

    private val stripSlotsFallback = listOf(at(538, 822), at(245, 822), at(833, 822))

    /** Suggestion slots left to right (the top bar's Button nodes other than its menu, back and voice buttons). */
    private fun stripSlots(): List<Point> {
        val buttons = device.findObjects(By.pkg(PACKAGE).clazz("android.widget.Button")).mapNotNull { o ->
            val d = runCatching { o.contentDescription }.getOrNull() ?: return@mapNotNull null
            if (d == "Open features menu" || d == "Back" || d.startsWith("Voice")) null else runCatching { o.visibleCenter }.getOrNull()
        }.sortedBy { it.x }
        return buttons
    }

    /** Makes pboard the enabled and current input method (shell permission; no root needed). */
    fun selectIme() {
        device.executeShellCommand("ime enable $IME_ID")
        device.executeShellCommand("ime set $IME_ID")
    }

    /** Waits until an input method window is shown (up to [timeoutMs]). */
    fun waitForKeyboard(timeoutMs: Long = 5_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val s = device.executeShellCommand("dumpsys input_method")
            if (s.contains("mInputShown=true") || s.contains("mIsInputViewShown=true")) return
            SystemClock.sleep(50)
        }
    }

    fun tap(p: Point, pauseMs: Long = 40) {
        device.click(p.x, p.y)
        SystemClock.sleep(pauseMs)
    }

    /** Types lower-case [text] letter by letter (letters, space, comma, period). */
    fun type(text: String, pauseMs: Long = 40) {
        for (c in text) key(c.toString())?.let { tap(it, pauseMs) }
    }

    /** Glides [word] (consecutive keys joined by straight segments). */
    fun glide(word: String) {
        val pts = word.mapNotNull { key(it.toString()) }.fold(ArrayList<Point>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }
        if (pts.size < 2) return
        device.swipe(pts.toTypedArray(), 6)
        SystemClock.sleep(150)
    }

    fun space() = key(" ")?.let { tap(it) }
    fun delete(times: Int = 1) = repeat(times) { key("delete")?.let { tap(it) } }
    fun shift() = key("shift")?.let { tap(it) }
    fun enter() = key("enter")?.let { tap(it) }

    /** Taps a suggestion strip slot (0 centre, 1 left, 2 right); long-press opens its menu. */
    fun suggestion(slot: Int = 0, long: Boolean = false) {
        val slots = stripSlots()
        val p = when {
            slots.size >= 3 -> listOf(slots[slots.size / 2], slots.first(), slots.last())[slot]
            slots.isNotEmpty() -> slots[0]
            page.isEmpty() -> stripSlotsFallback[slot]
            else -> return
        }
        if (long) device.swipe(p.x, p.y, p.x, p.y, 120) else tap(p)
        SystemClock.sleep(200)
    }

    /** Symbols page, more symbols, a few keys, back to letters. */
    fun symbols() {
        val letters = page
        key("symbols")?.let { tap(it, 300) }
        refreshKeys()
        for (k in listOf("1", "2", "3")) key(k)?.let { tap(it) }
        key("more symbols")?.let { tap(it, 300) }
        refreshKeys()
        for (k in listOf("+", "=")) key(k)?.let { tap(it) }
        key("letters")?.let { tap(it, 300) }
        page = letters
    }

    /** Emoji panel: open, insert, scroll, back to letters (the panel is laid out over the key area). */
    fun emoji() {
        val letters = page
        val q = key("q") ?: return
        val l = key("l") ?: return
        val back = key("symbols") ?: return
        key("emoji")?.let { tap(it, 600) }
        val rowGap = l.y - q.y
        tap(Point(q.x, q.y), 100)
        tap(Point(device.displayWidth / 2, q.y), 100)
        device.swipe(device.displayWidth / 2, l.y + rowGap, device.displayWidth / 2, q.y, 20)
        SystemClock.sleep(300)
        tap(back, 400) // the panel's ABC button sits where the symbols key is
        page = letters
    }

    /** Hides the keyboard (back), then shows it again by tapping the field. */
    fun hideAndShow(field: Point) {
        device.pressBack()
        SystemClock.sleep(300)
        device.click(field.x, field.y)
        waitForKeyboard()
    }

    /** Centre of the playground text field. */
    fun fieldCentre(): Point = device.findObject(By.res("android", "edit"))?.visibleCenter ?: Point(device.displayWidth / 2, device.displayHeight / 3)

    companion object {
        const val KEY_CLASS = "android.inputmethodservice.Keyboard\$Key"
        private val ENTER_NAMES = setOf("Enter", "Search", "Send", "Go", "Next", "Done", "Previous")

        val SENTENCES = listOf(
            "hello there i think we should meet tomorrow afternoon at the library.",
            "the weather is supposed to be terrible so i would rather stay inside.",
            "could you plesae confirm whether everybody recieved the invitation.",
            "thanks a lot for your understanding and patience.",
        )
        val GLIDES = listOf("hello", "world", "something", "beautiful", "the", "and", "keyboard", "information", "tomorrow")
    }
}

fun playgroundIntent(): Intent = Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.PlaygroundActivity"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Opens the playground (its field takes focus and shows the keyboard) and waits for the keyboard. */
fun MacrobenchmarkScope.openPlayground(driver: KeyboardDriver) {
    startActivityAndWait(playgroundIntent())
    device.wait(Until.hasObject(By.res("android", "edit")), 5_000)
    driver.waitForKeyboard()
}

/** One pass over the keyboard's hot paths: tapped sentences (with typos), glides, suggestions, symbols, emoji. */
fun KeyboardDriver.exerciseHotPaths() {
    for (s in KeyboardDriver.SENTENCES) {
        type(s)
        space()
    }
    for (w in KeyboardDriver.GLIDES) glide(w)
    type("recieve")
    suggestion(0)
    type("tomor")
    suggestion(1)
    type("helo")
    suggestion(2, long = true)
    tap(Point(1, 1), 100) // dismiss a popup, if any
    delete(5)
    shift()
    type("london is big")
    space()
    symbols()
    emoji()
    enter()
}
