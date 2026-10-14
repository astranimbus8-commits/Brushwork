package com.brushwork.paint.diagnostics

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** v1.9 (R9, design 2.4.13; owner A7a): one report of the crash log. */
data class CrashReport(val id: String, val timeMillis: Long, val kind: Kind, val summary: String, val text: String) {
    enum class Kind { CRASH, OUT_OF_MEMORY, FREEZE, NATIVE, ANR, LOW_MEMORY_KILL }
}

/**
 * v1.9 (R9, design 3.2 A7a; owner A7a): the on-device crash log the user can copy and share.
 *
 * - Files: `filesDir/diagnostics/reports/<13-digit millis>-<kind>[-quiet].txt` (kind in lower
 *   case, e.g. `1760364187000-crash.txt`), one report each ([ReportText]), the newest
 *   [MAX_REPORTS] kept, each at most [MAX_BYTES]. The id of a report is its file name without
 *   `.txt`. "quiet" reports are listed under Diagnostics only, never on the gallery's card: a
 *   handled error, a freeze, a low-memory kill while the app was in the background.
 * - [install] (the first line of `BrushworkApp.onCreate`) puts a handler before the default
 *   uncaught-exception handler: it writes the report synchronously, then hands the crash on, so
 *   Android still shows its dialog and ends the process. Under Robolectric (one JVM, many apps)
 *   only the app is remembered.
 * - [record] is also how the guarded jobs and `ErrorReporter` keep a handled error: written on
 *   the diagnostics thread, at most once a minute for the same place and class.
 * - [install] also reads, on the diagnostics thread, how the last runs ended ([ExitReasons]: a
 *   low-memory kill, an ANR, a native crash become reports).
 * - What was seen: the "diagnostics" preferences ("seenAt", the newest report the gallery's card
 *   was dismissed for; "exitSeenAt", the newest exit read).
 *
 * Never crashes and never blocks the main thread itself (every step is guarded); the one
 * synchronous write is the fatal report, as the process is ending anyway.
 */
object CrashLog {
    private const val TAG = "Brushwork"

    /** Reports kept (the oldest goes first). */
    const val MAX_REPORTS = 20

    /** The largest report, in bytes. */
    const val MAX_BYTES = 64 * 1024

    /** The reports' folder under `filesDir`. */
    internal const val DIR = "diagnostics/reports"

    /** The preferences file of what was seen. */
    internal const val PREFS = "diagnostics"
    internal const val SEEN_AT = "seenAt"
    internal const val EXIT_SEEN_AT = "exitSeenAt"

    /** Handled errors of the same place and class are kept at most once in this time (ms). */
    internal const val REPEAT_MS = 60_000L

    private val name = Regex("""^(\d{13})-([a-z_]+)(-quiet)?$""")

    @Volatile
    internal var app: Application? = null
        private set

    /** The app's version and build, read once at [install] ("1.9 (19) release"). */
    @Volatile
    private var appLine = "unknown"

    @Volatile
    private var debuggable = true

    /** Freed first when the crash is out of memory, so the report can still be written. */
    @Volatile
    private var reserve: ByteArray? = null

    @Volatile
    private var installed = false

    private val lock = Any()
    private val lastHandled = HashMap<String, Long>()

