package com.brushwork.paint.engine

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.ExitInfoSource
import com.brushwork.paint.engine.budget.ExitRecord
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.storage.engine.EngineStateStore
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * v1.10 (A10, design 2.12): the low-memory closings of the app while an editor was open, and the
 * step-down they cause.
 *
 * **The marker.** While an editor shows, `noBackupFilesDir/editor-open` ([MARKER_FILE]) holds the
 * level, the layer count, the canvas size, whether the editor is in the foreground (since when)
 * and `Settings.Global.BOOT_COUNT` ([Marker]); the editor's chrome keeps it current at
 * `ON_START`, `ON_STOP` and layer changes ([editorShown]) and deletes it when the editor closes
 * ([editorClosed]), so a marker found at the next start means the process ended with an editor
 * open. The writes run in order on one background thread. [takeMarker] reads and deletes it at
 * the app's start, on main, before an editor can be restored and write a new one.
 *
 * **Closings** ([atStart], on the start's background thread, with the marker taken):
 * - API 30+: the app's exits newer than `lastExitSeen` (`engine-state.json`, then advanced to the
 *   newest) are read; the oldest one at or after the time the marker's editor came to the
 *   foreground is its process's end. A **sure** closing is one with `REASON_LOW_MEMORY`. When the
 *   phone does not report low-memory kills (`isLowMemoryKillReportSupported` false), a SIGKILL in
 *   the foreground is a **probable** closing. A marker in the background counts nothing: a
 *   background kill is normal.
 * - API 26-29 (no exit records): a marker in the foreground with the same boot count as now and
 *   no crash report written since it came to the foreground is a **probable** closing. Another
 *   boot count (a reboot, a battery death) counts nothing.
 *
 * **Counting.** A sure closing counts at once. Probable closings are kept for 30 days
 * ([CLOSINGS_FILE]): the first in that window is free, and from the second on each one counts
 * (a force stop or one odd exit never steps down). A counted closing is added to the stored
 * check's `lmkDeathsAt` (kept to the last 30 days) and the step-down becomes the count of those
 * closings, at most 2, never less than it was:
 * - `stepDown = 1` (R × 0.8 at the next open); the check re-runs at the gallery, since a closing
 *   is newer than the result (`AutoCheckRunner`'s step-down trigger: `ranAtMillis` is kept).
 * - `stepDown = 2` (R × 0.64): the stored result becomes CLASSIC for [CheckReasons.LOW_MEMORY]
 *   (final), a chosen Full becomes Automatic (which then resolves to Classic; a chosen Classic
 *   stays), and the gallery shows `EngineLabels110.LMK_NOTICE` once ([EngineNotice.LowMemoryClosings];
 *   raised again at the next start if it never showed, [noticeAtStart]). Later closings keep 2
 *   and change nothing else.
 * The gallery's budget follows at once (`EngineConfig.applyIdle`, on main). Nothing is counted
 * without a stored check result (none ran yet, or memory is unknown), and a restored phone has no
 * marker (it lives in `noBackupFilesDir`), so nothing is misread there.
 */
object ExitWatch {
    /** The editor-open marker, in `noBackupFilesDir`. */
    const val MARKER_FILE = "editor-open"

    /** The probable closings of the last 30 days and the step-down notice's time, in `noBackupFilesDir`. */
    const val CLOSINGS_FILE = "engine-closings.txt"

    /** Closings are counted within this window (30 days). */
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** The marker's contents (plain text, one `key value` line per field: [encode], [decode]). */
    data class Marker(
        val level: EngineLevel,
        val layers: Int,
        val width: Int,
        val height: Int,
        val foreground: Boolean,
        val bootCount: Int,
        val sinceMillis: Long,
        val updatedAt: Long,
    )

    /** What [atStart] found. */
    enum class Closing { NONE, SURE, PROBABLE }

    /** What [atStart] reads and does outside its files (tests pass their own). */
    internal class Env(
        val api: Int,
        val bootCount: Int,
        val exits: ExitInfoSource,
        val mode: () -> LayerStorageMode,
        val setMode: (LayerStorageMode) -> Unit,
        val onMain: (Runnable) -> Unit,
    )

    /**
     * Tests: under Robolectric (one JVM, many apps) the editor's chrome writes no marker unless a
     * test sets this (reset it in `finally`).
     */
    @Volatile
    internal var runUnderRobolectric = false

    /** The editor's chrome keeps the marker: on a phone always, under Robolectric only when a test asks. */
    val markerRuns: Boolean get() = runUnderRobolectric || Build.FINGERPRINT != "robolectric"

    /** Where the marker writes run, in order (tests put a direct executor here). */
    @Volatile
    internal var writer: Executor = Executor { r -> defaultWriter.execute(r) }

    private val defaultWriter by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "exit-watch").apply { isDaemon = true } }
    }

    /** The marker this process last wrote (null: none, or the editor closed). */
    @Volatile
    private var current: Marker? = null

    /** The marker file. */
    fun markerFile(context: Context): File = File(context.noBackupFilesDir, MARKER_FILE)

    private fun closingsFile(context: Context): File = File(context.noBackupFilesDir, CLOSINGS_FILE)

    // ---------------------------------------------------------------- the marker (the editor's chrome)

    /**
     * The editor shows (or changed): writes the marker with [level], [layers], the canvas size and
     * [foreground]; the foreground time restarts only when [foreground] changes. Main; the write
     * runs on the marker thread.
     */
    fun editorShown(context: Context, level: EngineLevel, layers: Int, width: Int, height: Int, foreground: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        val app = context.applicationContext ?: context
        val last = current
        val since = if (last != null && last.foreground == foreground) last.sinceMillis else nowMillis
        val marker = Marker(level, layers, width, height, foreground, readBootCount(app), since, nowMillis)
        if (marker == last) return
        current = marker
        writer.execute {
            try {
                val f = markerFile(app)
                f.parentFile?.mkdirs()
                val bytes = encode(marker).toByteArray(Charsets.UTF_8)
                ProjectFormat.writeAtomically(f) { it.write(bytes) }
            } catch (e: Exception) {
                Log.w(TAG, "editor marker", e)
            }
        }
    }

    /** The editor closed: the marker goes (after the writes before it). Main. */
    fun editorClosed(context: Context) {
        val app = context.applicationContext ?: context
        current = null
        writer.execute { markerFile(app).delete() }
    }

    /** The app's start, on main: the marker the last process left (null: none, or unreadable), deleted. */
    fun takeMarker(context: Context): Marker? {
        current = null
        val f = markerFile(context)
        if (!f.exists()) return null
        val marker = runCatching { decode(f.readText()) }.getOrNull()
        f.delete()
        return marker
    }

    /** The marker file's text: one `key value` line per field of [marker]. */
    internal fun encode(marker: Marker): String = buildString {
        append("level ").append(marker.level.name).append('\n')
        append("layers ").append(marker.layers).append('\n')
        append("size ").append(marker.width).append(' ').append(marker.height).append('\n')
        append("foreground ").append(if (marker.foreground) 1 else 0).append('\n')
        append("boot ").append(marker.bootCount).append('\n')
        append("since ").append(marker.sinceMillis).append('\n')
        append("updated ").append(marker.updatedAt).append('\n')
    }

    /** The [Marker] of [text] ([encode]'s lines, in any order); null when a field is missing or unreadable. */
    internal fun decode(text: String): Marker? {
        val fields = text.lines().mapNotNull { line ->
            val t = line.trim()
            val space = t.indexOf(' ')
            if (space <= 0) null else t.substring(0, space) to t.substring(space + 1).trim()
        }.toMap()
        val level = EngineLevel.entries.firstOrNull { it.name == fields["level"] } ?: return null
        val size = fields["size"]?.split(' ')?.filter { it.isNotEmpty() }
        val foreground = when (fields["foreground"]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return Marker(
            level = level,
            layers = fields["layers"]?.toIntOrNull() ?: return null,
            width = size?.getOrNull(0)?.toIntOrNull() ?: return null,
            height = size.getOrNull(1)?.toIntOrNull() ?: return null,
            foreground = foreground,
            bootCount = fields["boot"]?.toIntOrNull() ?: return null,
            sinceMillis = fields["since"]?.toLongOrNull() ?: return null,
            updatedAt = fields["updated"]?.toLongOrNull() ?: return null,
        )
    }

    /** `Settings.Global.BOOT_COUNT` (-1 when the phone does not say; the settings cache it). */
    private fun readBootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    // ---------------------------------------------------------------- the start

    /** The start's background part for [app] with the [marker] [takeMarker] returned (`EngineStartup`). */
    fun atStart(app: BrushworkApp, marker: Marker?) {
        val main = Handler(Looper.getMainLooper())
        atStart(
            app, marker,
            Env(
                api = Build.VERSION.SDK_INT,
                bootCount = readBootCount(app),
                exits = EngineConfig.exitInfo,
                mode = { app.settings.layerStorageMode },
                setMode = { app.settings.layerStorageMode = it },
                onMain = { main.post(it) },
            ),
        )
    }

    /** The class KDoc's closings and counting. Blocking (small files and one binder call); never on main. */
    internal fun atStart(context: Context, marker: Marker?, env: Env): Closing {
        val (closing, at) = closingOf(context, marker, env)
        if (closing == Closing.NONE) return closing
        val counted = closing == Closing.SURE || probableCounts(context, at)
        Log.i(TAG, "editor closed for memory ($closing), counted: $counted")
        if (counted) count(context, at, env)
        return closing
    }

    /** What ended the marker's process, and when (the exit's time; below API 30 the marker's last write). */
    private fun closingOf(context: Context, marker: Marker?, env: Env): Pair<Closing, Long> {
        val none = Closing.NONE to 0L
        if (env.api >= 30) {
            val state = EngineStateStore.read(context)
            val exits = runCatching { env.exits.exits(state.lastExitSeen) }.getOrDefault(emptyList())
            val newest = exits.maxOfOrNull { it.atMillis } ?: return none
            if (newest > state.lastExitSeen) EngineStateStore.write(context, EngineStateStore.read(context).copy(lastExitSeen = newest))
            if (marker == null || !marker.foreground) return none
            val end: ExitRecord = exits.filter { it.atMillis >= marker.sinceMillis }.minByOrNull { it.atMillis } ?: return none
            val closing = when {
                end.lowMemory -> Closing.SURE
                !runCatching { env.exits.lmkReportsSupported() }.getOrDefault(true) && end.signaled9 && end.foreground -> Closing.PROBABLE
                else -> Closing.NONE
            }
            return closing to end.atMillis
        }
        if (marker == null || !marker.foreground) return none
        if (marker.bootCount < 0 || marker.bootCount != env.bootCount) return none
        if (CrashLog.closedAppSince(context, marker.sinceMillis)) return none
        return Closing.PROBABLE to marker.updatedAt
    }

    /** Keeps the probable closing at [at]; true when it is the second or later within 30 days. */
    private fun probableCounts(context: Context, at: Long): Boolean {
        val c = readClosings(context)
        val recent = (c.probable.filter { it > at - WINDOW_MS } + at).distinct().sorted()
        writeClosings(context, c.copy(probable = recent))
        return recent.size >= 2
    }

    /** Counts a closing at [at]: the stored check's closings, its step-down, and at 2 the rest of the class KDoc. */
    private fun count(context: Context, at: Long, env: Env) {
        val stored = CheckStore.readStored(context) ?: return
        val deaths = (stored.lmkDeathsAt + at).filter { it > at - WINDOW_MS }.distinct().sorted()
        val stepDown = maxOf(stored.stepDown, minOf(deaths.size, 2))
        val toTwo = stepDown >= 2 && stored.stepDown < 2
        var result = stored.copy(stepDown = stepDown, lmkDeathsAt = deaths)
        if (toTwo) result = result.copy(level = EngineLevel.CLASSIC, reason = CheckReasons.LOW_MEMORY, provisional = false)
        CheckStore.write(context, result)
        if (toTwo) {
            writeClosings(context, readClosings(context).copy(noticeAt = at))
            val prefs = UiPrefs(context)
            if (prefs.engineNoticeShownAt < result.ranAtMillis) prefs.engineNoticeShownAt = result.ranAtMillis
        }
        env.onMain(Runnable {
            if (toTwo) {
                val mode = env.mode()
                if (mode == LayerStorageMode.FULL) env.setMode(LayerStorageMode.AUTOMATIC)
                if (mode != LayerStorageMode.CLASSIC) EngineNotices.current = EngineNotice.LowMemoryClosings
            }
            EngineConfig.applyIdle(env.mode(), result, Budgets.compute(EngineConfig.memoryProbe, result.stepDown))
        })
    }

    /**
     * At the app's start, on main, before the check's notice ([com.brushwork.paint.engine.check.CheckNotices.atStart]):
     * raises the step-down's notice again when it was raised and never shown.
     */
    fun noticeAtStart(context: Context, stored: EngineCheckResult?, mode: LayerStorageMode) {
        if (stored == null || stored.stepDown < 2 || mode == LayerStorageMode.CLASSIC || EngineNotices.current != null) return
        val noticeAt = readClosings(context).noticeAt
        if (noticeAt > 0L && noticeAt > UiPrefs(context).engineLmkNoticeShownAt) EngineNotices.current = EngineNotice.LowMemoryClosings
    }

    // ---------------------------------------------------------------- the closings file

    /** The probable closings kept and the time of the step-down's notice (0: none). */
    private data class Closings(val probable: List<Long> = emptyList(), val noticeAt: Long = 0L)

    /** Plain text: `p <millis>` per probable closing, `n <millis>` for the notice; an unreadable line is skipped. */
    private fun readClosings(context: Context): Closings {
        val f = closingsFile(context)
        if (!f.isFile) return Closings()
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        val probable = lines.mapNotNull { l -> l.trim().takeIf { it.startsWith("p ") }?.substring(2)?.trim()?.toLongOrNull() }
        val notice = lines.firstNotNullOfOrNull { l -> l.trim().takeIf { it.startsWith("n ") }?.substring(2)?.trim()?.toLongOrNull() } ?: 0L
        return Closings(probable, notice)
    }

    private fun writeClosings(context: Context, c: Closings) {
        val f = closingsFile(context)
        f.parentFile?.mkdirs()
        val text = buildString {
            c.probable.forEach { append("p ").append(it).append('\n') }
            if (c.noticeAt > 0L) append("n ").append(c.noticeAt).append('\n')
        }
        ProjectFormat.writeAtomically(f) { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private const val TAG = "BrushworkEngine"
}
