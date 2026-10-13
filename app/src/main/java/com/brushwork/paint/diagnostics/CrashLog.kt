package com.brushwork.paint.diagnostics

import android.app.Application
import android.content.Context
import android.util.Log

/** v1.9 (R9, design 2.4.13; owner A7a): one report of the crash log. */
data class CrashReport(val id: String, val timeMillis: Long, val kind: Kind, val summary: String, val text: String) {
    enum class Kind { CRASH, OUT_OF_MEMORY, FREEZE, NATIVE, ANR, LOW_MEMORY_KILL }
}

/**
 * v1.9 (R9, design 2.4.13; owner A7a): the on-device crash log. F1b: the seam; nothing is kept
 * (no reports, nothing unseen), and [record] only logs.
 */
@Suppress("UNUSED_PARAMETER")
object CrashLog {
    private const val TAG = "Brushwork"

    /** Called once from `BrushworkApp.onCreate`. F1b: no-op. */
    fun install(app: Application) {}

    /** F1b: Log.e only. */
    fun record(t: Throwable, where: String, fatal: Boolean) {
        Log.e(TAG, "$where${if (fatal) " (fatal)" else ""}", t)
    }

    /** F1b: empty. */
    fun reports(context: Context): List<CrashReport> = emptyList()

    /** F1b: null. */
    fun unseen(context: Context): CrashReport? = null

    /** F1b: no-op. */
    fun markSeen(context: Context, id: String) {}

    /** F1b: no-op. */
    fun deleteAll(context: Context) {}
}
