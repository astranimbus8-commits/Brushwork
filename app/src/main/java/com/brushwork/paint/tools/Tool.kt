package com.brushwork.paint.tools

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
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

    /**
     * Tool became active, or the active layer changed while it is the current tool (then it is
     * preceded by [onDeactivate]).
     */
    open fun onActivate() {}

    /** The user picked this tool (called once, right after [onActivate]; not on layer changes). */
    open fun onSelected() {}

    /** controller.selection changed (menu, undo, another tool) while this tool is current. */
    open fun onSelectionChanged() {}

    /** Another tool is being activated (or the editor closes): commit pending work. */
    open fun onDeactivate() { if (hasPendingWork) commit() }

    /** The editor is closing (after [onDeactivate]): free large buffers/caches now. */
    open fun onDispose() {}

    /** Screen-space overlay; [t] maps document -> screen. */
    open fun drawOverlay(canvas: Canvas, t: ViewTransform) {}

    /**
     * True while there is uncommitted, editable work (the UI shows confirm/cancel buttons).
     * Implementations MUST back this with Compose snapshot state (mutableStateOf) so the UI updates.
     */
    open val hasPendingWork: Boolean get() = false

    /**
     * True when the pending work holds something the user did, so undo throws it away instead of
     * stepping back in history (and redo waits for it). Work a tool set up on its own that is
     * still untouched (the transform tool lifting the layer when it becomes active) is not: undo
     * and redo drop it and act on the history right away, so they never seem to do nothing.
     */
    open val hasUserChanges: Boolean get() = hasPendingWork

    /**
     * Undo ONE step of the pending work (e.g. remove the last placed point) instead of discarding
     * all of it. Return true if a step was undone; false lets the controller discard the pending
     * work as a whole. Called by controller.undo() (undo button / two-finger tap).
     */
    open fun undoStep(): Boolean = false

    /** Redo one step previously taken back by [undoStep]. Return true if something was redone. */
    open fun redoStep(): Boolean = false

    /** True when [redoStep] would do something (Compose state; enables the Redo button). */
    open val canRedoStep: Boolean get() = false

    // ------------------------------------------------------------------ two-finger gestures

    /**
     * A two-finger gesture begins while this tool is current ([focus] = midpoint of the fingers,
     * [a]/[b] = the two finger positions, all DOCUMENT coordinates). Return true to handle it
     * (e.g. pinch-scale the transformed image when the fingers are on it); false lets the canvas
     * pan/zoom/rotate the view. Any one-finger gesture was already cancelled via [onCancel].
     */
    open fun onTwoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean = false

    /**
     * Cumulative change since [onTwoFingerStart]: [translation] of the focus point (document
     * px), [scale] factor of the finger distance and [rotationDeg] of the finger angle, around
     * the START focus point. Called for every move.
     */
    open fun onTwoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {}

    /** The gesture ended ([cancelled] = e.g. a third finger landed; revert to the start state). */
    open fun onTwoFingerEnd(cancelled: Boolean) {}

    /** Bake pending work into the layer (with undo). */
    open fun commit() {}

    /** Throw pending work away. */
    open fun discard() {}
}
