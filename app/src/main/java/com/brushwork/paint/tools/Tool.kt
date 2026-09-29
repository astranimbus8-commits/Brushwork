package com.brushwork.paint.tools

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform

enum class ToolId(val label: String) {
    BRUSH("Brush"),
    ERASER("Eraser"),
    SMUDGE("Smudge"),
    BLUR("Blur"),
    FILL("Bucket"),
    EYEDROPPER("Eyedropper"),
    MAGIC_WAND("Magic wand"),
    LASSO("Lasso"),
    MARQUEE("Select shape"),
    TRANSFORM("Transform"),
    TEXT("Text"),
    SHAPE("Shape"),
    CURVE("Curve"),
    POLYLINE("Polyline"),
    FRAME_DIVIDER("Frame divider"),
    RULER("Ruler"),
}

/**
 * One input sample in DOCUMENT coordinates. [pressure] is 0..1 (1 for fingers unless the
 * brush simulates pressure). [time] is the event time in ms.
 */
data class ToolPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val time: Long = 0L,
    val isStylus: Boolean = false,
    /** Stylus tilt in radians (0 = perpendicular), 0 for fingers. */
    val tilt: Float = 0f,
    /** Stylus orientation in radians, 0 for fingers. */
    val orientation: Float = 0f,
)

/**
 * Base class for canvas tools. All callbacks happen on the main thread.
 *
 * Input: the controller forwards single-finger/stylus input. Multi-finger gestures (pan/zoom,
 * 2-finger-tap undo, 3-finger-tap redo) are handled by the canvas view, which calls [onCancel]
 * if a second finger lands while a stroke is in progress — the tool must then discard the
 * in-progress stroke WITHOUT leaving any trace or undo entry.
 *
 * Rendering: modify pixels only through the controller's helpers (so undo works), preview via
 * `controller.renderOverride` (goes through the compositor) and/or [drawOverlay] (screen space,
 * for handles/guides). Call `controller.invalidateDoc(rect)` / `controller.invalidateOverlay()`.
 */
abstract class Tool(val controller: EditorController) {
    abstract val id: ToolId

    /** If true the controller applies ruler snapping + stabilizer to the points before [onMove]. */
    open val usesStrokeAssist: Boolean = false

    open fun onDown(p: ToolPoint) {}
    open fun onMove(p: ToolPoint) {}
    open fun onUp(p: ToolPoint) {}

    /** Discard any in-progress gesture (see class docs). */
    open fun onCancel() {}

    /**
     * The finger stayed down without moving for ~450 ms. Return true if handled (e.g. the curve
     * tool opens the sharp/smooth menu for the point under the finger).
     */
    open fun onLongPress(p: ToolPoint): Boolean = false

    /** Tool became the active tool. */
    open fun onActivate() {}

    /** Another tool is being activated (or the editor closes): commit pending work. */
    open fun onDeactivate() { if (hasPendingWork) commit() }

    /** Screen-space overlay; [t] maps document -> screen. */
    open fun drawOverlay(canvas: Canvas, t: ViewTransform) {}

    /** True while there is uncommitted, editable work (the UI shows confirm/cancel buttons). */
    open val hasPendingWork: Boolean get() = false

    /** Bake pending work into the layer (with undo). */
    open fun commit() {}

    /** Throw pending work away. */
    open fun discard() {}
}
