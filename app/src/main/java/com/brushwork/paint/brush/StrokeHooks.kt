package com.brushwork.paint.brush

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId

/*
 * Seams through which v1.5 features change what a [BrushTool] stroke does without new tool
 * instances (frozen): vector layers record the stroke as an object ([StrokeHook.Record]), the
 * vector eraser replaces it, refused tips say why ([StrokeHook.Refuse]), and the clone stamp
 * paints the coverage with pixels from elsewhere ([CoverageSource]).
 */

/** What a stroke is about to be (decided once, when it starts). */
data class StrokeInfo(
    val toolId: ToolId,
    val kind: StrokeKind,
    val layer: Layer,
    val target: EditTarget,
    /** The sanitized brush the stroke paints with. */
    val preset: BrushPreset,
    val seed: Long,
    val isStylus: Boolean,
    /** The stroke color (opaque ARGB; luminance gray for a mask target). */
    val color: Int,
    /** True for a stroke along a vector path ([BrushTool.beginPath]); those always get [StrokeHook.None]. */
    val isPath: Boolean,
)

/** The answer of [BrushTool.strokeHook] for a starting stroke. */
sealed interface StrokeHook {
    /** A normal raster stroke. */
    data object None : StrokeHook

    /** No stroke: [message] is shown instead. */
    data class Refuse(val message: String) : StrokeHook

    /** The stroke is reported to [recorder] (every input point, and its commit). */
    data class Record(val recorder: StrokeRecorder) : StrokeHook
}

/** Receives a recorded stroke ([StrokeHook.Record]). Main thread. */
interface StrokeRecorder {
    /** True: BrushTool paints nothing (vector eraser); the recorder shows its own feedback. */
    val replacesStroke: Boolean

    /** True: the stroke is not clipped by the pixel selection (vector strokes). */
    val ignoresSelection: Boolean

    /** Every point given to the stroke's begin / move / finish, with its RAW pressure (before Stroke.pressureOf). */
    fun point(x: Float, y: Float, rawPressure: Float)

    /**
     * Replaces controller.commitEdit when the stroke completes: [commitPixels] does the normal
     * commit (the brush's own undo step) and returns its result. [bounds] = what the stroke
     * painted (document px, clipped to the document). Returns whether something was recorded.
     */
    fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean

    /** The stroke was cancelled (second finger, tool switch): leave no trace. */
    fun cancel()

    /** Screen-space feedback while the stroke runs ([t] maps document -> screen). */
    fun drawOverlay(canvas: Canvas, t: ViewTransform) {}
}

/** Clone stamp: the coverage is painted with [shader] instead of a color. */
interface CoverageSource {
    /** Called before each composite of [destDocRect] (document px; lazy source tiles). */
    fun prepare(destDocRect: Rect)

    /** Paints the coverage (document-anchored; its local matrix places the source). */
    val shader: Shader

    /** Before the stroke is committed into [commitRect]: copy the source region first (V13); may swap [shader]. */
    fun prepareCommit(commitRect: Rect)

    /** The stroke ended (committed or cancelled). */
    fun endStroke()
}
