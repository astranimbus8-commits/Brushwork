package com.brushwork.paint.tools.clone

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeRecorder
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.PositionedTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.hypot
import kotlin.math.min

/**
 * Clone stamp (v1.5 §4.2), like Photoshop's: paints with pixels copied from a source point.
 *
 * - A long-press (or the "Set source" chip, then a tap) sets the source; dragging its ⊕
 *   crosshair ([GRAB_RADIUS_DP]) moves it. Moving the finger after a long-press keeps moving it.
 *   With Aligned the ⊕ travels with the strokes: after a stroke it marks what its end sampled.
 * - Painting copies what is under the source to under the finger, with the real brush engine: a
 *   private [BrushTool] (`ToolId.CLONE` presets: coverage tips only) whose coverage is painted
 *   through [CloneSource]'s shader, so the live stroke equals the result. Selection, alpha lock
 *   and mask editing work as for the brush. One undo step per stroke, "Clone stamp"; a second
 *   finger cancels the stroke without a trace.
 * - **Aligned** (default on): the first stroke after setting the source fixes the offset and
 *   the next strokes keep it, so the source travels with the strokes; off, every stroke starts
 *   at the source again ([CloneAnchor]). **Sample**: this layer, or all visible layers.
 *
 * The source, the offset and the armed state are tool state, not document data (no undo).
 * The X / Y strip moves the source ([objectPosition]).
 */
class CloneTool(controller: EditorController) : Tool(controller), PositionedTool {
    override val id = ToolId.CLONE

    /** Strokes go through the ruler and stabilizer like the brush; placing the source does not. */
    override val usesStrokeAssist: Boolean get() = !armed

    /** The painting engine (not one of the controller's tools): its coverage shows [source]'s pixels. */
    val brush: BrushTool = BrushTool(controller, ToolId.CLONE)

    /** The source point and the Aligned offset. */
    val anchor = CloneAnchor()

    internal val source = CloneSource(controller)
    private val settings = controller.settings

    private var alignedState by mutableStateOf(settings.cloneAligned)
    private var sampleAllState by mutableStateOf(settings.cloneSampleAllLayers)
    private var showSourceState by mutableStateOf(settings.cloneShowSource)

    /** Photoshop's "Aligned" (stored in the app settings; [setAligned]). Compose state. */
    val aligned: Boolean get() = alignedState

    /** Sample all visible layers instead of the active one (stored in the app settings). Compose state. */
    val sampleAllLayers: Boolean get() = sampleAllState

    /** Show the ⊕ source crosshair and the sampled point while painting (stored in the app settings). Compose state. */
    val showSource: Boolean get() = showSourceState

    /** "Set source" is armed: the next touch places the source. */
    var armed by mutableStateOf(false)
        private set

    private enum class Gesture { NONE, WAITING, PAINT, PLACE, DRAG }

    private var gesture = Gesture.NONE
    private var strokeOffset: CloneOffset? = null
    /** The finger while painting (document px): the sampled point follows it. */
    private var finger: Vec2? = null
    /** Source − finger when a drag of the ⊕ began. */
    private var grab = Vec2(0f, 0f)
    private var sourceBefore: Vec2? = null
    private var fixedBefore: CloneOffset? = null
    private var originBefore: Vec2? = null
    /**
     * Where a long-press placed the source, while the finger has not moved away from it (more
     * than [HOLD_SLOP_DP]): the source stays exactly at the held point, whatever the jitter of a
     * still finger or the lift point (which went through the ruler / stabilizer) says.
     */
    private var heldAt: Vec2? = null
    /** The "sampling this layer instead" message was shown (once per use of the tool). */
    private var fallbackShown = false

    /** True while a clone stroke is painted. */
    val isPainting: Boolean get() = gesture == Gesture.PAINT

    /** The point the finger copies from while painting (document px), else null. */
    val sampledPoint: Vec2? get() {
        if (gesture != Gesture.PAINT) return null
        val f = finger ?: return null
        return strokeOffset?.sourceOf(f)
    }