    /**
     * The diagnostics thread: one, made on demand, ended after 2 s idle (so no thread outlives
     * a test's app). Handled reports, the exit reasons and the process summary run on it.
     */
    private val executor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "brushwork-diagnostics").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    /** True in Robolectric's JVM (its fingerprint), where the uncaught handler is never JVM-wide. */
    internal val underRobolectric: Boolean get() = Build.FINGERPRINT == "robolectric"

    /** True for a debuggable build (and in tests): `ErrorReporter` lets errors through then. */
    internal val isDebuggable: Boolean get() = debuggable

    /** Called once from `BrushworkApp.onCreate`. */
    fun install(app: Application) {
        this.app = app
        try {
            debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 || underRobolectric
            appLine = appLine(app)
        } catch (e: Throwable) {
            Log.w(TAG, "crash log: no app info", e)
        }
        if (underRobolectric || installed) return
        installed = true
        reserve = ByteArray(128 * 1024)
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(Handler(previous))
        } catch (e: Throwable) {
            Log.w(TAG, "crash log: no handler", e)
        }
        // Step 2: how the last runs ended (read off the main thread), and this run's summary.
        background {
            ExitReasons.readSince(app)
            ExitReasons.publishSummary(app)
        }
    }

    /**
     * The handler put before the default one: the report first ([recordNow]), then [previous]
     * (Android's, which shows the dialog and ends the process); without one, the process ends.
     */
    internal class Handler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                reserve = null
                recordNow(e, "uncaught on thread ${t.name}", fatal = true)
            } catch (_: Throwable) {
                // The report is lost; the crash is still handed on.
            } finally {
                if (previous != null) {
                    previous.uncaughtException(t, e)
                } else {
                    Process.killProcess(Process.myPid())
                    System.exit(10)
                }
            }
        }
    }

    /**
     * Keeps [t] as a report: [fatal] (the app stops) is written now; a handled error is written on
     * the diagnostics thread, unless the same [where] and class was kept in the last minute.
     */
    fun record(t: Throwable, where: String, fatal: Boolean) {
        try {
            Log.e(TAG, "$where${if (fatal) " (fatal)" else ""}", t)
        } catch (_: Throwable) {
        }
        try {
            if (fatal) {
                recordNow(t, where, fatal = true)
                return
            }
            val key = "$where|${t.javaClass.name}"
            val now = System.currentTimeMillis()
            synchronized(lastHandled) {
                val last = lastHandled[key]
                if (last != null && now - last in 0 until REPEAT_MS) return
                lastHandled[key] = now
            }
            // The text is made here (the stack and the canvas as they are now), written there.
            val text = reportText(t, where, fatal = false, now)
            val kind = if (ReportText.isOutOfMemory(t)) CrashReport.Kind.OUT_OF_MEMORY else CrashReport.Kind.CRASH
            background { write(now, kind, quiet = true, text) }
        } catch (e: Throwable) {
            try { Log.w(TAG, "crash log: not recorded", e) } catch (_: Throwable) {}
        }
    }

    /** Writes the report of [t] synchronously (the fatal path; under [MAX_BYTES], about 5 ms). */
    internal fun recordNow(t: Throwable, where: String, fatal: Boolean) {
        val now = System.currentTimeMillis()
        val kind = if (ReportText.isOutOfMemory(t)) CrashReport.Kind.OUT_OF_MEMORY else CrashReport.Kind.CRASH
        val text = try {
            reportText(t, where, fatal, now)
        } catch (e: Throwable) {
            // Not even memory for the full text: the kind, the time and the class.
            "${ReportText.TITLE}\nKind: ${kind.name}\nFatal: ${yes(fatal)}\nTime: ${ReportText.utc(now)}\n" +
                "Summary: ${ReportText.kindTitle(kind, fatal)}: ${t.javaClass.name}\n"
        }
        write(now, kind, quiet = !fatal, text)
    }

    /** The report of [t] ([ReportText]): the fields of this run, the breadcrumbs and the stack. */
    internal fun reportText(t: Throwable, where: String, fatal: Boolean, now: Long): String {
        val kind = if (ReportText.isOutOfMemory(t)) CrashReport.Kind.OUT_OF_MEMORY else CrashReport.Kind.CRASH
        val editor = editorFacts()
        val names = editor?.names ?: emptyList()
        val fields = baseFields(kind, fatal, now, ReportText.scrub(where, names)) +
            listOfNotNull(editor?.let { "Canvas" to it.canvas }) +
            ("Summary" to ReportText.summary(kind, fatal, t, where))
        val sections = listOf(
            // Breadcrumbs hold no personal data by contract (ids, sizes, counts): kept as they are.
            "Breadcrumbs, oldest first" to Breadcrumbs.snapshot(),
            "Stack" to ReportText.stackLines(t, names),
        )
        return ReportText.compose(fields, sections, MAX_BYTES)
    }

    /** The fields every report starts with. */
    internal fun baseFields(kind: CrashReport.Kind, fatal: Boolean, time: Long, where: String): List<Pair<String, String>> {
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val native = runCatching { android.os.Debug.getNativeHeapAllocatedSize() / mb }.getOrDefault(-1L)
        // PSS from smaps_rollup (a few ms) from Android 10; older phones read every mapping: left out there.
        val pss = if (Build.VERSION.SDK_INT >= 29) runCatching { android.os.Debug.getPss() / 1024L }.getOrNull() else null
        return listOf(
            "Kind" to kind.name,
            "Fatal" to yes(fatal),
            "Time" to ReportText.utc(time),
            "Where" to where,
            "App" to appLine,
            "Device" to deviceLine(),
            "Memory" to "Java heap ${(rt.totalMemory() - rt.freeMemory()) / mb} of ${rt.maxMemory() / mb} MB, native heap $native MB" +
                (pss?.let { ", PSS $it MB" } ?: ""),
        )
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    private fun deviceLine(): String {
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "?"
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), $abi"
    }

    private fun appLine(app: Application): String {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return "${pi.versionName ?: "?"} ($code) ${if (debuggable) "debug" else "release"}"
    }

    /** What the open editor adds: its canvas's size, layer count and tool, and its name to scrub. */
    private class EditorFacts(val canvas: String, val names: List<String>)

    private fun editorFacts(): EditorFacts? = try {
        val c = ((app as? BrushworkApp)?.editorSession?.state as? EditorSession.State.Ready)?.controller
        c?.let {
            val doc = it.doc
            EditorFacts("${doc.width}x${doc.height}, ${doc.layers.size} layers, tool ${it.activeToolId.name}", listOf(doc.name))
        }
    } catch (e: Throwable) {
        null
    }

    // ------------------------------------------------------------------ files

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    /**
     * Writes [text] as the report of [time] and [kind] (a free name: a millisecond later when
     * taken), then keeps the newest [MAX_REPORTS]. Returns the report's id, or null.
     */
    internal fun write(time: Long, kind: CrashReport.Kind, quiet: Boolean, text: String): String? {
        val a = app ?: return null
        synchronized(lock) {
            return try {
                val d = dir(a)
                if (!d.isDirectory && !d.mkdirs()) return null
                var t = time
                var id: String
                var f: File
                do {
                    id = idOf(t, kind, quiet)
                    f = File(d, "$id.txt")
                    t++
                } while (f.exists())
                val bytes = text.toByteArray(Charsets.UTF_8)
                FileOutputStream(f).use { out ->
                    out.write(bytes, 0, minOf(bytes.size, MAX_BYTES))
                    runCatching { out.fd.sync() }
                }
                trim(d)
                id
            } catch (e: Throwable) {
                runCatching { Log.w(TAG, "crash log: not written", e) }
                null
            }
        }
    }

    /** The id of a report of [time] and [kind] ([quiet]: listed under Diagnostics only). */
    internal fun idOf(time: Long, kind: CrashReport.Kind, quiet: Boolean): String =
        "%013d-%s%s".format(java.util.Locale.ROOT, time, kind.name.lowercase(java.util.Locale.ROOT), if (quiet) "-quiet" else "")

    private fun trim(d: File) {
        val files = reportFiles(d)
        if (files.size <= MAX_REPORTS) return
        for (f in files.dropLast(MAX_REPORTS)) f.delete()
    }

    /** The report files in [d], oldest first (their names start with the time). */
    private fun reportFiles(d: File): List<File> =
        (d.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".txt") && name.matches(it.name.removeSuffix(".txt")) }
            .sortedBy { it.name }

    /** What a report's id says: its time, its kind and whether it is quiet; null for another file. */
    internal class Id(val time: Long, val kind: CrashReport.Kind, val quiet: Boolean)

    internal fun parseId(id: String): Id? {
        val m = name.matchEntire(id) ?: return null
        val kind = CrashReport.Kind.entries.firstOrNull { it.name.lowercase(java.util.Locale.ROOT) == m.groupValues[2] } ?: return null
        return Id(m.groupValues[1].toLong(), kind, m.groupValues[3].isNotEmpty())
    }

    private fun read(f: File): CrashReport? = try {
        val id = f.name.removeSuffix(".txt")
        val p = parseId(id)
        if (p == null) {
            null
        } else {
            val text = f.readText(Charsets.UTF_8)
            val summary = text.lineSequence().firstOrNull { it.startsWith("Summary: ") }?.removePrefix("Summary: ")
                ?: ReportText.kindTitle(p.kind)
            CrashReport(id, p.time, p.kind, summary, text)
        }
    } catch (e: Throwable) {
        null
    }

    /** Every report kept, newest first (reads up to [MAX_REPORTS] files: call it off the main thread). */
    fun reports(context: Context): List<CrashReport> = try {
        synchronized(lock) { reportFiles(dir(context)) }.asReversed().mapNotNull { read(it) }
    } catch (e: Throwable) {
        emptyList()
    }

    /** True when a report of this kind closes the app the user was using (the gallery's card). */
    internal fun showsCard(p: Id): Boolean = !p.quiet && p.kind != CrashReport.Kind.FREEZE

    /**
     * The newest report the gallery's card has not been dismissed for: a crash, a native crash,
     * an ANR or a low-memory kill in the foreground, newer than the one [markSeen] was last
     * called for. Lists the folder and reads one file (cheap enough for the gallery's first frame).
     */
    fun unseen(context: Context): CrashReport? = try {
        val seen = prefs(context).getLong(SEEN_AT, 0L)
        val f = synchronized(lock) { reportFiles(dir(context)) }.lastOrNull { f ->
            val p = parseId(f.name.removeSuffix(".txt"))
            p != null && showsCard(p) && p.time > seen
        }
        f?.let { read(it) }
    } catch (e: Throwable) {
        null
    }

    /** The gallery's card was dismissed for report [id]: it and every older one are seen. */
    fun markSeen(context: Context, id: String) {
        try {
            val p = parseId(id) ?: return
            val prefs = prefs(context)
            if (p.time > prefs.getLong(SEEN_AT, 0L)) prefs.edit().putLong(SEEN_AT, p.time).apply()
        } catch (e: Throwable) {
            runCatching { Log.w(TAG, "crash log: not marked", e) }
        }
    }

    /** [com.brushwork.paint.ui.common.StabilityLabels19.DELETE_REPORTS]. */
    fun deleteAll(context: Context) {
        try {
            synchronized(lock) { reportFiles(dir(context)).forEach { it.delete() } }
        } catch (e: Throwable) {
            runCatching { Log.w(TAG, "crash log: not deleted", e) }
        }
    }

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ the diagnostics thread

    /** Runs [block] on the diagnostics thread, guarded. */
    internal fun background(block: () -> Unit): Future<*>? = try {
        executor.submit {
            try {
                block()
            } catch (e: Throwable) {
                runCatching { Log.w(TAG, "diagnostics", e) }
            }
        }
    } catch (e: Throwable) {
        null
    }

    /** Tests: waits (at most [timeoutMs]) until the diagnostics thread has done what was asked so far. */
    internal fun drain(timeoutMs: Long = 5_000) {
        runCatching { background {}?.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** Tests: forgets the handled errors kept in the last minute (so the next is kept at once). */
    internal fun resetRepeats() = synchronized(lastHandled) { lastHandled.clear() }
}
