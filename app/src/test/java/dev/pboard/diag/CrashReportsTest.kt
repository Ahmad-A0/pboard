package dev.pboard.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportsTest {
    @Test fun redactsQuotedTextAndCapsLength() {
        assertEquals("For input string: <5 chars>", CrashReports.redact("For input string: \"hello\""))
        assertEquals("a <3 chars> b <0 chars>", CrashReports.redact("a 'abc' b \"\""))
        assertEquals("index 4 out of bounds for length 3", CrashReports.redact("index 4 out of bounds for length 3"))
        val long = CrashReports.redact("x".repeat(500))!!
        assertTrue(long.length <= 201)
    }

    @Test fun stackTraceKeepsFramesAndCausesButNotTypedText() {
        val e = IllegalStateException("bad \"secret words\"", NumberFormatException("For input string: \"hunter2\""))
        val s = CrashReports.stackTrace(e)
        assertTrue(s.startsWith("java.lang.IllegalStateException: bad <12 chars>"))
        assertTrue(s.contains("Caused by: java.lang.NumberFormatException: For input string: <7 chars>"))
        assertTrue(s.contains("\tat dev.pboard.diag.CrashReportsTest"))
        assertFalse(s.contains("hunter2"))
        assertFalse(s.contains("secret"))
    }

    @Test fun formatThenParseRoundTrips() {
        val t = 1_794_000_000_000L
        val text = CrashReports.format(CrashReports.Kind.ANR, t, "0.1.0 (1)", "16 (API 36)", "thread: main",
            "\"main\" prio=5 tid=1 Blocked\n  at dev.pboard.X.y(X.kt:1)\n")
        val name = CrashReports.fileName(CrashReports.Kind.ANR, t)
        val r = CrashReports.parse(name, text)
        assertEquals(CrashReports.Kind.ANR, r.kind)
        assertEquals(t / 1000, r.timeMs / 1000)
        assertEquals("\"main\" prio=5 tid=1 Blocked", r.headline)
        assertTrue(name.startsWith(CrashReports.PREFIX) && name.endsWith("-anr.txt"))
    }

    @Test fun reportsAreCapped() {
        val big = CrashReports.format(CrashReports.Kind.CRASH, 0, "a", "b", "", "y".repeat(200_000))
        assertTrue(big.length <= CrashReports.MAX_REPORT_CHARS + 32)
        assertTrue(big.endsWith("(truncated)\n"))
    }

    @Test fun retentionKeepsTheNewest() {
        val names = (0 until 25).map { CrashReports.fileName(CrashReports.Kind.CRASH, 1_700_000_000_000L + it * 60_000L) } +
            listOf(".exit_seen", "unrelated.txt")
        val drop = CrashReports.excess(names)
        assertEquals(5, drop.size)
        // the five oldest go
        assertEquals(names.take(5).toSet(), drop.toSet())
    }

    @Test fun shareTextIsBounded() {
        val one = "z".repeat(60_000)
        val s = CrashReports.shareText(List(20) { one })
        assertTrue(s.length <= CrashReports.MAX_SHARE_CHARS + 64)
        assertTrue(s.contains("older reports left out"))
    }
}
