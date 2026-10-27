package com.brushwork.paint.engine

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.budget.AndroidExitInfo
import com.brushwork.paint.engine.budget.AndroidMemoryProbe
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckNotices
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.engine.tiles.SwapJanitor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.10 (F1, J32; signature frozen, A10 body): the engine's start, called from
 * `BrushworkApp.onCreate` (A10: the swap janitor's purge, the exit watch, the gallery budget of
 * `EngineConfig.applyIdle`). F1: nothing, so the app starts as v1.9.
 *
 * A10, on the main thread (cheap: a few small files): the exit source gets the application
 * ([AndroidExitInfo.attach]; Gate 2, O6: the memory probe too, [AndroidMemoryProbe.attach]); the
 * editor-open marker the last process left is taken
 * ([ExitWatch.takeMarker]) before an editor can be restored and write its own; the gallery's
 * budget from the stored check result ([CheckStore]; none stored, nothing changes); the
 * step-down's notice ([ExitWatch.noticeAtStart]) and the check's ([CheckNotices.atStart]) if one
 * was raised and never shown; and `onTrimMemory` stopping the check and deleting its marker. Then,
 * on a background thread and never under Robolectric (one JVM, many apps; the tests call
 * [atStart] themselves): the closings of the last process counted ([ExitWatch.atStart]), the swap
 * leftovers of earlier processes deleted ([SwapJanitor.purge]: never a live session's directory,
 * so an editor reopened meanwhile keeps its own) and, after the closings so that it carries their
 * step-down, the launch's look at an interrupted check ([AutoCheckRunner.atLaunch]). The gallery's
 * check waits for the closings too ([awaitClosingsCounted]): a run that read the stored result
 * before [ExitWatch] counted a closing would write it back without that closing's step-down.
 */
object EngineStartup {
    /** The gallery's check waits at most this long for the start to count the closings. */
    const val CLOSINGS_WAIT_MS = 10_000L

    /**
     * Open while the start's background part has not counted the last process's closings yet; null
     * when no such part runs (under Robolectric, unless a test sets one). Any thread.
     */
    @Volatile
    internal var closingsCounted: CountDownLatch? = null

    /**
     * The gallery's check, on its own thread before its run: waits until the start counted the
     * closings (at most [CLOSINGS_WAIT_MS]); returns at once when they are counted or nothing
     * counts them. Never on main.
     */
    internal fun awaitClosingsCounted() {
        val gate = closingsCounted ?: return
        try {
            if (!gate.await(CLOSINGS_WAIT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "the closings are not counted yet; the check runs anyway")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun onAppCreate(app: Application) {
        val brush = app as? BrushworkApp ?: return
        AndroidExitInfo.attach(app)
        // v1.10 Gate 2 (O6): the memory probe reads the application's ActivityManager, not the process' current one.
        AndroidMemoryProbe.attach(app)
        val marker = runCatching { ExitWatch.takeMarker(app) }.getOrNull()
        val check = CheckStore.read(app)
        val mode = brush.settings.layerStorageMode
        if (check != null) EngineConfig.applyIdle(mode, check, Budgets.compute(EngineConfig.memoryProbe, check.stepDown))
        // The notices tell the check's choice, not the slow-reads fallback of CheckStore.read.
        val stored = if (check == null) null else CheckStore.readStored(app)
        ExitWatch.noticeAtStart(app, stored, mode)
        CheckNotices.atStart(app, stored, mode)
        app.registerComponentCallbacks(TrimCallbacks(brush))
        if (Build.FINGERPRINT == "robolectric") return
        closingsCounted = CountDownLatch(1)
        Thread({ atStart(brush, marker) }, "engine-start").apply { isDaemon = true }.start()
    }

    /**
     * The background part of the start (its thread), with the [marker] taken on main: the closings
     * (then [closingsCounted] opens, whatever happened), the swap purge, the interrupted check.
     */
    internal fun atStart(app: BrushworkApp, marker: ExitWatch.Marker? = null) {
        try {
            ExitWatch.atStart(app, marker)
        } catch (e: Exception) {
            Log.w(TAG, "exit watch", e)
        } finally {
            closingsCounted?.countDown()
        }
        try {
            SwapJanitor.purge(SwapJanitor.root(app))
        } catch (e: Exception) {
            Log.w(TAG, "swap purge", e)
        }
        try {
            AutoCheckRunner.of(app).atLaunch()
        } catch (e: Exception) {
            Log.w(TAG, "engine start", e)
        }
    }

    /** `onTrimMemory`: the check stops and its marker goes (design 2.10). */
    private class TrimCallbacks(private val app: BrushworkApp) : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = AutoCheckRunner.of(app).onTrimMemory()

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = AutoCheckRunner.of(app).onTrimMemory()
    }

    private const val TAG = "BrushworkEngine"
}