    /** Lets the brush commit, except a stroke whose whole source lies outside the document (it copies nothing: no step). */
    private val commitGate = object : StrokeRecorder {
        override val replacesStroke: Boolean get() = false
        override val ignoresSelection: Boolean get() = false
        override fun point(x: Float, y: Float, rawPressure: Float) {}
        override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean =
            if (source.readsOnlyOutside(bounds)) false else commitPixels()
        override fun cancel() {}
    }

    init {
        brush.coverageSource = source
        brush.undoLabelOverride = UNDO_LABEL
        brush.strokeHook = { info ->
            // (The pointer-down gate already refuses vector layers; kept for direct callers.)
            if (info.layer.isVectorLayer) StrokeHook.Refuse(LayerToolRules.pixelOnlyMessage(ToolId.CLONE)) else StrokeHook.Record(commitGate)
        }
    }

    // ------------------------------------------------------------------ options

    fun setAligned(on: Boolean) {
        if (on == alignedState) return
        alignedState = on
        settings.cloneAligned = on
        anchor.resetAlignment()
        controller.invalidateOverlay()
    }

    fun setSampleAllLayers(on: Boolean) {
        if (on == sampleAllState) return
        sampleAllState = on
        settings.cloneSampleAllLayers = on
        fallbackShown = false
        if (!on) source.releaseSnapshot()
    }

    fun setShowSource(on: Boolean) {
        if (on == showSourceState) return
        showSourceState = on
        settings.cloneShowSource = on
        controller.invalidateOverlay()
    }

    /** Arms (or disarms) "Set source": the next tap places the source. */
    fun arm(on: Boolean) {
        if (on == armed) return
        if (gesture == Gesture.PAINT || gesture == Gesture.PLACE || gesture == Gesture.DRAG) return
        armed = on
        controller.invalidateOverlay()
    }

    /** Sets the source at [p] (document px); the next stroke fixes the Aligned offset again. */
    fun setSource(p: Vec2) {
        anchor.set(p)
        controller.invalidateOverlay()
    }

    /** Forgets the source. */
    fun clearSource() {
        anchor.clear()
        controller.invalidateOverlay()
    }

    private val position = object : ObjectPosition {
        override val position: Vec2? get() = anchor.source
        override val label: String get() = POSITION_LABEL
        override fun setPosition(x: Float?, y: Float?) {
            val s = anchor.source ?: return
            setSource(Vec2(x ?: s.x, y ?: s.y))
        }
    }

