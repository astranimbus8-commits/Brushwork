package com.brushwork.paint.vector.draw

import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeInfo
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.brush.StrokeRecorder
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.vector.VStroke

/**
 * What a brush / eraser stroke does on a vector layer (v1.5 §4.9, owned by A3), reached through
 * `VectorLayers.strokeHook` (the BrushTool seam):
 *
 * - the brush draws exactly as on a raster layer (same live stroke, stabilizer, ruler); on lift
 *   the stroke is kept as a `VStroke` with every point it was fed: the live pixels ARE the cache
 *   (kept with `keepLayerData`), the object is appended as data, ONE undo step;
 * - the eraser removes objects or parts of them ([VectorEraserRecorder]);
 * - tips that move pixels (watercolor, smudge, blur) are refused, and so is a brush on an
 *   alpha-locked vector layer (its live stroke would be clipped to the painted pixels, which a
 *   replay can't reproduce);
 * - a stroke into the layer's mask, a path stroke and every stroke on another kind of layer is a
 *   normal raster stroke.
 */
object VectorStrokeCapture {
    /** Shown when a brush is refused on an alpha-locked vector layer. */
    const val ALPHA_LOCK_MESSAGE = "Transparency is locked on this vector layer: unlock it to draw strokes"

    fun hookFor(c: EditorController, info: StrokeInfo): StrokeHook {
        val layer = info.layer
        if (info.isPath || !layer.isVectorLayer || info.target != EditTarget.CONTENT) return StrokeHook.None
        return when {
            info.toolId == ToolId.ERASER ->
                StrokeHook.Record(VectorEraserRecorder(c, layer, info.preset, info.isStylus, VectorEraserModes.mode(c)))
            info.toolId != ToolId.BRUSH -> StrokeHook.Refuse(LayerToolRules.pixelOnlyMessage(info.toolId))
            info.kind.isDirect || info.kind != StrokeKind.PAINT -> StrokeHook.Refuse(directTipMessage(info))
            layer.alphaLocked -> StrokeHook.Refuse(ALPHA_LOCK_MESSAGE)
            else -> {
                val state = VectorDrawState.of(c)
                if (c.selection != null && !state.selectionHintShown) {
                    state.selectionHintShown = true
                    c.toast("Selections don't limit strokes on vector layers")
                }
                StrokeHook.Record(StrokeCapture(c, info))
            }
        }
    }

    /** "Watercolor needs a raster layer" (the brush's name, else its tip). */
    internal fun directTipMessage(info: StrokeInfo): String {
        val name = info.preset.name.takeIf { it.isNotBlank() } ?: when (info.preset.tip) {
            BrushTip.WATERCOLOR -> "Watercolor"
            BrushTip.SMUDGE -> "Smudge"
            BrushTip.BLUR -> "Blur"
            else -> info.toolId.label
        }
        return "$name needs a raster layer — tap Vector to switch"
    }
}

/**
 * Records a brush stroke on a vector layer: every point the stroke is fed, with its raw
 * pressure. Its commit keeps the live pixels (the cache) and appends the `VStroke` as data, in
 * ONE undo step; a stroke that painted nothing records nothing.
 */
private class StrokeCapture(private val c: EditorController, private val info: StrokeInfo) : StrokeRecorder {
    override val replacesStroke: Boolean = false

    /** A vector stroke is an object: the pixel selection doesn't clip it (its replay couldn't). */
    override val ignoresSelection: Boolean = true

    private var xs = FloatArray(64)
    private var ys = FloatArray(64)
    private var ps = FloatArray(64)
    private var n = 0

    override fun point(x: Float, y: Float, rawPressure: Float) {
        if (n == xs.size) {
            val cap = n * 2
            xs = xs.copyOf(cap); ys = ys.copyOf(cap); ps = ps.copyOf(cap)
        }
        xs[n] = x; ys[n] = y; ps[n] = rawPressure
        n++
    }

    override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
        val layer = info.layer
        if (c.doc.indexOf(layer) < 0 || n == 0) return false
        // (The layer stopped being a vector layer meanwhile: a normal stroke.)
        if (!layer.isVectorLayer) return commitPixels()
        // Pixels and data must change together: a layer that can't take the data gets neither.
        if (layer.locked || !layer.visible) return false
        val stroke = VStroke(
            id = 0,
            preset = info.preset,
            color = info.color,
            seed = info.seed,
            stylus = info.isStylus,
            points = PackedPoints(xs.copyOf(n), ys.copyOf(n), ps.copyOf(n)),
        )
        var ok = false
        c.groupUndo(label) {
            ok = c.keepLayerData(layer) { commitPixels() }
            if (ok) c.vectors.appendData(layer, listOf(stroke), label)
        }
        return ok
    }

    override fun cancel() {
        n = 0
    }
}
