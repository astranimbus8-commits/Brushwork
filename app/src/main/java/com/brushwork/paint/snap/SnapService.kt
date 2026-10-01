package com.brushwork.paint.snap

import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.LayerBoundsCache
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapBox
import com.brushwork.paint.tools.transform.SnapGuide
import com.brushwork.paint.tools.transform.SnapGuideRenderer
import com.brushwork.paint.tools.transform.SnapGuides
import com.brushwork.paint.tools.transform.SnapHit
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.SnapResult
import com.brushwork.paint.tools.transform.SnapSource
import com.brushwork.paint.tools.transform.SnapTargets
import com.brushwork.paint.tools.vector.ShapeGeometry

/**
 * "Snap to objects" for every tool (one setting, [enabled]): what dragged things align to and
 * how far they reach. One per editor ([EditorController.snapping]); main thread only.
 *
 * Targets ([targets]): the canvas edges and center, the selection (optional), the content
 * bounds of the other visible layers, extra lines a tool adds (e.g. the other points of the path
 * being edited, see [SnapLine.point]), features layers provide ([addLayerFeatures], e.g. the
 * vertices of shape layers), the straight lines drawn in layers ([LineDetector]: Table filter
 * lines, frame borders...) and, optionally, the grid. Layer bounds and lines are found in the
 * background and cached by content version ([prepare]); [version] changes when new ones arrive.
 *
 * Tools usually go through a [SnapSession] (one per gesture), which also draws the guides.
 */
class SnapService(private val controller: EditorController) {
    private val prefs get() = controller.settings.prefs

    private var enabledState by mutableStateOf(prefs.getBoolean(PREF_SNAP, true))

    /**
     * Snap to objects: on by default, remembered, shared by all tools (the transform tool's
     * "Snap to objects" chip and the one of every other tool are the same setting).
     */
    var enabled: Boolean
        get() = enabledState
        set(v) {
            if (v == enabledState) return
            enabledState = v
            prefs.edit { putBoolean(PREF_SNAP, v) }
            version++
            controller.invalidateOverlay()
        }

    /** Changes whenever what can be snapped to may have changed (new bounds / lines found). */
    var version: Int = 0
        private set

    private val cache = LayerBoundsCache(controller.scope, detectLines = true) {
        version++
        controller.invalidateOverlay()
    }

    /** True while the bounds / lines of some (large) layers are still being found. */
    val isBusy: Boolean get() = cache.isBusy

    private val featureSources = ArrayList<(Layer) -> List<SnapLine>>()

    /**
     * Registers [source]: extra lines / points [Layer]s offer as targets (e.g. the shape tool
     * returns the vertices of shape layers, via [SnapLine.point]). Called for every candidate
     * layer when targets are built; keep it cheap (cache decoded data).
     */
    fun addLayerFeatures(source: (Layer) -> List<SnapLine>) {
        featureSources += source
    }

    /** How close (document px, at the current zoom) something must come to snap. */
    fun threshold(): Float {
        val t = controller.viewTransform
        val d = t.screenToDocLength(t.dp(SNAP_DISTANCE_DP))
        return if (d.isFinite() && d > 0f) d else 0f
    }

    /** Layers whose content can be snapped to: visible, not fully transparent, not in [exclude]. */
    fun candidateLayers(exclude: Collection<Layer> = emptyList()): List<Layer> =
        controller.doc.layers.filter { l -> l.visible && l.opacity > 0f && exclude.none { it === l } }

    /**
     * Starts finding the bounds and lines of the layers that can be snapped to (except
     * [exclude]); what is known stays cached. Does nothing while snapping is off.
     */
    fun prepare(exclude: Collection<Layer> = emptyList()) {
        if (!enabled) return
        cache.request(candidateLayers(exclude), controller.doc.layers)
    }

    /** Content bounds of [layer] if known (document px). */
    fun bounds(layer: Layer): Rect? = cache.bounds(layer)

