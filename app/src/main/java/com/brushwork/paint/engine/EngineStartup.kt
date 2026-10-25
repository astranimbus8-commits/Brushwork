package com.brushwork.paint.engine

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckStore

/**
 * v1.10 (F1, J32; signature frozen, A10 body): the engine's start, called from
 * `BrushworkApp.onCreate` (A10: the swap janitor's purge, the exit watch, the gallery budget of
 * `EngineConfig.applyIdle`). F1: nothing, so the app starts as v1.9.
 *
 * A10, on the main thread (cheap: one small file): the gallery's budget from the stored check
 * result ([CheckStore]; none stored, nothing changes), and `onTrimMemory` stopping the check and
 * deleting its marker. Then, on a background thread and never under Robolectric (one JVM, many
 * apps; the tests call the parts themselves): the launch's look at an interrupted check
 * ([AutoCheckRunner.atLaunch]).
 */
object EngineStartup {
    fun onAppCreate(app: Application) {
        val brush = app as? BrushworkApp ?: return
        val check = CheckStore.read(app)
        if (check != null) EngineConfig.applyIdle(brush.settings.layerStorageMode, check, Budgets.compute(EngineConfig.memoryProbe, check.stepDown))
        app.registerComponentCallbacks(TrimCallbacks(brush))
        if (Build.FINGERPRINT == "robolectric") return
        Thread({ atStart(brush) }, "engine-start").apply { isDaemon = true }.start()
    }

    /** The background part of the start (its thread). */
    internal fun atStart(app: BrushworkApp) {
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
