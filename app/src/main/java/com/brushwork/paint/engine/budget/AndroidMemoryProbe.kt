package com.brushwork.paint.engine.budget

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import android.os.Process
import kotlin.math.max

/**
 * v1.10 (F1 stub, design 3.3; body A7): the phone's [MemoryProbe], over
 * `ActivityManager.MemoryInfo` (total, available, threshold, low), `isLowRamDevice`,
 * `Process.is64Bit` and the process' own heaps.
 *
 * It needs the application's context: the one given to [attach] (`BrushworkApp` at start), else
 * the process' current application. Without either, and under Robolectric, whose `MemoryInfo` is
 * all zero, the snapshot reports `totalMem == 0`, so `Budgets.compute` gives `Budgets.v19` (J3)
 * and the engine resolves to CLASSIC with v1.9's numbers (I32).
 */
object AndroidMemoryProbe : MemoryProbe {
    /** The application context [attach] gave. */
    @Volatile
    private var app: Context? = null

    /** Gives the probe the application's context (`BrushworkApp.onCreate`); any thread. */
    fun attach(context: Context) {
        app = context.applicationContext ?: context
    }

    override fun snapshot(): MemSnapshot {
        val am = activityManager() ?: return UNKNOWN
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val rt = Runtime.getRuntime()
        return MemSnapshot(
            totalMem = info.totalMem,
            availMem = info.availMem,
            threshold = info.threshold,
            lowMemory = info.lowMemory,
            isLowRamDevice = am.isLowRamDevice,
            is64Bit = Process.is64Bit(),
            javaMax = rt.maxMemory(),
            javaUsed = rt.totalMemory() - rt.freeMemory(),
            nativeAllocated = Debug.getNativeHeapAllocatedSize(),
        )
    }

    /**
     * M2 (section 2.10): allocates [stepBytes] bitmaps one after another, touching every pixel,
     * until [capBytes] would be passed, `availMem` falls below [stopBelowAvail], or an allocation
     * throws; then frees them all. Returns the bytes it held at the end; 0 without a context (it
     * cannot read `availMem`, so it allocates nothing). Off main (the check's thread).
     */
    override fun probeNative(stepBytes: Long, stopBelowAvail: Long, capBytes: Long): Long {
        if (stepBytes <= 0L || capBytes < stepBytes) return 0L
        val am = activityManager() ?: return 0L
        val info = ActivityManager.MemoryInfo()
        val held = ArrayList<Bitmap>()
        var reached = 0L
        try {
            while (reached + stepBytes <= capBytes) {
                am.getMemoryInfo(info)
                if (info.availMem < stopBelowAvail) break
                val b = touched(stepBytes)
                held += b
                reached += b.byteCount
            }
        } catch (e: OutOfMemoryError) {
            // The native heap gave out first: what is held is the reach.
        } catch (e: RuntimeException) {
            // The platform refused the bitmap (too large): the same.
        } finally {
            for (b in held) b.recycle()
        }
        return reached
    }

    /** A bitmap of about [bytes] with every pixel written, so its pages are really taken. */
    private fun touched(bytes: Long): Bitmap {
        val h = max(1L, bytes / 4 / PROBE_WIDTH).coerceAtMost(Int.MAX_VALUE.toLong() / PROBE_WIDTH).toInt()
        return Bitmap.createBitmap(PROBE_WIDTH, h, Bitmap.Config.ARGB_8888).apply { eraseColor(PROBE_COLOR) }
    }

    /** The system's ActivityManager through [app] or the current application; null without one. */
    private fun activityManager(): ActivityManager? {
        val context = app ?: currentApplication() ?: return null
        return context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    /** The process' application (`ActivityThread.currentApplication`), for a probe used before [attach]; null when it cannot be read. */
    private fun currentApplication(): Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: LinkageError) {
        null
    }

    /** What the probe reports without a context: nothing known (J3). */
    private val UNKNOWN = MemSnapshot(0, 0, 0, false, false, false, 0, 0, 0)

    /** The probe bitmaps' width; the height follows from the step. */
    private const val PROBE_WIDTH = 1024

    /** A colour no blank page holds, so every page is written. */
    private const val PROBE_COLOR = 0xFF808080.toInt()
}