    /** Straight lines drawn in [layer] if known (document px). */
    fun lines(layer: Layer): List<DetectedLine> = cache.lines(layer)

    /**
     * Everything to snap to (whether or not snapping is [enabled]: callers check that): the
     * canvas, the selection's bounds when [includeSelection], [boxes], the content bounds of
     * the candidate layers (top-most first) when [includeLayers], then [extra] lines, the layers'
     * features and drawn lines, and the grid lines when [includeGrid] and grid snapping is on.
     */
    fun targets(
        exclude: Collection<Layer> = emptyList(),
        boxes: List<SnapBox> = emptyList(),
        extra: List<SnapLine> = emptyList(),
        includeSelection: Boolean = false,
        includeLayers: Boolean = true,
        includeGrid: Boolean = false,
    ): SnapTargets {
        val doc = controller.doc
        val allBoxes = ArrayList<SnapBox>()
        if (includeSelection) controller.selection?.bounds?.takeIf { !it.isEmpty }?.let { allBoxes += SnapBox(it.toDocBox(), "Selection", SnapSource.SELECTION) }
        allBoxes += boxes
        val lines = ArrayList<SnapLine>(extra)
        if (includeLayers) {
            val layers = candidateLayers(exclude).asReversed()
            for (layer in layers) cache.bounds(layer)?.let { allBoxes += SnapBox(it.toDocBox(), layer.name, SnapSource.OBJECT) }
            for (layer in layers) for (src in featureSources) lines += runCatching { src(layer) }.getOrDefault(emptyList())
            for (layer in layers) for (l in cache.lines(layer)) lines += SnapLine.drawn(l.axis, l.pos, l.start, l.end, layer.name)
        }
        val grid = if (includeGrid) controller.grid.takeIf { it.snap }?.let { SnapGuides.gridLines(it, doc.width, doc.height) } else null
        return SnapTargets.build(doc.width.toFloat(), doc.height.toFloat(), allBoxes, grid, extra = lines)
    }

    /**
     * [p] on the nearest square-grid intersection when grid snapping is on (the vector tools'
     * long-standing grid snap), else [p] unchanged.
     */
    fun gridPoint(p: Vec2): Vec2 {
        val g = controller.grid
        return if (g.enabled && g.snap && g.type == GridType.SQUARE && g.spacingPx > 0f) {
            ShapeGeometry.snapToGrid(p, g.spacingPx, g.offsetXPx, g.offsetYPx)
        } else p
    }

    /** Stops finding bounds / lines (what is known stays cached). */
    fun cancel() = cache.cancel()

    /** Forgets everything (the editor is closing). */
    fun clear() = cache.clear()

