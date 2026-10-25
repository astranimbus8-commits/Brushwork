package com.brushwork.paint.ui.engine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Window
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineStats
import com.brushwork.paint.engine.ExitWatch
import com.brushwork.paint.engine.NativeWatch
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v1.10 (F1, J32, design 2.7 and 2.8; signature frozen, A10 body): the engine's chrome over the
 * editor (`EditorScreen`): the ring while a layer is made ready (`EditorController.activating`),
 * the low-storage message, the frame metrics. F1: nothing.
 *
 * A10: the session's swap store. Once the editor shows a session that runs at FULL, a
 * [SlabSwapStore] becomes `EngineConfig.swapStore` ([SlabSwapStore.installIfFull]; at CLASSIC and
 * TILES, with a store already installed, or under Robolectric nothing happens); the session's end
 * (`EngineConfig.closeSession`) closes it and deletes its directory. Installed here and not in
 * `EditorSession` (region `v110-A10 session`) until A11's merge (design 4.0, `$Sequential`). With a
 * store installed, [LowStorageNotice] shows [EngineLabels110.LOW_STORAGE] once in the session when
 * the store reads less than 1.5 GiB free (at the start, then every 60 s).
 *
 * A10: the activation ring (design 2.7). While a layer is made ready asynchronously, canvas input
 * is held without a toast; when that lasts [RING_DELAY_MS] (250 ms), the pill slot (under the
 * options strip, `ChromeLayout.pillTop`) shows a small ring with [EngineLabels110.PREPARING_LAYER]
 * until the layer is ready. A quicker activation shows nothing.
 *
 * A10: the safety nets (design 2.12) and the measurements (section 7). While the editor shows,
 * the editor-open marker of [ExitWatch] is written at once and kept current at `ON_START`,
 * `ON_STOP` and each change of the layer count or the canvas size (a stroke writes nothing), and
 * deleted when the editor goes; while it is started,
 * [NativeWatch] ticks every 2 s with the memory read off main; and the window's frame metrics
 * feed `EngineStats` (each UI frame's total duration, on a background thread). Each runs on a
 * phone and, under Robolectric, only when a test asks (`ExitWatch.markerRuns`,
 * `NativeWatch.watchRuns`, `EngineStats.keepUnderRobolectric`). The marker and the watch live
 * here and not in `EditorSession` (region `v110-A10 session`) until A11's merge.
 */
@Composable
fun EngineEditorChrome(controller: EditorController) {
    val context = LocalContext.current
    LaunchedEffect(controller) {
        SlabSwapStore.installIfFull(context.applicationContext)
        LowStorageNotice.watch { controller.toast(it) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (ExitWatch.markerRuns) EditorMarker(controller, lifecycle)
    if (NativeWatch.watchRuns) {
        LaunchedEffect(controller, lifecycle) {
            NativeWatch.reset()
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(NativeWatch.WATCH_MS)
                    val snap = withContext(Dispatchers.IO) { runCatching { EngineConfig.memoryProbe.snapshot() }.getOrNull() } ?: continue
                    NativeWatch.tick(snap)
                }
            }
        }
    }
    if (EngineStats.keepUnderRobolectric || Build.FINGERPRINT != "robolectric") FrameTimes(context)

    val activating = controller.activating
    var ring by remember { mutableStateOf(false) }
    LaunchedEffect(activating) {
        ring = false
        if (activating != null) {
            delay(RING_DELAY_MS)
            ring = true
        }
    }
    if (activating != null && ring) PreparingRing()
}

/**
 * The editor-open marker ([ExitWatch]): written when the editor shows and at each `ON_START`
 * (foreground), `ON_STOP` (background) and change of the layer count or the canvas size (never per
 * stroke); deleted when the editor goes.
 */
@Composable
private fun EditorMarker(controller: EditorController, lifecycle: Lifecycle) {
    val context = LocalContext.current.applicationContext
    DisposableEffect(controller, lifecycle) {
        fun write() {
            val doc = controller.doc
            ExitWatch.editorShown(context, EngineConfig.level, doc.layers.size, doc.width, doc.height, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) write()
        }
        write()
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ExitWatch.editorClosed(context)
        }
    }
    LaunchedEffect(controller, lifecycle) {
        // Every committed edit bumps layersVersion; only a new layer count or canvas size goes on.
        snapshotFlow {
            controller.layersVersion
            val doc = controller.doc
            Triple(doc.layers.size, doc.width, doc.height)
        }.collect { (layers, width, height) ->
            ExitWatch.editorShown(context, EngineConfig.level, layers, width, height, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    }
}

/** The UI frames' durations ([EngineStats.frame]) from the window's frame metrics, while the editor shows. */
@Composable
private fun FrameTimes(context: Context) {
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            EngineStats.frame(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000f)
        }
        val added = window != null && runCatching { window.addOnFrameMetricsAvailableListener(listener, frameMetricsHandler) }.isSuccess
        onDispose {
            if (added) runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        }
    }
}

/** The frame metrics arrive on one shared background thread (never main). */
private val frameMetricsHandler: Handler by lazy {
    Handler(HandlerThread("frame-metrics").apply { isDaemon = true; start() }.looper)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The ring appears after an activation has held the canvas this long. */
internal const val RING_DELAY_MS = 250L

/** The ring and its text, in the pill slot under the options strip. */
@Composable
private fun PreparingRing() {
    val density = LocalDensity.current
    val statusDp = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }.value
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .padding(top = ChromeLayout.pillTop(statusDp).dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 360.dp)
                .testTag(PREPARING_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = BrushworkColors.ChromeHigh,
            contentColor = BrushworkColors.OnChrome,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BrushworkColors.ChromeBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BrushworkColors.Accent, strokeWidth = 2.dp)
                Text(EngineLabels110.PREPARING_LAYER, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The ring's test tag (it is never a label). */
internal const val PREPARING_TAG = "engine.preparing"
