package dev.pboard.diag

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Format, privacy redaction and retention of pboard's on-device crash reports (no Android dependencies, so it is
 * unit-tested on the JVM; [CrashLog] is the Android side).
 *
 * A report is a small text file in app-private storage: a header (kind, time, app and Android version, thread) and a
 * stack trace or ANR thread dump. Nothing typed ever goes in: exception messages are kept for their shape only -
 * quoted parts (`For input string: "hello"`) are replaced by their length - and they are cut short. Reports are
 * never transmitted; the user can read, share (share sheet) or delete them from Settings › About.
 */
object CrashReports {
    const val MAX_REPORTS = 20
    const val MAX_REPORT_CHARS = 64 * 1024
    /** The share sheet's text is bounded well below the binder transaction limit. */
    const val MAX_SHARE_CHARS = 400 * 1024
    private const val MAX_MESSAGE_CHARS = 200
    const val PREFIX = "report-"
    const val SUFFIX = ".txt"

    enum class Kind(val label: String) { CRASH("Crash"), ANR("Not responding (ANR)"), NATIVE("Native crash"), OTHER("Stopped") }

    data class Report(val name: String, val kind: Kind, val timeMs: Long, val headline: String)

    private val QUOTED = Regex("\"[^\"]*\"|'[^']*'|“[^”]*”")

    /** An exception message with anything quoted (often user or field text) replaced by its length, and cut short. */
    fun redact(message: String?): String? {
        if (message == null) return null
        val r = QUOTED.replace(message) { m -> "<${m.value.length - 2} chars>" }
        return if (r.length > MAX_MESSAGE_CHARS) r.substring(0, MAX_MESSAGE_CHARS) + "…" else r
    }

    /** The stack trace of [t] and its causes, with every message redacted. */
    fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        var cur: Throwable? = t
        var first = true
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur)) {
            val msg = redact(cur.message)
            pw.print(if (first) "" else "Caused by: ")
            pw.println(cur.javaClass.name + (if (msg != null) ": $msg" else ""))
            for (e in cur.stackTrace) pw.println("\tat $e")
            for (s in cur.suppressed) pw.println("\tSuppressed: " + s.javaClass.name + (redact(s.message)?.let { ": $it" } ?: ""))
            first = false
            cur = cur.cause
        }
        pw.flush()
        return sw.toString()
    }

    fun time(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))

    fun fileName(kind: Kind, timeMs: Long): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return PREFIX + f.format(Date(timeMs)) + "-" + kind.name.lowercase(Locale.US) + SUFFIX
    }

    /** The report text: header lines, a blank line, then [body] (capped at [MAX_REPORT_CHARS]). */
    fun format(kind: Kind, timeMs: Long, app: String, android: String, detail: String, body: String): String {
        val head = buildString {
            append("pboard ").append(kind.label).append('\n')
            append("time: ").append(time(timeMs)).append('\n')
            append("app: ").append(app).append('\n')
            append("android: ").append(android).append('\n')
            if (detail.isNotEmpty()) append(detail.trimEnd()).append('\n')
            append('\n')
        }
        val room = MAX_REPORT_CHARS - head.length
        val b = if (body.length > room) body.substring(0, room.coerceAtLeast(0)) + "\n… (truncated)\n" else body
        return head + b
    }

    /** Kind, time and first line of the trace, from a stored report. */
    fun parse(name: String, text: String): Report {
        val lines = text.lineSequence().toList()
        val kind = Kind.entries.firstOrNull { lines.firstOrNull() == "pboard " + it.label } ?: Kind.OTHER
        val t = lines.firstOrNull { it.startsWith("time: ") }?.removePrefix("time: ")
            ?.let { runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).parse(it)?.time }.getOrNull() } ?: 0L
        val blank = lines.indexOf("")
        val headline = lines.drop(if (blank >= 0) blank + 1 else 0).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return Report(name, kind, t, headline.take(160))
    }

    /** Report files beyond the newest [MAX_REPORTS] (to delete). Names sort by time (UTC timestamp prefix). */
    fun excess(names: List<String>): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }.sortedDescending().drop(MAX_REPORTS)

    /** All reports as one text for the share sheet, newest first, bounded by [MAX_SHARE_CHARS]. */
    fun shareText(texts: List<String>): String {
        val sb = StringBuilder()
        for (t in texts) {
            if (sb.length + t.length + 8 > MAX_SHARE_CHARS) { sb.append("… (older reports left out)\n"); break }
            if (sb.isNotEmpty()) sb.append("\n-----\n\n")
            sb.append(t)
        }
        return sb.toString()
    }

    fun listDir(dir: File): List<String> = dir.list()?.toList().orEmpty()
}
