package com.brushwork.paint.engine.live

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer

/**
 * Live adjustment sessions (v1.6, §3.1 C2; area A): while an adjustment layer's effect, mask or
 * opacity is dragged, the area it covers is drawn from proxy tiles at 1/8..1× screen resolution
 * over a per-frame-keyed below-cache; when the finger stops (150 ms) or lifts, the exact image
 * refines tile by tile, visible tiles first, centre out, at most 8 ms per frame
 * (`DisplayTiles.updateBudgeted`). Proxies are views (I7): they never reach a saved, exported,
 * merged, thumbnailed or sampled bitmap, and once a session ends every display tile equals a
 * no-session render bit for bit.
 *
 * Callers: `AdjustmentEdit.preview`, `MaskPreview.update` and the layer window's opacity drag of
 * an adjustment layer call [touch], then [end]; `CanvasView.onDraw` calls [drawFrame] first and
 * keeps asking for frames while [wantsFrame].
 *
 * Foundation stub: no session ever starts; [touch] only invalidates (the v1.5 path).
 */
class LiveAdjust(private val c: EditorController) {
    /** EXACT: no session starts, [touch] only invalidates. LIVE: sessions (when the setting is on). */
    enum class Policy { EXACT, LIVE }

    /** I8: EXACT under Robolectric (tests opt in with LIVE and drive frames with [clock] and [refineStep]). */
    var policy: Policy = if ("robolectric" == android.os.Build.FINGERPRINT) Policy.EXACT else Policy.LIVE

    /** Time source in nanoseconds (replaceable in tests). */
    var clock: () -> Long = System::nanoTime

    /** True while a session is running (proxy frames or refinement left). Stub: never. */
    val isActive: Boolean get() = false

    /**
     * An adjustment layer's effect / opacity / mask changed live; [region] (document px, null =
     * all) needs redrawing. Starts or continues a session when [policy] is LIVE, the "Fast
     * adjustment preview" setting is on and [layer] is a visible adjustment layer. Stub: invalidate.
     */
    fun touch(layer: Layer, region: Rect?) {
        c.invalidateDoc(region)
    }

    /** The live edit of [layer] ended (finger up, sheet flushed): refine to exact. Stub: nothing to do. */
    fun end(layer: Layer) {}

    /**
     * Draws this frame when a session is active (true); false = the caller updates and draws the
     * display tiles itself. [canvas] is the view's canvas in SCREEN space; [docToScreen] maps
     * document px onto it; [visibleDoc] is the document area on screen; [smooth] = filter tiles.
     * Stub: false.
     */
    fun drawFrame(canvas: Canvas, docToScreen: Matrix, visibleDoc: Rect, smooth: Boolean): Boolean = false

    /** True while frames are still needed (refinement left): the view keeps invalidating. Stub: false. */
    val wantsFrame: Boolean get() = false

    /** Tests: one refinement slice of at most [budgetNanos]; false when done. Stub: false. */
    fun refineStep(budgetNanos: Long = Long.MAX_VALUE): Boolean = false

    /** Frees every session buffer (editor closing, `onTrimMemory`). Stub: nothing to free. */
    fun release() {}
}
