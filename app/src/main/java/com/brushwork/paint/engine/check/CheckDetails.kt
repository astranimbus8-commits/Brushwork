package com.brushwork.paint.engine.check

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineStat
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.StatSummary
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.storage.engine.EngineState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.10 (A10, design 2.11, LA1 b): the text `EngineLabels110.COPY_DETAILS` puts on the clipboard:
 * the layer storage choice, the level and budgets running now, the check's numbers (level and
 * reason, the memory it saw, J, the probe bytes, the Deflate, write and read speeds, the free
 * space, the composite and its switches, the step-down, the low-memory closings and the date), the
 * engine state's flags, and the `EngineStats` medians and 95th percentiles (no lines while none are
 * kept). Plain English for a bug report or the device checks; it is never drawn as a screen (the
 * on-screen list is step 7's), so its words live here and not in `ui/engine` (label rule 4). Sizes
 * in MiB and GiB, speeds in MB/s (10^6 bytes, as M3 and M4 measure them), dates in the phone's zone.
 */
object CheckDetails {
    private const val MIB = 1L shl 20
    private const val GIB = 1L shl 30

    /** The clip's label (the system shows it on some phones). */
    const val CLIP_LABEL = "Brushwork engine check"

    /** Puts [text] on the clipboard; true when it is there. Android 13 and later confirm the copy themselves. */
    fun copy(context: Context, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        true
    } catch (e: Exception) {
        Log.w(TAG, "details not copied", e)
        false
    }

    /** [lines], one per line. */
    fun text(mode: LayerStorageMode, level: EngineLevel, budgets: Budgets, check: EngineCheckResult?, state: EngineState, stats: List<StatSummary>): String =
        lines(mode, level, budgets, check, state, stats).joinToString("\n")

    /** The details as lines: [mode] chosen, [level] and [budgets] running now, the stored [check], [state], [stats]. */
    fun lines(mode: LayerStorageMode, level: EngineLevel, budgets: Budgets, check: EngineCheckResult?, state: EngineState, stats: List<StatSummary>): List<String> {
        val out = mutableListOf(CLIP_LABEL)
        out += "Setting: ${mode.name.lowercase()}"
        out += "Running now: ${level.name}, R ${mib(budgets.nativeBudget)}, J ${mib(budgets.javaMax)}, " +
            "memory ${if (budgets.known) "known" else "unknown"}, step-down ${budgets.stepDown}"
        if (check == null) {
            out += "Check: not run yet"
        } else {
            val reason = if (check.reason.isEmpty()) "" else " (${check.reason})"
            val provisional = if (check.provisional) ", provisional (${check.inconclusiveRuns} in a row)" else ""
            out += "Check: ${check.level.name}$reason$provisional"
            out += "Ran: ${date(check.ranAtMillis)} in ${check.durationMs} ms, version ${check.appVersionCode}, ${check.deviceKey}"
            out += "Memory: total ${mib(check.totalMem)}, available ${mib(check.availMem)}, threshold ${mib(check.threshold)}, " +
                "J ${mib(check.javaMax)}, low-RAM ${yes(check.lowRam)}, 64-bit ${yes(check.is64Bit)}"
            out += "Native probe: ${mib(check.nativeProbeBytes)}${if (check.m2Outcome.isEmpty()) "" else " (${check.m2Outcome})"}"
            out += "Deflate: ${speed(check.deflateMBs)}, inflate ${speed(check.inflateMBs)}, ratio ${"%.2f".format(Locale.US, check.ratio)}"
            out += "Storage: write ${speed(check.writeMBs)}, read ${speed(check.readMBs)}, free ${gib(check.freeBytes)}"
            out += "Composite of 64 layers: ${"%.1f".format(Locale.US, check.composite64Ms)} ms, stroke cache from " +
                "${check.belowCacheMinLayers?.let { "$it layers" } ?: "off"}, above projection ${if (check.aboveProjection) "on" else "off"}"
            val closings = if (check.lmkDeathsAt.isEmpty()) "none" else check.lmkDeathsAt.joinToString { date(it) }
            out += "Step-down: ${check.stepDown}; low-memory closings: $closings"
        }
        out += "Check pending: ${yes(state.engineCheckPending)}; slow swap reads: ${yes(state.slowSwapReads)}"
        if (stats.isNotEmpty()) {
            out += "Timings, p50 / p95 of the last values:"
            for (s in stats) out += "  ${s.stat.name}: ${value(s.stat, s.p50)} / ${value(s.stat, s.p95)} (${s.n})"
        }
        return out
    }

    /** A stat's value in its unit: bytes as MiB for [EngineStat.LEDGER_PEAK], ms otherwise. */
    private fun value(stat: EngineStat, v: Float): String =
        if (stat == EngineStat.LEDGER_PEAK) mib(v.toLong()) else "%.1f ms".format(Locale.US, v)

    private fun mib(bytes: Long) = "${bytes / MIB} MiB"

    private fun gib(bytes: Long) = "%.1f GiB".format(Locale.US, bytes.toDouble() / GIB)

    private fun speed(mbs: Float) = "%.0f MB/s".format(Locale.US, mbs)

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    private fun date(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

    private const val TAG = "BrushworkEngine"
}
