package com.brushwork.paint.engine.budget

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * v1.10 (F1 stub, design 3.3 and 2.12; body A10): the phone's [ExitInfoSource], from
 * `ActivityManager.getHistoricalProcessExitReasons` (API 30+; below it there is no record and
 * [exits] is empty). [attach] gives it the application (`EngineStartup`); before that it reports
 * no exit.
 *
 * A record maps to an [ExitRecord] with its timestamp, `lowMemory` for `REASON_LOW_MEMORY`,
 * `signaled9` for `REASON_SIGNALED` with status 9 (SIGKILL) and `foreground` for an importance of
 * `IMPORTANCE_FOREGROUND` or more important. Only this package's last [MAX_RECORDS] (8) exits are
 * read, newest first. Any thread off main (a binder call); a failure reads as no exit.
 */
object AndroidExitInfo : ExitInfoSource {
    /** The exits read at most. */
    const val MAX_RECORDS = 8

    @Volatile private var app: Context? = null

    /** Gives the source the application it reads the exits of (`EngineStartup`, once). */
    fun attach(context: Context) {
        app = context.applicationContext ?: context
    }

    override fun exits(sinceMillis: Long): List<ExitRecord> {
        val context = app ?: return emptyList()
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
                .filter { it.timestamp > sinceMillis }
                .map { recordOf(it) }
                .sortedByDescending { it.atMillis }
        } catch (e: Exception) {
            Log.w(TAG, "exit reasons", e)
            emptyList()
        }
    }

    override fun lmkReportsSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            ActivityManager.isLowMemoryKillReportSupported()
        } catch (e: Exception) {
            false
        }
    }

    /** The [ExitRecord] of one system record (API 30+). */
    @RequiresApi(30)
    internal fun recordOf(info: ApplicationExitInfo): ExitRecord = ExitRecord(
        atMillis = info.timestamp,
        lowMemory = info.reason == ApplicationExitInfo.REASON_LOW_MEMORY,
        signaled9 = info.reason == ApplicationExitInfo.REASON_SIGNALED && info.status == SIGKILL,
        foreground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    )

    private const val SIGKILL = 9
    private const val TAG = "BrushworkEngine"
}
