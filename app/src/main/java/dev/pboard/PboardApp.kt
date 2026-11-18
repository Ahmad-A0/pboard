package dev.pboard

import android.app.Application
import dev.pboard.diag.CrashLog

/** Process entry point (keyboard, settings and playground share the process): installs the private crash log first. */
class PboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
