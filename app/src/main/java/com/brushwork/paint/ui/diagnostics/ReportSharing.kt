package com.brushwork.paint.ui.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.diagnostics.ReportText
import com.brushwork.paint.ui.common.StabilityLabels19
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v1.9 (R9, design 3.2 A7a step 5; owner A7a): what the crash card's and the Diagnostics list's
 * buttons do with a report: copy its text ([copy], then [StabilityLabels19.REPORT_COPIED]), share
 * it as text through the system's share sheet ([share]), share them all ([shareAll]: one text, or
 * past [TEXT_LIMIT] a file in the app's cache shared through its FileProvider). Nothing leaves the
 * phone unless the user picks where it goes. Every step is guarded: a failure is logged, never thrown.
 */
internal object ReportSharing {
    private const val TAG = "Brushwork"

    /** The clip's and the share's title (stands in for a LabelsV19 entry: not shown as a control). */
    private const val CLIP_LABEL = ReportText.TITLE

    /** Shared as one text up to this many bytes (an intent's extras stay well under the 1 MB binder limit). */
    const val TEXT_LIMIT = 100 * 1024

    /** The file [StabilityLabels19.SHARE_ALL] writes past [TEXT_LIMIT], under the FileProvider's `exports/` cache path. */
    const val ALL_FILE = "brushwork-reports.txt"

    /** Between two reports in [StabilityLabels19.SHARE_ALL]. */
    private const val SEPARATOR = "\n\n----------------------------------------\n\n"

    /** Copies [report]'s text and says [StabilityLabels19.REPORT_COPIED]. True when it was copied. */
    fun copy(context: Context, report: CrashReport): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, report.text))
        Toast.makeText(context, StabilityLabels19.REPORT_COPIED, Toast.LENGTH_SHORT).show()
        true
    } catch (e: Throwable) {
        Log.w(TAG, "report not copied", e)
        false
    }

    /** The share sheet for [report]'s text. True when it was shown. */
    fun share(context: Context, report: CrashReport): Boolean =
        start(context, textIntent("${ReportText.TITLE} ${ReportText.utc(report.timeMillis)}", report.text))

    /** The text of every report in [reports], newest first. */
    fun allText(reports: List<CrashReport>): String = reports.joinToString(SEPARATOR) { it.text }

    /**
     * The intent [StabilityLabels19.SHARE_ALL] sends: the reports as one text, or past [TEXT_LIMIT] bytes as the file
     * [ALL_FILE] in the cache (written here: call it off the main thread). Null when there is
     * nothing to share or the file could not be written.
     */
    fun shareAllIntent(context: Context, reports: List<CrashReport>): Intent? {
        if (reports.isEmpty()) return null
        val subject = "${ReportText.TITLE}s (${reports.size})"
        val text = allText(reports)
        if (ReportText.utf8Length(text) <= TEXT_LIMIT) return textIntent(subject, text)
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val f = File(dir, ALL_FILE)
            f.writeText(text, Charsets.UTF_8)
            val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(subject, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "reports not written for sharing", e)
            null
        }
    }

    /** Shows the share sheet for [intent] (from [shareAllIntent]). True when it was shown. */
    fun shareAll(context: Context, intent: Intent?): Boolean = intent != null && start(context, intent)

    private fun textIntent(subject: String, text: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        val chooser = Intent.createChooser(intent, null)
        if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    } catch (e: Throwable) {
        Log.w(TAG, "share sheet not shown", e)
        false
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * The time each report's buttons name ([StabilityLabels19.COPY_REPORT] "Copy report 12 Oct
     * 14:03"), [times] newest first, in [zone]: the minute; the second for reports of the same
     * minute; then " (2)", " (3)" for reports of the same second, so no two buttons read alike.
     */
    fun rowTimes(times: List<Long>, zone: TimeZone = TimeZone.getDefault()): List<String> {
        val minute = SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).apply { timeZone = zone }
        val second = SimpleDateFormat("d MMM HH:mm:ss", Locale.ENGLISH).apply { timeZone = zone }
        val byMinute = times.map { minute.format(Date(it)) }
        val minuteCounts = byMinute.groupingBy { it }.eachCount()
        val labels = times.mapIndexed { i, t -> if (minuteCounts.getValue(byMinute[i]) > 1) second.format(Date(t)) else byMinute[i] }
        val counts = labels.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return labels.map { l ->
            if (counts.getValue(l) == 1) {
                l
            } else {
                val k = (seen[l] ?: 0) + 1
                seen[l] = k
                if (k == 1) l else "$l ($k)"
            }
        }
    }
}
