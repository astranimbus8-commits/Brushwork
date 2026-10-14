package com.brushwork.paint.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.InputStream

/**
 * v1.9 (R9, design 3.2 A7a step 2; owner A7a): why the runs before this one ended, read once at
 * start on the diagnostics thread from `ActivityManager.getHistoricalProcessExitReasons` (API 30+;
 * older phones keep only the uncaught handler's reports). The three ends the uncaught handler
 * never sees become reports:
 * - [ApplicationExitInfo.REASON_LOW_MEMORY]: [CrashReport.Kind.LOW_MEMORY_KILL]. On a phone that
 *   does not report low-memory kills (`ActivityManager.isLowMemoryKillReportSupported()` false),
 *   a SIGKILL ([ApplicationExitInfo.REASON_SIGNALED], status 9) is taken for one.
 * - [ApplicationExitInfo.REASON_ANR]: [CrashReport.Kind.ANR], with the main thread of the trace.
 * - [ApplicationExitInfo.REASON_CRASH_NATIVE]: [CrashReport.Kind.NATIVE], with the library and
 *   function names of the tombstone (protobuf on Android 12+; only its readable names are kept).
 *
 * A Java crash ([ApplicationExitInfo.REASON_CRASH]) has its report from the handler already; only
 * on the first run with the crash log (no "exitSeenAt" yet) are the exits Android still keeps
 * from before (v1.8's crashes included) taken, all quiet. Only exits after "exitSeenAt" are read,
 * oldest first; a low-memory kill while the app was in the background is quiet (Android ends
 * apps in the background all the time). The last breadcrumbs ride along with each exit as the
 * process's state summary ([publishSummary]), so even a low-memory kill says what was going on.
 */
internal object ExitReasons {
    /** `ApplicationExitInfo.getStatus()` of a SIGKILL. */
    private const val SIGKILL = 9

    /** At most this many trace lines are kept (before the report's own cap). */
    const val MAX_TRACE_LINES = 200

    /** At most this many bytes of a trace are read. */
    private const val MAX_TRACE_BYTES = 1 shl 20

    /** The process state summary is at most 128 bytes (Android's limit). */
    const val SUMMARY_BYTES = 128