    /** The source, for the X / Y strip (null while none is set). */
    override val objectPosition: ObjectPosition? get() = if (anchor.source != null) position else null

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        val at = Vec2(p.x, p.y)
        finger = at
        val src = anchor.source
        when {
            armed -> beginPlacing(Gesture.PLACE, at)
            src != null && showSource && isOnCrosshair(at, src) -> {
                beginPlacing(Gesture.DRAG, at)
                grab = src - at
            }
            src == null -> gesture = Gesture.WAITING
            else -> startStroke(p, at)
        }
    }

    private fun beginPlacing(g: Gesture, at: Vec2) {
        sourceBefore = anchor.source
        fixedBefore = anchor.fixed
        originBefore = anchor.origin
        gesture = g
        if (g == Gesture.PLACE) anchor.set(at)
        controller.invalidateOverlay()
    }

    private fun startStroke(p: ToolPoint, at: Vec2) {
        val offset = anchor.offsetFor(at, aligned) ?: run { gesture = Gesture.WAITING; return }
        val layer = controller.activeLayer
        val target = controller.editTargetOf(layer)
        val wanted = if (sampleAllLayers) CloneSource.Sample.ALL_LAYERS else CloneSource.Sample.THIS_LAYER
        val used = source.beginStroke(layer, target, wanted, offset)
        // (A mask always clones its own values: that is not a fallback.)
        if (used != wanted && target == EditTarget.CONTENT && !fallbackShown) {
            fallbackShown = true
            controller.toast(FALLBACK_MESSAGE)
        }
        strokeOffset = offset
        gesture = Gesture.PAINT
        // The stroke keeps the preset it starts with (the side slider may move meanwhile). A dab
        // reaches size / 2 from its center (a rotated square tip size · 0.71) and is scattered up
        // to scatter · size away from the path.
        val preset = controller.cloneBrush
        strokeReach = preset.size * (0.75f + preset.scatter.coerceAtLeast(0f)) + 2f
        brush.onDown(p)
        if (!brush.isStroking) {
            // Refused (locked or hidden layer...): the brush said why.
            source.endStroke()
            gesture = Gesture.NONE
            strokeOffset = null
            return
        }
        notePath(at)
    }

    /** How far the current stroke's dabs reach from its path (document px). */
    private var strokeReach = 0f

    /** Tells the source where the stroke went (All layers composites only what it can sample). */
    private fun notePath(to: Vec2) {
        val from = finger ?: to
        source.notePath(from.x, from.y, to.x, to.y, strokeReach)
        finger = to
    }

    override fun onMove(p: ToolPoint) {
        val at = Vec2(p.x, p.y)
        when (gesture) {
            Gesture.PAINT -> {
                notePath(at)
                brush.onMove(p)
            }
            Gesture.PLACE -> {
                val held = heldAt
                if (held != null && screenDistance(at, held) <= controller.viewTransform.dp(HOLD_SLOP_DP)) return
                heldAt = null
                setSource(at)
            }
            Gesture.DRAG -> setSource(at + grab)
            Gesture.NONE, Gesture.WAITING -> {}
        }
    }

    override fun onUp(p: ToolPoint) {
        val at = Vec2(p.x, p.y)
        when (gesture) {
            Gesture.PAINT -> {
                notePath(at) // before the brush commits
                brush.onUp(p)
                strokeOffset?.let { anchor.strokeCompleted(it, aligned, at) }
            }
            Gesture.PLACE -> {
                // After a long-press without moving, the exact point held stays (the lift point
                // went through the ruler / stabilizer).
                if (heldAt == null) setSource(at)
                armed = false
            }
            Gesture.DRAG -> setSource(at + grab)
            Gesture.WAITING -> controller.toast(HINT)
            Gesture.NONE -> {}
        }
        endGesture()
    }

    /**
     * A long-press sets the source where the finger is (the stroke it started leaves nothing).
     * It also works where the stroke was refused (a locked or hidden layer: the source is only
     * tool state, and All layers may sample other layers).
     */
    override fun onLongPress(p: ToolPoint): Boolean {
        when (gesture) {
            Gesture.PAINT -> {
                brush.onCancel()
                source.endStroke()
                strokeOffset = null
            }
            Gesture.WAITING, Gesture.NONE -> {}
            // Placing already: the source is where the finger is.
            Gesture.PLACE -> return true
            // Holding the ⊕ still while dragging it is no command (no haptic tick).
            Gesture.DRAG -> return false
        }
        sourceBefore = anchor.source
        fixedBefore = anchor.fixed
        originBefore = anchor.origin
        armed = false
        gesture = Gesture.PLACE
        val at = Vec2(p.x, p.y)
        setSource(at)
        heldAt = anchor.source ?: at
        return true
    }

    override fun onCancel() {
        when (gesture) {
            Gesture.PAINT -> {
                brush.onCancel()
                source.endStroke()
            }
            Gesture.PLACE, Gesture.DRAG -> {
                anchor.restore(sourceBefore, fixedBefore, originBefore)
                controller.invalidateOverlay()
            }
            Gesture.NONE, Gesture.WAITING -> {}
        }
        endGesture()
    }

    private fun endGesture() {
        gesture = Gesture.NONE
        strokeOffset = null
        finger = null
        sourceBefore = null
        fixedBefore = null
        originBefore = null
        heldAt = null
        controller.invalidateOverlay()
    }

    override fun onSelected() {
        fallbackShown = false
    }

    override fun onDeactivate() {
        if (gesture == Gesture.PAINT) {
            // Only reachable mid-stroke if the editor closes or switches tools under the finger.
            brush.onDeactivate()
            val end = finger
            val offset = strokeOffset
            if (end != null && offset != null) anchor.strokeCompleted(offset, aligned, end)
        }
        endGesture()
        armed = false
        source.releaseSnapshot()
    }

    override fun onDispose() {
        brush.onDispose()
        source.release()
    }

    // ------------------------------------------------------------------ overlay

    /**
     * True when a touch at [p] grabs the ⊕ at [src] instead of painting: within [GRAB_RADIUS_DP]
     * on screen. With Aligned and a kept offset the ⊕ sits one offset behind where the last
     * stroke ended, so a stroke that continues from there lands one offset away from it; such a
     * stroke must paint. The ⊕ is then grabbed only by touches nearer to it than half the
     * offset (painting wins when a short offset makes the two ambiguous; a long-press, Set
     * source or the X / Y strip still move the source).
     */
    internal fun isOnCrosshair(p: Vec2, src: Vec2): Boolean {
        val t = controller.viewTransform
        var radius = t.dp(GRAB_RADIUS_DP)
        val kept = anchor.fixed
        if (aligned && kept != null) radius = min(radius, 0.5f * hypot(kept.dx.toFloat(), kept.dy.toFloat()) * t.zoom)
        return screenDistance(p, src) <= radius
    }

    private fun screenDistance(a: Vec2, b: Vec2): Float {
        val t = controller.viewTransform
        return t.docToScreen(a).distanceTo(t.docToScreen(b))
    }

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt(); strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        brush.drawOverlay(canvas, t)
        val src = anchor.source ?: return
        val placing = gesture == Gesture.PLACE || gesture == Gesture.DRAG
        if (!showSource && !placing) return
        val radius = controller.cloneBrush.size / 2f * t.zoom
        val at = t.docToScreen(src)
        // While the source is placed: the area the brush will copy from, in the accent color.
        if (placing) ring(canvas, at, radius, t, ACCENT)
        crosshair(canvas, at, t, if (placing) ACCENT else WHITE, circled = true)
        val sampled = sampledPoint ?: return
        val s = t.docToScreen(sampled)
        ring(canvas, s, radius, t, WHITE)
        crosshair(canvas, s, t, WHITE, circled = false)
    }

    private fun ring(canvas: Canvas, c: Vec2, radius: Float, t: ViewTransform, color: Int) {
        if (radius < t.dp(4f)) return
        shadow.strokeWidth = t.dp(3f)
        line.strokeWidth = t.dp(1.5f)
        line.color = color
        canvas.drawCircle(c.x, c.y, radius, shadow)
        canvas.drawCircle(c.x, c.y, radius, line)
    }

    /** "⊕" ([circled]) or "+" with a gap in the middle, constant on screen. */
    private fun crosshair(canvas: Canvas, c: Vec2, t: ViewTransform, color: Int, circled: Boolean) {
        val inner = t.dp(if (circled) 5f else 3f)
        val outer = t.dp(if (circled) 14f else 10f)
        for (pass in 0..1) {
            val p = if (pass == 0) shadow.apply { strokeWidth = t.dp(3.5f) } else line.apply { strokeWidth = t.dp(1.5f); this.color = color }
            if (circled) canvas.drawCircle(c.x, c.y, t.dp(9f), p)
            canvas.drawLine(c.x - outer, c.y, c.x - inner, c.y, p)
            canvas.drawLine(c.x + inner, c.y, c.x + outer, c.y, p)
            canvas.drawLine(c.x, c.y - outer, c.x, c.y - inner, p)
            canvas.drawLine(c.x, c.y + inner, c.x, c.y + outer, p)
        }
    }

    companion object {
        const val UNDO_LABEL = "Clone stamp"
        const val HINT = "Long-press where to copy from"
        const val FALLBACK_MESSAGE = "Not enough memory to sample all layers: copying from this layer"
        const val POSITION_LABEL = "Source"

        /** Touching this close to the ⊕ (on screen) drags the source instead of painting. */
        const val GRAB_RADIUS_DP = 28f

        /** A finger still holding a long-press may wobble this much (on screen) without moving the source. */
        const val HOLD_SLOP_DP = 6f

        private const val ACCENT = 0xFF4DA3FF.toInt()
        private const val WHITE = -1
    }
}
