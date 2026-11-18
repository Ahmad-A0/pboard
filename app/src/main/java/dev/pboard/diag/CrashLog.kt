package dev.pboard.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * pboard's private crash log (see [CrashReports] for the format and privacy rules):
 *  - uncaught exceptions on any thread are written to `files/crashes/` before the process dies (the previous default
 *    handler then runs as usual);
 *  - ANRs, native crashes and kills for excessive resource use are picked up at the next start from the platform's
 *    exit records (ApplicationExitInfo, our own process only; for an ANR its thread dump).
 * Nothing is ever sent anywhere: there is no network code, and export is the user's explicit share from Settings.
 */
object CrashLog {
    private const val DIR = "crashes"
    private const val SEEN = ".exit_seen"
    @Volatile private var installed = false

    fun dir(context: Context) = File(context.filesDir, DIR)

    /** Installs the uncaught-exception hook (idempotent) and records exits since the last start in the background. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { writeCrash(app, thread, e) }
            if (previous != null) previous.uncaughtException(thread, e) else {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
        Thread({ runCatching { syncExitReasons(app) } }, "pboard-crashlog").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        "${p.versionName} (${p.longVersionCode})"
    }.getOrDefault("?")

    private fun androidVersion() = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"

    private fun write(context: Context, kind: CrashReports.Kind, timeMs: Long, detail: String, body: String) {
        val d = dir(context).apply { mkdirs() }
        val text = CrashReports.format(kind, timeMs, appVersion(context), androidVersion(), detail, body)
        File(d, CrashReports.fileName(kind, timeMs)).writeText(text)
        for (name in CrashReports.excess(CrashReports.listDir(d))) File(d, name).delete()
    }

    private fun writeCrash(context: Context, thread: Thread, e: Throwable) {
        write(context, CrashReports.Kind.CRASH, System.currentTimeMillis(), "thread: ${thread.name}", CrashReports.stackTrace(e))
    }

    /** Exit records newer than the last one seen: ANRs (with the thread dump), native crashes, resource kills. */
    private fun syncExitReasons(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val seenFile = File(dir(context).apply { mkdirs() }, SEEN)
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
        var newest = seen
        for (info in infos.sortedBy { it.timestamp }) {
            if (info.timestamp <= seen) continue
            newest = maxOf(newest, info.timestamp)
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> CrashReports.Kind.ANR
                ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashReports.Kind.NATIVE
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
                    CrashReports.Kind.OTHER
                else -> null // Java crashes are written by the hook with their stack trace; normal exits are not kept
            } ?: continue
            val detail = buildString {
                append("process: ").append(info.processName).append('\n')
                append("reason: ").append(info.reason).append(" / ").append(info.status).append('\n')
                append("description: ").append(CrashReports.redact(info.description) ?: "-").append('\n')
                append("importance: ").append(info.importance).append(", pss: ").append(info.pss).append(" kB, rss: ")
                    .append(info.rss).append(" kB")
            }
            val body = if (kind == CrashReports.Kind.ANR) {
                runCatching {
                    info.traceInputStream?.use { s ->
                        s.reader().use { r ->
                            val buf = CharArray(CrashReports.MAX_REPORT_CHARS)
                            val n = r.read(buf).coerceAtLeast(0)
                            String(buf, 0, n)
                        }
                    }
                }.getOrNull() ?: "(no thread dump available)"
            } else if (kind == CrashReports.Kind.NATIVE) {
                "(the native tombstone is kept by the system: adb bugreport)"
            } else ""
            write(context, kind, info.timestamp, detail, body)
        }
        if (newest > seen) seenFile.writeText(newest.toString())
    }

    fun reports(context: Context): List<CrashReports.Report> {
        val d = dir(context)
        return CrashReports.listDir(d).filter { it.startsWith(CrashReports.PREFIX) }.sortedDescending()
            .mapNotNull { n -> runCatching { CrashReports.parse(n, File(d, n).readText()) }.getOrNull() }
    }

    fun read(context: Context, name: String): String =
        runCatching { File(dir(context), File(name).name).readText() }.getOrDefault("")

    fun shareText(context: Context): String =
        CrashReports.shareText(reports(context).map { read(context, it.name) })

    fun deleteAll(context: Context) {
        val d = dir(context)
        for (n in CrashReports.listDir(d)) if (n.startsWith(CrashReports.PREFIX)) File(d, n).delete()
    }
}