    /**
     * The kind of report an exit with [reason] (and [status], the signal or exit code) makes, or
     * null for an exit that is no failure (the user, the system's normal stops, an exit of its
     * own). [lowMemoryReported]: the phone reports low-memory kills as such. [firstRun]: also a
     * Java crash (no handler wrote it).
     */
    fun kindOf(reason: Int, status: Int = 0, lowMemoryReported: Boolean = true, firstRun: Boolean = false): CrashReport.Kind? = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> CrashReport.Kind.LOW_MEMORY_KILL
        ApplicationExitInfo.REASON_ANR -> CrashReport.Kind.ANR
        ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashReport.Kind.NATIVE
        ApplicationExitInfo.REASON_SIGNALED -> if (status == SIGKILL && !lowMemoryReported) CrashReport.Kind.LOW_MEMORY_KILL else null
        ApplicationExitInfo.REASON_CRASH -> if (firstRun) CrashReport.Kind.CRASH else null
        else -> null
    }

    /**
     * True when an exit of [kind] at [importance] shows the gallery's card: an ANR and a native
     * crash always, a low-memory kill only while the app was visible.
     */
    fun showsCard(kind: CrashReport.Kind, importance: Int): Boolean = when (kind) {
        CrashReport.Kind.ANR, CrashReport.Kind.NATIVE -> true
        CrashReport.Kind.LOW_MEMORY_KILL -> importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        else -> false
    }

    /** The name of exit [reason], as the report gives it. */
    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason $reason"
    }

    /**
     * Reads the exits after "exitSeenAt" and writes their reports (diagnostics thread; never the
     * main thread). Then "exitSeenAt" is the newest exit read, so each exit is read once.
     */
    fun readSince(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        read30(context)
    }

    @RequiresApi(30)
    private fun read30(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = CrashLog.prefs(context)
        val firstRun = !prefs.contains(CrashLog.EXIT_SEEN_AT)
        val seen = prefs.getLong(CrashLog.EXIT_SEEN_AT, 0L)
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        val lowMemoryReported = runCatching { ActivityManager.isLowMemoryKillReportSupported() }.getOrDefault(true)
        var newest = seen
        for (info in infos) {
            newest = maxOf(newest, info.timestamp)
            val kind = kindOf(info.reason, info.status, lowMemoryReported, firstRun) ?: continue
            val quiet = firstRun || !showsCard(kind, info.importance)
            CrashLog.write(info.timestamp, kind, quiet, text(info, kind, lowMemoryReported))
        }
        // The first run with nothing to read starts from now.
        if (firstRun && newest == 0L) newest = System.currentTimeMillis()
        prefs.edit().putLong(CrashLog.EXIT_SEEN_AT, newest).apply()
    }

    /** The report of exit [info]: the usual fields, the exit's facts, its trace. */
    @RequiresApi(30)
    internal fun text(info: ApplicationExitInfo, kind: CrashReport.Kind, lowMemoryReported: Boolean = true): String {
        val desc = ReportText.scrub(info.description)
        val guess = kind == CrashReport.Kind.LOW_MEMORY_KILL && info.reason == ApplicationExitInfo.REASON_SIGNALED
        val summary = ReportText.kindTitle(kind) + when {
            kind == CrashReport.Kind.ANR || kind == CrashReport.Kind.NATIVE -> if (desc.isEmpty()) "" else ": " + desc.take(80)
            else -> ""
        }
        val fields = CrashLog.baseFields(kind, fatal = true, info.timestamp, "process exit (${reasonName(info.reason)}, ${importanceName(info.importance)})")
            .map { if (it.first == "Memory") "Memory" to "at exit: PSS ${info.pss / 1024} MB, RSS ${info.rss / 1024} MB" else it } +
            ("Summary" to summary)
        val exit = listOfNotNull(
            "Reason: ${reasonName(info.reason)} (${info.reason}), status ${info.status}",
            "Importance: ${importanceName(info.importance)} (${info.importance})",
            "Description: $desc".takeIf { desc.isNotEmpty() },
            "This phone does not report low-memory kills: a SIGKILL is taken for one.".takeIf { guess && !lowMemoryReported },
            stateSummary(info.processStateSummary)?.let { "Last breadcrumbs: $it" },
        )
        val trace = try {
            info.traceInputStream?.use { stream ->
                when (kind) {
                    CrashReport.Kind.ANR -> anrMainThread(stream)
                    CrashReport.Kind.NATIVE -> nativeNames(stream)
                    else -> emptyList()
                }
            } ?: emptyList()
        } catch (e: Throwable) {
            listOf("(the trace could not be read: ${e.javaClass.simpleName})")
        }
        val sections = buildList {
            add("Exit" to exit)
            if (trace.isNotEmpty()) add((if (kind == CrashReport.Kind.ANR) "Main thread" else "Native names") to trace)
        }
        return ReportText.compose(fields, sections, CrashLog.MAX_BYTES)
    }

    private fun importanceName(importance: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        else -> "background"
    }

    /** Our own breadcrumbs, as [publishSummary] left them; printable ASCII only. */
    internal fun stateSummary(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val s = String(bytes, Charsets.UTF_8).filter { it.code in 0x20..0x7E }
        return s.ifBlank { null }
    }

    /** The newest breadcrumbs, newest last, in at most [SUMMARY_BYTES] bytes ("tool:BRUSH > open:..."). */
    internal fun summaryOf(crumbs: List<String>): ByteArray {
        val out = ArrayList<String>()
        var size = 0
        for (c in crumbs.asReversed()) {
            val clean = c.filter { it.code in 0x20..0x7E }
            val add = clean.length + if (out.isEmpty()) 0 else 3
            if (size + add > SUMMARY_BYTES) break
            out += clean
            size += add
        }
        return out.asReversed().joinToString(" > ").toByteArray(Charsets.US_ASCII)
    }

    /**
     * Leaves the newest breadcrumbs with Android ([ActivityManager.setProcessStateSummary], API
     * 30+), so the next run's exit info carries them. Diagnostics or watchdog thread.
     */
    fun publishSummary(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(summaryOf(Breadcrumbs.snapshot()))
        } catch (e: Throwable) {
            // Not kept: the reports still have their breadcrumbs.
        }
    }

    /**
     * The main thread of an ANR trace (the text `ActivityManager` dumps): from the line that
     * starts with `"main"` to the blank line after it, at most [MAX_TRACE_LINES]; file paths are
     * cut to their file names and quoted text is scrubbed.
     */
    internal fun anrMainThread(stream: InputStream): List<String> {
        val out = ArrayList<String>()
        var inMain = false
        var read = 0
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                read += line.length + 1
                if (read > MAX_TRACE_BYTES) break
                if (!inMain) {
                    if (line.startsWith("\"main\"")) inMain = true else continue
                }
                if (line.isBlank()) break
                out += traceLine(line)
                if (out.size >= MAX_TRACE_LINES) break
            }
        }
        return out
    }

    private val readable = Regex("""[\x20-\x7E]{4,}""")
    private val symbol = Regex("""^(?:_Z\w+|Java_\w+|SIG[A-Z]+|(?:SEGV|BUS|ILL|FPE|TRAP)_[A-Z_]+|[A-Za-z_][\w$]*(?:::[\w$~<>,* ]+)+.*|(?:com\.brushwork|android|androidx|kotlin|kotlinx|java)\.[\w$.]+)$""")
    private val library = Regex("""\.so\b""")
    private val pathToken = Regex("""[^\s"'()\[\]{}<>,;=]*/([^\s"'()\[\]{}<>,;=/]+)""")

    /**
     * The readable library and function names of a native crash's tombstone (the text of
     * Android 11 or the protobuf of 12+): each run of 4+ printable bytes that names a `.so`, a
     * symbol (C++ `::`, `_Z…`, `Java_…`, an app, Android or Kotlin class) or a signal, paths cut
     * to their file names; consecutive repeats once; at most [MAX_TRACE_LINES]. Anything else
     * (memory maps' file paths, command lines, other text) is left out.
     */
    internal fun nativeNames(stream: InputStream): List<String> {
        val bytes = stream.readNBytesCompat(MAX_TRACE_BYTES)
        val text = String(bytes, Charsets.ISO_8859_1)
        val out = ArrayList<String>()
        for (m in readable.findAll(text)) {
            for (raw in m.value.split('\n')) {
                val trimmed = raw.trim()
                // Android 11's text tombstone: its abort message, scrubbed like a message.
                if (trimmed.startsWith("Abort message")) {
                    out += ReportText.scrub(trimmed)
                    continue
                }
                // In the protobuf a string's length byte comes first, and it may be printable.
                val run = listOf(trimmed, trimmed.drop(1)).firstOrNull { it.length >= 4 && (library.containsMatchIn(it) || symbol.matches(it)) } ?: continue
                val line = traceLine(run)
                if (line.length > ReportText.MAX_MESSAGE || out.lastOrNull() == line) continue
                out += line
                if (out.size >= MAX_TRACE_LINES) return out
            }
        }
        return out
    }

    private val quotedText = Regex(""""[^"]*"|'[^'\n]*'""")
    private val threadName = Regex(""""[A-Za-z][\w.\-]{0,40}"""")

    /**
     * One trace line: every path cut to its file name, quoted text scrubbed (a quoted single word,
     * a thread's name such as `"main"`, stays).
     */
    internal fun traceLine(line: String): String {
        val noPaths = pathToken.replace(line) { it.groupValues[1] }
        return quotedText.replace(noPaths) { m -> if (threadName.matches(m.value)) m.value else "<text>" }
    }

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (buf.size() < max) {
            val n = read(chunk, 0, minOf(chunk.size, max - buf.size()))
            if (n <= 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}
