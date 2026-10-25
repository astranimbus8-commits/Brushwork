package com.brushwork.paint.engine

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.budget.AndroidExitInfo
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckNotices
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.engine.tiles.SwapJanitor

/**
 * v1.10 (F1, J32; signature frozen, A10 body): the engine's start, called from
 * `BrushworkApp.onCreate` (A10: the swap janitor's purge, the exit watch, the gallery budget of
 * `EngineConfig.applyIdle`). F1: nothing, so the app starts as v1.9.
 *
 * A10, on the main thread (cheap: a few small files): the exit source gets the application
 * ([AndroidExitInfo.attach]); the editor-open marker the last process left is taken
 * ([ExitWatch.takeMarker]) before an editor can be restored and write its own; the gallery's
 * budget from the stored check result ([CheckStore]; none stored, nothing changes); the
 * step-down's notice ([ExitWatch.noticeAtStart]) and the check's ([CheckNotices.atStart]) if one
 * was raised and never shown; and `onTrimMemory` stopping the check and deleting its marker. Then,
 * on a background thread and never under Robolectric (one JVM, many apps; the tests call
 * [atStart] themselves): the swap leftovers of earlier processes deleted ([SwapJanitor.purge]:
 * never a live session's directory, so an editor reopened meanwhile keeps its own), the closings
 * of the last process counted ([ExitWatch.atStart]) and, after them so that it carries their
 * step-down, the launch's look at an interrupted check ([AutoCheckRunner.atLaunch]).
 */
object EngineStartup {
    fun onAppCreate(app: Application) {
        val brush = app as? BrushworkApp ?: return
        AndroidExitInfo.attach(app)
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
        Thread({ atStart(brush, marker) }, "engine-start").apply { isDaemon = true }.start()
    }

    /** The background part of the start (its thread), with the [marker] taken on main. */
    internal fun atStart(app: BrushworkApp, marker: ExitWatch.Marker? = null) {
        try {
            SwapJanitor.purge(SwapJanitor.root(app))
        } catch (e: Exception) {
            Log.w(TAG, "swap purge", e)
        }
        try {
            ExitWatch.atStart(app, marker)
        } catch (e: Exception) {
            Log.w(TAG, "exit watch", e)
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