    companion object {
        /** Snap distance on screen. */
        const val SNAP_DISTANCE_DP = 8f

        /** Shared with the transform tool's former setting, so the user's choice carries over. */
        const val PREF_SNAP = "transform.snapToObjects"

        internal fun Rect.toDocBox() = DocBox(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
}

/**
 * Snapping for one drag (a point, a box) of one tool: [begin] when a gesture starts, [snapPoint]
 * / [snapMove] / [snapValue] on every move (always with what the finger alone gives, so moving
 * farther than the snap distance lets go of a guide), [draw] in the tool's overlay, [end] when
 * the finger lifts. Targets are built on first use and rebuilt when new layer bounds / lines
 * arrive. With snapping off, points still follow the square grid when grid snapping is on.
 */
class SnapSession(private val service: SnapService, private val controller: EditorController) {
    /** Guides to show right now (document px); empty when nothing is aligned. */
    var guides: List<SnapGuide> = emptyList()
        private set

    private var targets: SnapTargets? = null
    private var builtVersion = -1
    private var exclude: List<Layer> = emptyList()
    private var includeSelection = false
    private var extra: () -> List<SnapLine> = { emptyList() }

    /** True between [begin] and [end]. */
    var isActive = false
        private set

    /**
     * Starts a gesture: [exclude] layers are not snapped to (e.g. the shape layer being edited),
     * [includeSelection] adds the selection's bounds, [extra] gives tool-specific targets
     * (e.g. `SnapLine.point` for the other points of the path), evaluated when targets are built.
     */
    fun begin(exclude: Collection<Layer> = emptyList(), includeSelection: Boolean = false, extra: () -> List<SnapLine> = { emptyList() }) {
        this.exclude = exclude.toList()
        this.includeSelection = includeSelection
        this.extra = extra
        targets = null
        builtVersion = -1
        guides = emptyList()
        isActive = true
        service.prepare(this.exclude)
    }

    /** Forces the targets to be rebuilt (e.g. [extra] changed during the gesture). */
    fun invalidateTargets() {
        targets = null
    }

    private fun current(): SnapTargets? {
        if (!service.enabled) return null
        val t = targets
        if (t != null && builtVersion == service.version) return t
        return service.targets(exclude, extra = extra(), includeSelection = includeSelection).also {
            targets = it
            builtVersion = service.version
        }
    }

    /**
     * [p] snapped: each axis to the closest target line within reach (when snapping is on);
     * an axis that didn't snap follows the square grid when grid snapping is on. Updates [guides].
     */
    fun snapPoint(p: Vec2): Vec2 {
        val t = current()
        val grid = service.gridPoint(p)
        if (t == null) {
            setGuides(emptyList())
            return grid
        }
        val thr = service.threshold()
        val hx = SnapGuides.snapValue(p.x, SnapAxis.X, t, thr)
        val hy = SnapGuides.snapValue(p.y, SnapAxis.Y, t, thr)
        val q = Vec2(hx?.pos ?: grid.x, hy?.pos ?: grid.y)
        setGuides(if (hx == null && hy == null) emptyList() else SnapGuides.snapPoint(q, t, 1e-3f).second)
        return q
    }

    /**
     * A dragged box: the offset that puts its closest left / center / right and top / center /
     * bottom lines on target lines (zero on an axis that didn't snap: see
     * [SnapResult.snappedX]). Updates [guides]. Grid snapping is left to the caller.
     */
    fun snapMove(box: DocBox): SnapResult {
        val t = current() ?: return SnapResult(0f, 0f, emptyList(), snappedX = false, snappedY = false).also { setGuides(emptyList()) }
        val r = SnapGuides.snapMove(box, t, service.threshold())
        setGuides(r.guides)
        return r
    }

    /**
     * One coordinate (e.g. the dragged edge of a box) snapped on [axis], or null. Does not touch
     * [guides]: show them with [showGuidesFor].
     */
    fun snapValue(v: Float, axis: SnapAxis): SnapHit? {
        val t = current() ?: return null
        return SnapGuides.snapValue(v, axis, t, service.threshold())
    }

    /** Shows the guides of every target line [box]'s lines lie on (e.g. after [snapValue]). */
    fun showGuidesFor(box: DocBox, eps: Float = 0.51f) {
        val t = current()
        setGuides(if (t == null) emptyList() else SnapGuides.guidesFor(box, t, eps))
    }

    /** Hides the guides (the gesture goes on). */
    fun clearGuides() = setGuides(emptyList())

    /** Ends the gesture: hides the guides. */
    fun end() {
        isActive = false
        targets = null
        setGuides(emptyList())
    }

    private fun setGuides(g: List<SnapGuide>) {
        if (g.isEmpty() && guides.isEmpty()) return
        guides = g
        controller.invalidateOverlay()
    }

    /** Draws the guides (screen space, in a tool's overlay). [moving] keeps labels off it. */
    fun draw(canvas: Canvas, t: ViewTransform, moving: DocBox? = null) {
        if (guides.isEmpty()) return
        SnapGuideRenderer.draw(canvas, t, guides, controller.doc.width.toFloat(), controller.doc.height.toFloat(), moving)
    }
}
