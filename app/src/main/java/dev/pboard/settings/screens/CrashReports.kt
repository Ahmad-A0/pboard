package dev.pboard.settings.screens

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pboard.R
import dev.pboard.diag.CrashLog
import dev.pboard.settings.Navigator
import dev.pboard.settings.ui.BodyText
import dev.pboard.settings.ui.ConfirmDialog
import dev.pboard.settings.ui.PrefRow
import dev.pboard.settings.ui.SettingsPage

/** Settings › About › Crash reports: the private crash log (dev.pboard.diag.CrashLog), readable, shareable, deletable. */
@Composable
fun CrashReportsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val reports = remember(version) { CrashLog.reports(ctx) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.crash_reports), onBack = { nav.pop() }) {
        if (reports.isEmpty()) {
            item { BodyText(stringResource(R.string.crash_reports_empty)) }
        } else {
            item {
                PrefRow(stringResource(R.string.crash_reports_share), summary = stringResource(R.string.crash_reports_share_summary),
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.crash_reports_subject))
                            .putExtra(Intent.EXTRA_TEXT, CrashLog.shareText(ctx))
                        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
            }
            item { PrefRow(stringResource(R.string.crash_reports_delete), onClick = { confirm = true }) }
            for (r in reports) {
                item(key = r.name) {
                    val time = DateUtils.formatDateTime(ctx, r.timeMs,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                    PrefRow("${r.kind.label} · $time", summary = r.headline,
                        onClick = { open = if (open == r.name) null else r.name })
                }
                if (open == r.name) item(key = r.name + "/text") {
                    SelectionContainer { BodyText(CrashLog.read(ctx, r.name)) }
                }
            }
        }
    }
    if (confirm) {
        ConfirmDialog(stringResource(R.string.crash_reports_delete_confirm), stringResource(R.string.delete), onConfirm = {
            CrashLog.deleteAll(ctx)
            open = null
            version++
        }) { confirm = false }
    }
}
