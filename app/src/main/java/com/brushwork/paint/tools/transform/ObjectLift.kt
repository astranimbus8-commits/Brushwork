package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer

/*
 * The Transform tool's object seam (v1.5 §5.6, frozen): on a vector layer the tool lifts OBJECTS
 * instead of pixels through an [ObjectLiftProvider] (`controller.vectors.liftProvider`, A2), and
 * keeps everything else (handles, Free / Distort, flips, Numbers, the X / Y strip, pinch,
 * snapping). The tool draws [ObjectLift.drawBase] plus [ObjectLift.floating] while the transform
 * is pending; ✓ calls [ObjectLift.commit], Delete [ObjectLift.delete], the end of the session
 * [ObjectLift.release].
 */

/** Lifted vector objects: a preview bitmap of them plus the layer without them. */
interface ObjectLift {
    val layer: Layer

    /** The lifted objects rendered alone; drawn by the transform matrix (× 1 / [floatingScale]). */
    val floating: Bitmap

    /** Where [floating] was (document px): the transform's initial box. */
    val sourceRect: Rect

    /** Pixels of [floating] per document px (1 unless memory forced a smaller preview). */
    val floatingScale: Float

    /** Draws the layer's cache with the lifted objects' hole (document px). */
    fun drawBase(canvas: Canvas)

    /** Applies [state] to the objects' geometry exactly (one undo step [label]); false = nothing changed. */
    fun commit(state: TransformState, label: String): Boolean

    /** Deletes the lifted objects (one undo step [label]). */
    fun delete(label: String): Boolean

    /** The session ended (committed, cancelled or deleted): free the bitmaps. */
    fun release()
}

/** Lifts vector objects for the Transform tool. */
interface ObjectLiftProvider {
    /**
     * Lift the object selection, else the objects touched by the pixel selection, else all.
     * [onReady] gets the lift (null: nothing to lift) — possibly later, after a background
     * render. False = refused (the provider showed why).
     */
    fun lift(layer: Layer, onReady: (ObjectLift?) -> Unit): Boolean

    /** Tap outside the quad: select the object under [p] alone and re-lift (true), or false for today's behaviour. */
    fun tapped(p: Vec2): Boolean
}

/**
 * The provider while objects can't be lifted (the foundation stub): the Transform tool then
 * lifts PIXELS on vector layers too, as on raster layers (committing turns the layer into a
 * raster layer, undoably, like any pixel edit).
 */
object RefusingLiftProvider : ObjectLiftProvider {
    override fun lift(layer: Layer, onReady: (ObjectLift?) -> Unit): Boolean = false
    override fun tapped(p: Vec2): Boolean = false
}
