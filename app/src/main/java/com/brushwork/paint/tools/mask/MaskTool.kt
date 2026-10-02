package com.brushwork.paint.tools.mask

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.MaskCoverageHint
import com.brushwork.paint.engine.PixelEditRecorder
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.BrushSource
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskBrushCache
import com.brushwork.paint.masks.MaskBrushRaster
import com.brushwork.paint.masks.MaskComponent
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.masks.MaskGeometry
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecRenderer
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.MaskStroke
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.masks.SampleGrid
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.PinchTargeting
import com.brushwork.paint.tools.PositionedTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Masks (v1.5 §4.3; owned by A5): Lightroom-style editable masks made of linear, radial and brush
 * components, on an adjustment layer (whose effect then applies live through the mask) or as a
 * normal layer's own mask.
 *
 * - "+ Linear" / "+ Radial" / "+ Brush" arm a creating gesture: drag from the 100 % point to the
 *   0 % point, drag from the centre outward, or paint. Without anything armed a drag does
 *   nothing (pick a type first, as in Lightroom); with a brush component selected, drags paint
 *   (or erase) into it.
 * - Target: on an adjustment layer its mask; otherwise the first creating gesture adds a new
 *   adjustment layer ("Tone 1") above the active layer with the component, as ONE step, or edits
 *   "This layer's mask".
 * - Pins select components; the selected one shows its handles. Two fingers with at least one on
 *   the selected component's box scale and rotate it (§4.7). The X / Y strip moves its pin.
 * - Every edit is one undo step (a created component, a handle drag on release, a brush stroke, a
 *   sheet change); the tool has no ✓ / ✕. Handle drags preview at reduced resolution through a
 *   render override; brush strokes paint the mask directly (tiles recorded as they go).
 * - A red overlay shows the mask's coverage for 1.2 s after every edit, or always (👁).
 */
class MaskTool(controller: EditorController) : Tool(controller), PositionedTool {
    override val id = ToolId.MASK

    init {
        // The "Safe compositing" kill switch takes effect as soon as the editor starts (the tools
        // are created then), whether or not the Masks tool is ever used.
        AdjustmentStage.safeCompositing = controller.settings.safeCompositing
    }

    /** Component kinds the strip can add. */
    enum class Kind(val label: String) { LINEAR("Linear"), RADIAL("Radial"), BRUSH("Brush") }

    /** Where a creating gesture goes while the active layer is not an adjustment layer. */
    sealed interface Target {
        /** A new adjustment layer with this effect, above the active layer. */
        data class NewAdjustment(val filterId: String) : Target
        /** The active layer's own (editable) mask. */
        data object ThisLayer : Target
    }

    // ------------------------------------------------------------------ observable state

    /** The kind the next creating gesture adds (null: nothing armed). */
    var armed by mutableStateOf<Kind?>(null)
        private set

    /** The selected component of the edited spec (null: none). */
    var selectedId by mutableStateOf<Long?>(null)
        private set

    /** Mode of new components (and the strip's mode chips while nothing is selected). */
    var newMode by mutableStateOf(MaskMode.ADD)

    /** Brush strokes take coverage away instead of adding it. */
    var brushErase by mutableStateOf(false)

    /** Target of creating gestures on non-adjustment layers. */
    var target by mutableStateOf<Target>(Target.NewAdjustment(AdjustmentEffects.DEFAULT_ID))
        private set

    /** The Adjust sheet is open. */
    var adjustOpen by mutableStateOf(false)

    /** The Components sheet is open. */
    var componentsOpen by mutableStateOf(false)

    /** Bumped to make the Adjust… chip pulse (a new adjustment layer was made here). */
    var adjustPulse by mutableIntStateOf(0)
        private set

    /** The last [adjustPulse] the strip has shown (it pulses and scrolls to the chip once per new layer). */
    internal var adjustPulseShown = 0

    /** Layer whose painted mask would be replaced by an editable one (the question is shown). */
    var replacePrompt by mutableStateOf<Layer?>(null)
        private set

    /** The filter browser is asked for ("Apply a filter through this mask…"). */
    var filterBrowserOpen by mutableStateOf(false)

    /** The overlay stays on (👁; AppSettings.maskOverlayAlways). */
    var overlayAlways by mutableStateOf(controller.settings.maskOverlayAlways)
        private set

    /** The spec shown while an edit is live (null: the layer's own). */
    private var liveSpec by mutableStateOf<MaskSpec?>(null)

    /** Bumped on every committed change of the edited mask (Compose reads it). */
    var revision by mutableIntStateOf(0)
        private set

    private var replaceConfirmedFor: Layer? = null
    private var overlayUntil = 0L
    private var hintShown = false

    /** The reduced-resolution preview of live edits (internal: tests compare it with full renders). */
    internal val preview = MaskPreview(controller)
    private val tint = MaskTint()
    private var cache: MaskBrushCache? = null

    // ------------------------------------------------------------------ what is edited

    /** The layer whose mask the tool edits; null when a creating gesture adds a new adjustment layer. */
    val editLayer: Layer?
        get() {
            controller.layersVersion
            val l = controller.activeLayer
            return when {
                l.isAdjustmentLayer -> l
                target == Target.ThisLayer -> l
                else -> null
            }
        }

    /**
     * The committed spec of [editLayer]: its spec, an empty one for a layer without a mask (or one
     * whose painted mask the user agreed to replace), null for a painted mask (or no edit layer).
     */
    val spec: MaskSpec?
        get() {
            revision
            return specOf(editLayer)
        }

    private fun specOf(l: Layer?): MaskSpec? = when {
        l == null -> null
        l.mask == null -> MaskSpec()
        l.maskSpec != null -> l.maskSpec
        replaceConfirmedFor === l -> MaskSpec()
        else -> null
    }

    /** The spec as shown (live while an edit is in progress). */
    val displaySpec: MaskSpec? get() = liveSpec ?: spec

    /** The selected component (as shown). */
    val selected: MaskComponent? get() = selectedId?.let { id -> displaySpec?.components?.firstOrNull { it.id == id } }

    /** The edited layer has a painted mask that must be replaced before components can be added. */
    val needsReplace: Boolean
        get() {
            val l = editLayer ?: return false
            return l.mask != null && l.maskSpec == null && replaceConfirmedFor !== l
        }

    /** Effect of a new adjustment layer (the target's, else Tone). */
    val newEffectId: String get() = (target as? Target.NewAdjustment)?.filterId ?: AdjustmentEffects.DEFAULT_ID

    // ------------------------------------------------------------------ strip commands

    /** Arms [kind] for the next creating gesture (again: disarms). */
    fun arm(kind: Kind?) {
        armed = if (armed == kind) null else kind
        // An armed kind adds a NEW component (a selected brush component keeps painting without it).
        if (armed != null) selectedId = null
        // A new brush part starts by painting: there is nothing to erase from yet.
        brushErase = false
        controller.invalidateOverlay()
    }

    /** Selects component [id] (null: none). */
    fun select(id: Long?) {
        selectedId = id
        if (id != null) armed = null
        controller.invalidateOverlay()
    }

    /** Chooses where creating gestures go on non-adjustment layers. */
    fun chooseTarget(t: Target) {
        target = t
        selectedId = null
        if (t == Target.ThisLayer) {
            val l = controller.activeLayer
            if (l.mask != null && l.maskSpec == null && replaceConfirmedFor !== l && !l.isAdjustmentLayer) replacePrompt = l
        }
        revision++
        controller.invalidateOverlay()
    }

    /**
     * "Replace with an editable mask" (the Components sheet): asks the question for the edited
     * layer's painted mask — an adjustment layer's too (choosing a target never asks for those).
     */
    fun requestReplace() {
        val l = editLayer ?: return
        if (needsReplace) replacePrompt = l
    }

    /** "Replace the painted mask with an editable one?" answered. */
    fun answerReplace(replace: Boolean) {
        val l = replacePrompt
        replacePrompt = null
        if (replace && l != null) replaceConfirmedFor = l
        else if (!replace && target == Target.ThisLayer && !controller.activeLayer.isAdjustmentLayer) target = Target.NewAdjustment(AdjustmentEffects.DEFAULT_ID)
        revision++
    }

    /** Sets the mode of the selected component (one step), or of new components. */
    fun setMode(mode: MaskMode) {
        val c = selected
        if (c == null) { newMode = mode; return }
        if (c.mode == mode) return
        updateComponent(MaskGeometry.withCommon(c, mode = mode), "Mask mode")
    }

    /** Inverts the selected component (one step). */
    fun toggleInvertSelected() {
        val c = selected ?: return
        updateComponent(MaskGeometry.withCommon(c, invert = !c.invert), "Invert mask component")
    }

    /** The overlay on / off (remembered). */
    fun toggleOverlay() {
        overlayAlways = !overlayAlways
        controller.settings.maskOverlayAlways = overlayAlways
        controller.invalidateOverlay()
    }

    /** Opens the Adjust sheet (for an adjustment layer). */
    fun openAdjust() {
        if (!controller.activeLayer.isAdjustmentLayer) {
            controller.toast("Pick an adjustment layer, or add a mask to make one")
            return
        }
        adjustOpen = true
    }

    /** Draws the chip pulse once more (a new adjustment layer). */
    private fun pulseAdjust() { adjustPulse++ }

    /** The edited mask changed elsewhere (a sheet action): what shows it is refreshed. */
    fun touch() {
        revision++
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ spec edits (sheets, strip)

    /** Replaces the selected (or any) component [c] (same id) as one step [label]. */
    fun updateComponent(c: MaskComponent, label: String) {
        val s = spec ?: return
        commitSpec(MaskGeometry.replaced(s, c.id, c), label)
    }

    /**
     * Shows [s] (the edited layer's next spec) live without a step (a slider being dragged);
     * [moved]: a brush component moved by an affine map since the live edit began.
     */
    fun previewSpec(s: MaskSpec, moved: Pair<Long, MaskGeometry.Affine>? = null) {
        val base = spec ?: return
        val layer = editLayer ?: return
        if (!preview.isActive) preview.begin(layer, base, cache)
        liveSpec = s
        preview.update(s, moved)
        flashOverlay()
    }

    /** Ends a live edit: [s] becomes the edited layer's spec as one step [label] (nothing when unchanged). */
    fun commitSpec(s: MaskSpec, label: String): Boolean {
        val base = spec
        val layer = editLayer
        preview.end()
        liveSpec = null
        if (layer == null || base == null) return false
        if (s == base) return true
        val region = MaskSpecs.changedRegion(base, s, controller.doc.width, controller.doc.height)
        val ok = MaskEdits.apply(controller, layer, s, label, region, brushSource())
        if (ok && replaceConfirmedFor === layer) replaceConfirmedFor = null
        if (ok) hintIfMaskOff(layer)
        revision++
        flashOverlay()
        return ok
    }

    /** The layer whose turned-off mask was pointed out (once per layer while the tool is active). */
    private var maskOffHinted: Layer? = null

    /**
     * An edit of a mask that is turned off (the layers window's mask switch) shows nothing on the
     * canvas: say so once.
     */
    private fun hintIfMaskOff(layer: Layer) {
        if (layer.mask == null || layer.maskEnabled || maskOffHinted === layer) return
        maskOffHinted = layer
        controller.toast("The mask of \"${layer.name}\" is turned off: turn it on in the layers window to see it")
    }

    /** Throws a live edit away. */
    fun cancelSpecPreview() {
        if (liveSpec == null && !preview.isActive) return
        preview.end()
        liveSpec = null
    }

    /** Deletes component [id] (one step). */
    fun deleteComponent(id: Long) {
        val s = spec ?: return
        if (s.components.none { it.id == id }) return
        if (selectedId == id) selectedId = null
        commitSpec(s.copy(components = s.components.filter { it.id != id }), "Delete mask component")
    }

    /** Duplicates component [id] right above it (one step; the copy is selected). */
    fun duplicateComponent(id: Long) {
        val s = spec ?: return
        val i = s.components.indexOfFirst { it.id == id }
        if (i < 0) return
        val newId = MaskGeometry.nextId(s)
        val copy = MaskGeometry.withId(s.components[i], newId)
        val list = s.components.toMutableList().apply { add(i + 1, copy) }
        if (commitSpec(s.copy(components = list, nextId = newId + 1), "Duplicate mask component")) selectedId = newId
    }

    // ------------------------------------------------------------------ gestures

    private sealed class Gesture

    /** A tap or drag on nothing (deselects on a tap). */
    private class Idle(val start: Vec2) : Gesture() { var moved = false }

    /**
     * A finger down on the pin of component [compId] while [kind] is armed (or a brush component
     * paints): a tap selects the pin's component, a drag does [kind] from [start], a long press
     * moves the component.
     */
    private class PinTap(val kind: Kind, val compId: Long, val start: Vec2, val pressure: Float) : Gesture()

    /** Creating a linear or radial component by dragging. */
    private class Create(val kind: Kind, val start: Vec2, val layer: Layer?, val base: MaskSpec, val compId: Long) : Gesture() {
        var moved = false
        var spec: MaskSpec = base
    }

    /** Dragging a handle (or the pin) of a component ([wasSelected]: a tap on its pin deselects it). */
    private class Drag(val layer: Layer, val base: MaskSpec, val startComp: MaskComponent, val handle: MaskHandles.Kind, val start: Vec2, val wasSelected: Boolean = true) : Gesture() {
        var moved = false
        var spec: MaskSpec = base
    }

    /**
     * A brush stroke into component [compId]. [live]: painted into the mask directly ([rec]
     * records the tiles); otherwise previewed at reduced resolution and committed at the end.
     */
    private class Paint(
        val layer: Layer?,
        val base: MaskSpec,
        val compId: Long,
        val baseComp: BrushMask?,
        val newComp: BrushMask,
        val erase: Boolean,
        val size: Float,
        val hardness: Float,
        val flow: Float,
        val live: Boolean,
        val rec: PixelEditRecorder?,
    ) : Gesture() {
        val xs = FloatList(); val ys = FloatList(); val ps = FloatList()
        /** The layer's content version when the stroke began. */
        val versionAtStart: Long = layer?.contentVersion ?: 0L
        var renderedDabs = 0
        var lastDab: RectF? = null
        val touched = Rect()
        var spec: MaskSpec = base
    }

    private class FloatList {
        var data = FloatArray(64); var size = 0
        fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    private var gesture: Gesture? = null

    /** A render override that leaves the layer as it is, so the compositor knows its mask is being painted. */
    private var paintOverride: LayerRenderOverride? = null

    override fun onDown(p: ToolPoint) {
        if (gesture != null) onCancel()
        // A move typed or slid in the X / Y strip that wasn't ended is recorded before the finger
        // does anything else (its preview would otherwise be replaced and lost).
        endStripEdit()
        val pos = Vec2(p.x, p.y)
        val t = controller.viewTransform
        val layer = editLayer
        val s = spec
        val sel = selected
        // What a drag away from the handles does: add the armed kind, or paint into the selected
        // brush component (Brush / Erase).
        val kind = armed ?: if (sel is BrushMask) Kind.BRUSH else null
        if (kind == null) {
            // The selected component's handles.
            if (layer != null && s != null && sel != null) {
                val h = MaskHandles.hit(sel, pos, t)
                if (h != null) {
                    if (!MaskEdits.usable(controller, layer)) return
                    gesture = Drag(layer, s, sel, h, pos)
                    return
                }
            }
            // A pin selects its component (and dragging it moves the component).
            if (layer != null && s != null) {
                val hit = MaskHandles.hitPin(s, pos, t, selectedId)
                if (hit != null) {
                    val was = hit.id == selectedId
                    selectedId = hit.id
                    controller.invalidateOverlay()
                    if (!MaskEdits.usable(controller, layer)) return
                    gesture = Drag(layer, s, hit, MaskHandles.Kind.PIN, pos, was)
                    return
                }
            }
            gesture = Idle(pos)
            return
        }
        // Creating or painting starts anywhere, on pins too (a brush component's pin sits in the
        // middle of what it paints): a pin under the finger is selected by a TAP, and moved after
        // a long press; a drag does what is armed.
        if (layer != null && s != null) {
            val hit = MaskHandles.hitPin(s, pos, t, selectedId)
            if (hit != null) {
                gesture = PinTap(kind, hit.id, pos, p.pressure)
                controller.invalidateOverlay()
                return
            }
        }
        begin(kind, pos, p.pressure)
    }

    /** Starts what a drag of [kind] from [pos] does: a creating drag or a brush stroke. */
    private fun begin(kind: Kind, pos: Vec2, pressure: Float) {
        val layer = editLayer
        if (needsReplace) {
            replacePrompt = layer
            return
        }
        if (layer == null) {
            if (!controller.canAddAdjustmentLayer) {
                controller.toast("Layer limit reached (${controller.maxLayers}) for this canvas size")
                return
            }
        } else if (!MaskEdits.usable(controller, layer)) {
            return
        }
        val base = spec ?: MaskSpec()
        when (kind) {
            Kind.LINEAR, Kind.RADIAL -> gesture = Create(kind, pos, layer, base, MaskGeometry.nextId(base))
            Kind.BRUSH -> {
                // Erasing takes coverage away from a brush part: a NEW part has none, so an erase
                // stroke there would only add an invisible part (and a step, or even a new
                // adjustment layer). Say what to do instead.
                if (brushErase && selected !is BrushMask) {
                    controller.toast(ERASE_HINT)
                    return
                }
                startPaint(layer, base, pos, pressure)
            }
        }
    }

    /**
     * Held still on a pin: the pin's component is selected and the finger moves it (the way to
     * move a brush component with one finger; two fingers and the X / Y strip work too).
     */
    override fun onLongPress(p: ToolPoint): Boolean {
        val g = gesture as? PinTap ?: return false
        val layer = editLayer ?: return false
        val s = spec ?: return false
        val comp = s.components.firstOrNull { it.id == g.compId } ?: return false
        armed = null
        selectedId = comp.id
        controller.invalidateOverlay()
        if (!MaskEdits.usable(controller, layer)) {
            gesture = null
            return true
        }
        gesture = Drag(layer, s, comp, MaskHandles.Kind.PIN, g.start, wasSelected = false)
        return true
    }

    override fun onMove(p: ToolPoint) {
        val pos = Vec2(p.x, p.y)
        when (val g = gesture) {
            is PinTap -> if (movedEnough(g.start, pos)) {
                // A drag that started on a pin: what is armed (or painting) from where it began.
                gesture = null
                begin(g.kind, g.start, g.pressure)
                if (gesture != null) onMove(p)
            }
            is Idle -> if (!g.moved && movedEnough(g.start, pos)) {
                g.moved = true
                if (!hintShown) {
                    hintShown = true
                    controller.toast("Tap + Linear, + Radial or + Brush, then drag on the canvas")
                }
            }
            is Create -> {
                if (!g.moved && !movedEnough(g.start, pos)) return
                if (!g.moved) {
                    g.moved = true
                    preview.begin(g.layer, g.base, cache)
                }
                g.spec = MaskGeometry.added(g.base, created(g.kind, g.start, pos, g.compId))
                liveSpec = g.spec
                preview.update(g.spec)
                flashOverlay()
            }
            is Drag -> {
                if (!g.moved && !movedEnough(g.start, pos)) return
                if (!g.moved) {
                    g.moved = true
                    preview.begin(g.layer, g.base, cache)
                }
                val comp = MaskHandles.dragged(g.startComp, g.handle, g.start, pos)
                g.spec = MaskGeometry.replaced(g.base, comp.id, comp)
                liveSpec = g.spec
                val moved = if (g.startComp is BrushMask && g.handle == MaskHandles.Kind.PIN) g.startComp.id to MaskGeometry.Affine.translate(pos.x - g.start.x, pos.y - g.start.y) else null
                preview.update(g.spec, moved)
                flashOverlay()
            }
            is Paint -> paintTo(g, pos, p.pressure)
            null -> {}
        }
    }

    override fun onUp(p: ToolPoint) {
        val g = gesture ?: return
        val pos = Vec2(p.x, p.y)
        when (g) {
            is PinTap -> {
                gesture = null
                // A tap on a pin: its component is selected (what was armed is put away); a tap on
                // the selected one deselects it.
                if (g.compId == selectedId) {
                    selectedId = null
                } else {
                    armed = null
                    selectedId = g.compId
                }
                controller.invalidateOverlay()
            }
            is Idle -> {
                gesture = null
                if (!g.moved && selectedId != null) { selectedId = null; controller.invalidateOverlay() }
            }
            is Create -> {
                gesture = null
                if (!g.moved) {
                    preview.end()
                    controller.toast(if (g.kind == Kind.LINEAR) "Drag from where the effect is full to where it ends" else "Drag from the centre outward")
                    return
                }
                g.spec = MaskGeometry.added(g.base, created(g.kind, g.start, pos, g.compId))
                commitCreated(g.layer, g.spec, g.compId, if (g.kind == Kind.LINEAR) "Mask: linear" else "Mask: radial")
                armed = null
            }
            is Drag -> {
                gesture = null
                if (!g.moved) {
                    preview.end()
                    liveSpec = null
                    // A tap on the selected component's pin deselects it.
                    if (g.handle == MaskHandles.Kind.PIN && g.wasSelected) selectedId = null
                    controller.invalidateOverlay()
                    return
                }
                val comp = MaskHandles.dragged(g.startComp, g.handle, g.start, pos)
                commitSpec(MaskGeometry.replaced(g.base, comp.id, comp), if (g.handle == MaskHandles.Kind.PIN) "Move mask" else "Edit mask")
            }
            is Paint -> {
                paintTo(g, pos, p.pressure)
                gesture = null
                finishPaint(g)
            }
        }
    }

    override fun onCancel() {
        val g = gesture ?: return
        gesture = null
        if (g is Paint && g.live) {
            g.rec?.abort()
            removePaintOverride()
            if (!g.touched.isEmpty) {
                controller.invalidateDoc(g.touched)
                g.layer?.mask?.let { tint.refresh(it, g.touched) }
            }
        }
        preview.end()
        liveSpec = null
        controller.invalidateOverlay()
    }

    private fun movedEnough(a: Vec2, b: Vec2): Boolean {
        val t = controller.viewTransform
        return t.docToScreen(a).distanceTo(t.docToScreen(b)) >= t.dp(4f)
    }

    /** The component a creating drag from [a] to [b] makes. */
    private fun created(kind: Kind, a: Vec2, b: Vec2, id: Long): MaskComponent = when (kind) {
        Kind.LINEAR -> LinearMask(id, mode = newMode, x0 = a.x, y0 = a.y, x1 = b.x, y1 = b.y)
        Kind.RADIAL -> {
            val r = max(2f, a.distanceTo(b))
            RadialMask(id, mode = newMode, cx = a.x, cy = a.y, rx = r, ry = r)
        }
        Kind.BRUSH -> BrushMask(id, mode = newMode)
    }

    /**
     * A created component becomes real: a new adjustment layer with it (one step), or the edited
     * layer's spec. The component is selected and the Adjust chip pulses for a new layer.
     */
    private fun commitCreated(layer: Layer?, newSpec: MaskSpec, compId: Long, label: String) {
        preview.end()
        liveSpec = null
        if (layer == null) {
            val filter = FilterRegistry.byId(newEffectId)?.takeIf { it.isAdjustmentCapable } ?: FilterRegistry.byId(AdjustmentEffects.DEFAULT_ID)
            val made = controller.addAdjustmentLayer(AdjustmentEffects.defaultSpec(filter, controller.color), newSpec, label)
            if (made != null) {
                selectedId = compId
                pulseAdjust()
            }
        } else {
            if (MaskEdits.apply(controller, layer, newSpec, label, null, brushSource())) {
                selectedId = compId
                if (replaceConfirmedFor === layer) replaceConfirmedFor = null
                hintIfMaskOff(layer)
            }
        }
        revision++
        flashOverlay()
    }

    // ------------------------------------------------------------------ brush strokes

    private fun startPaint(layer: Layer?, base: MaskSpec, pos: Vec2, pressure: Float) {
        val preset = controller.maskBrush
        val sel = selected as? BrushMask
        val baseComp = if (armed == Kind.BRUSH && sel == null) null else sel
        val compId = baseComp?.id ?: MaskGeometry.nextId(base)
        val newComp = baseComp ?: (created(Kind.BRUSH, pos, pos, compId) as BrushMask)
        // A new component that changes the mask before it has strokes (INTERSECT or inverted)
        // isn't a local change: it goes through the preview like a handle drag.
        val local = layer != null && layer.mask != null && layer.maskSpec != null &&
            (baseComp != null || MaskSpecRenderer.influence(newComp, controller.doc.width, controller.doc.height) == null)
        val rec = if (local) controller.beginEdit(layer, EditTarget.MASK) else null
        val size = preset.size.coerceIn(1f, 5000f)
        val g = Paint(layer, base, compId, baseComp, newComp, brushErase, size, preset.hardness, preset.opacity, local, rec)
        if (local) {
            if (baseComp != null && cache?.serves(baseComp) != true) ensureCache()?.full(baseComp)
            installPaintOverride(layer)
        } else {
            // An adjustment layer without a mask previews its future mask too (the effect shows
            // only where the stroke paints, as it will after the stroke).
            preview.begin(layer?.takeIf { it.mask != null || it.isAdjustmentLayer }, base, cache)
        }
        gesture = g
        paintTo(g, pos, pressure)
    }

    private fun paintTo(g: Paint, pos: Vec2, pressure: Float) {
        if (!pos.x.isFinite() || !pos.y.isFinite()) return
        val n = g.xs.size
        if (n > 0 && g.xs.data[n - 1] == pos.x && g.ys.data[n - 1] == pos.y) return
        g.xs.add(pos.x); g.ys.add(pos.y); g.ps.add(pressure)
        val stroke = strokeOf(g)
        val comp = g.newComp.copy(strokes = (g.baseComp?.strokes ?: emptyList()) + stroke)
        g.spec = if (g.baseComp == null) g.base.copy(components = g.base.components + comp, nextId = g.compId + 1) else MaskGeometry.replaced(g.base, g.compId, comp)
        val dabs = MaskBrushRaster.dabsOf(stroke)
        if (!g.live) {
            // Only the samples under the new dabs change from one frame to the next (each stroke
            // is accumulated dab by dab, in order): the preview re-renders just there. The first
            // frame adds the part itself, which can change the whole mask (Intersect, inverted).
            val first = g.renderedDabs == 0
            val region = newDabsRegion(g, dabs)
            liveSpec = g.spec
            preview.update(g.spec, region = if (first) null else region ?: Rect())
            flashOverlay()
            return
        }
        val region = newDabsRegion(g, dabs) ?: return
        renderLive(g, stroke, dabs, region)
        liveSpec = g.spec
    }

    /**
     * The document area (clamped to the document; null = none) that the dabs added to [dabs]
     * since the last frame can change — plus the previous last dab, which may have been an end
     * dab that moved on — and remembers what was rendered.
     */
    private fun newDabsRegion(g: Paint, dabs: MaskBrushRaster.Dabs): Rect? {
        if (dabs.count == 0) return null
        val from = max(0, g.renderedDabs - 1)
        var l = Float.POSITIVE_INFINITY; var tp = Float.POSITIVE_INFINITY; var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (k in from until dabs.count) {
            l = min(l, dabs.x[k]); r = max(r, dabs.x[k]); tp = min(tp, dabs.y[k]); b = max(b, dabs.y[k])
        }
        val rad = dabs.radius + 1f
        val box = RectF(l - rad, tp - rad, r + rad, b + rad)
        g.lastDab?.let { box.union(it) }
        val last = dabs.count - 1
        g.lastDab = RectF(dabs.x[last] - rad, dabs.y[last] - rad, dabs.x[last] + rad, dabs.y[last] + rad)
        g.renderedDabs = dabs.count
        val region = Rect(floor(box.left).toInt(), floor(box.top).toInt(), ceil(box.right).toInt(), ceil(box.bottom).toInt())
        return region.takeIf { it.intersect(0, 0, controller.doc.width, controller.doc.height) }
    }

    private fun strokeOf(g: Paint): MaskStroke =
        MaskStroke(g.erase, g.size, g.hardness, g.flow, PackedPoints(g.xs.toArray(), g.ys.toArray(), g.ps.toArray()))

    /** Renders the edited spec (with the stroke so far) into the mask within [region]. */
    private fun renderLive(g: Paint, stroke: MaskStroke, dabs: MaskBrushRaster.Dabs, region: Rect) {
        val layer = g.layer ?: return
        val mask = layer.mask ?: return
        val rec = g.rec ?: return
        val base = brushSource()
        val source = BrushSource { comp, grid ->
            if (comp.id == g.compId) {
                val prior = g.baseComp?.let { bc -> base?.coverage(bc, grid) ?: MaskBrushRaster.rasterize(bc.strokes, grid) } ?: ByteArray(grid.size)
                MaskBrushRaster.apply(listOf(dabs), grid, prior)
                prior
            } else {
                base?.coverage(comp, grid)
            }
        }
        val w = region.width(); val h = region.height()
        if (liveBuffer.size < w * h) liveBuffer = IntArray(w * h)
        val px = liveBuffer
        MaskSpecRenderer.renderGrid(g.spec, SampleGrid.pixels(region.left, region.top, w, h), px, w, source)
        rec.touch(region)
        mask.setPixels(px, 0, w, region.left, region.top, w, h)
        g.touched.union(region)
        tint.refresh(mask, region)
        controller.invalidateDoc(region)
        flashOverlay()
    }

    /** Reused pixels of live brush renders (a stroke's dab boxes; freed with the tool). */
    private var liveBuffer = IntArray(0)

    private fun finishPaint(g: Paint) {
        val label = when {
            g.baseComp == null -> "Mask: brush"
            g.erase -> "Erase mask"
            else -> "Brush mask"
        }
        if (armed == Kind.BRUSH) armed = null
        if (!g.live) {
            commitCreated(g.layer, g.spec, g.compId, label)
            return
        }
        val layer = g.layer ?: return
        val rec = g.rec ?: return
        removePaintOverride()
        MaskEdits.commitStroke(controller, rec, layer, g.spec, label)
        hintIfMaskOff(layer)
        // The overlay's copy already shows the stroke (patched while painting).
        layer.mask?.let { tint.adopt(layer, it, g.versionAtStart) }
        liveSpec = null
        selectedId = g.compId
        // The cache follows the component (incrementally: the strokes so far are the same objects).
        (g.spec.components.firstOrNull { it.id == g.compId } as? BrushMask)?.let { bc -> cache?.takeIf { it.serves(bc) }?.full(bc) }
        revision++
        flashOverlay()
    }

    private fun installPaintOverride(layer: Layer) {
        val ov = object : LayerRenderOverride, MaskCoverageHint {
            override val layer: Layer = layer
            override fun drawContent(canvas: Canvas): Boolean = false

            // The mask shows the stroke so far: it can be non-black only within the coverage of
            // the spec with it (strokes only grow, so earlier frames' pixels are inside too).
            private var coverageOf: MaskSpec? = null
            private var coverage: Rect? = null

            override fun maskCoverage(): Rect? {
                val s = (gesture as? Paint)?.spec ?: displaySpec ?: return Rect(0, 0, controller.doc.width, controller.doc.height)
                if (s !== coverageOf) {
                    coverageOf = s
                    coverage = MaskSpecs.coverageBounds(s, controller.doc.width, controller.doc.height)
                }
                return coverage?.let { Rect(it) }
            }
        }
        paintOverride = ov
        controller.renderOverride = ov
    }

    private fun removePaintOverride() {
        val ov = paintOverride ?: return
        paintOverride = null
        if (controller.renderOverride === ov) controller.renderOverride = null
    }

    private fun ensureCache(): MaskBrushCache? {
        val w = controller.doc.width; val h = controller.doc.height
        if (w.toLong() * h > MaskBrushCache.MAX_PIXELS) return null
        val c = cache?.takeIf { it.width == w && it.height == h } ?: MaskBrushCache(w, h)
        cache = c
        return c
    }

    /** The brush coverage the tool's cache can give (null: none). */
    private fun brushSource(): BrushSource? = cache

    // ------------------------------------------------------------------ two fingers

    private class Pinch(val layer: Layer, val base: MaskSpec, val comp: MaskComponent, val focus: Vec2)

    private var pinch: Pinch? = null

    override fun onTwoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        val c = selected ?: return false
        val layer = editLayer ?: return false
        if (spec == null) return false
        if (layer.locked || !layer.visible) return false
        val t = controller.viewTransform
        val accepted = if (c is LinearMask) {
            val len = Vec2(c.x1 - c.x0, c.y1 - c.y0).length
            PinchTargeting.acceptsSegment(a, b, Vec2(c.x0, c.y0), Vec2(c.x1, c.y1), len / 2f, t)
        } else {
            val corners = MaskGeometry.boxCorners(c) ?: return false
            PinchTargeting.acceptsQuad(a, b, corners.map { Vec2(it.first, it.second) }, t)
        }
        if (!accepted) return false
        gesture?.let { onCancel() }
        // A pending X / Y strip move is recorded first; the pinch starts from the spec as it is now.
        endStripEdit()
        val now = spec ?: return false
        val comp = now.components.firstOrNull { it.id == c.id } ?: return false
        pinch = Pinch(layer, now, comp, focus)
        preview.begin(layer, now, cache)
        return true
    }

    override fun onTwoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {
        val pz = pinch ?: return
        if (!translation.x.isFinite() || !translation.y.isFinite() || !scale.isFinite() || scale <= 0f || !rotationDeg.isFinite()) return
        val m = MaskGeometry.Affine.similarity(pz.focus.x, pz.focus.y, scale, rotationDeg, translation.x, translation.y)
        val comp = MaskGeometry.transformed(pz.comp, m) ?: return
        val s = MaskGeometry.replaced(pz.base, comp.id, comp)
        liveSpec = s
        preview.update(s, if (comp is BrushMask) comp.id to m else null)
        flashOverlay()
    }

    override fun onTwoFingerEnd(cancelled: Boolean) {
        val pz = pinch ?: return
        pinch = null
        val s = liveSpec
        if (cancelled || s == null) {
            preview.end()
            liveSpec = null
            return
        }
        commitSpec(s, "Edit mask")
    }

    // ------------------------------------------------------------------ X / Y strip

    private var stripBase: Pair<MaskSpec, MaskComponent>? = null

    private val positionImpl = object : ObjectPosition {
        override val position: Vec2?
            get() {
                val c = selected ?: return null
                return MaskGeometry.pin(c)?.let { Vec2(it.first, it.second) }
            }

        override val label: String
            get() {
                val c = selected ?: return "Mask"
                val s = displaySpec ?: return MaskGeometry.kindName(c)
                return MaskGeometry.displayName(s, c)
            }

        override fun setPosition(x: Float?, y: Float?) {
            val c = selected ?: return
            val layer = editLayer ?: return
            if (layer.locked || !layer.visible) return
            val started = stripBase ?: run {
                val s = spec ?: return
                val sc = s.components.firstOrNull { it.id == c.id } ?: return
                (s to sc).also { stripBase = it }
            }
            val (base, startComp) = started
            val pin = MaskGeometry.pin(startComp) ?: return
            val cur = MaskGeometry.pin(c) ?: pin
            val nx = x ?: cur.first; val ny = y ?: cur.second
            if (!nx.isFinite() || !ny.isFinite()) return
            val m = MaskGeometry.Affine.translate(nx - pin.first, ny - pin.second)
            val moved = MaskGeometry.transformed(startComp, m) ?: return
            previewSpec(MaskGeometry.replaced(base, moved.id, moved), if (moved is BrushMask) moved.id to m else null)
        }

        override fun endPositionEdit() {
            stripBase ?: return
            stripBase = null
            val s = liveSpec ?: return
            commitSpec(s, "Move mask")
        }
    }

    override val objectPosition: ObjectPosition?
        get() = if (selected != null && editLayer != null) positionImpl else null

    /** Records a strip move that is still being previewed (nothing when there is none). */
    private fun endStripEdit() {
        if (stripBase != null) positionImpl.endPositionEdit()
    }

    // ------------------------------------------------------------------ Adjust sheet

    private var adjustEdit: AdjustmentEdit? = null

    /**
     * The live edit of [layer]'s effect. No side effects (the Adjust sheet asks for it while it
     * composes): a previous edit with pending changes stays registered as a deferred step, so it
     * is still recorded first — before any other step and before undo / redo.
     */
    fun adjustmentEdit(layer: Layer): AdjustmentEdit {
        adjustEdit?.let { if (it.layer === layer) return it }
        return AdjustmentEdit(controller, layer).also { adjustEdit = it }
    }

    /** Records the Adjust sheet's pending changes as their step (sheet closed or minimized). */
    fun flushAdjustment() {
        adjustEdit?.flush()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onActivate() {
        val l = controller.activeLayer
        // A layer with an editable mask opens it; otherwise creating gestures make adjustments.
        if (!l.isAdjustmentLayer) {
            target = if (l.maskSpec != null && l.mask != null) Target.ThisLayer else Target.NewAdjustment(newEffectId)
        }
        if (selected == null) selectedId = null
        if (replaceConfirmedFor != null && replaceConfirmedFor !== l) replaceConfirmedFor = null
        revision++
    }

    override fun onSelected() {
        adjustOpen = false
        componentsOpen = false
        filterBrowserOpen = false
    }

    override fun onDeactivate() {
        onCancel()
        pinch?.let { pinch = null; preview.end(); liveSpec = null }
        // A strip move still being previewed is recorded on the layer it was made on (another
        // layer is about to become active, or another tool); any other live edit (a sheet slider
        // never released) is thrown away, so nothing shown belongs to the previous layer.
        endStripEdit()
        cancelSpecPreview()
        flushAdjustment()
        adjustEdit = null
        cache?.clear()
        cache = null
        tint.release()
        // The preview bitmap (up to ~1.2 MP) is made again the next time something is dragged.
        preview.release()
        liveBuffer = IntArray(0)
        selectedId = null
        armed = null
        maskOffHinted = null
    }

    override fun onDispose() {
        preview.release()
        tint.release()
        cache?.clear()
        cache = null
    }

    // ------------------------------------------------------------------ overlay

    private var overlayJob: Job? = null

    /** Shows the red coverage overlay for a moment (it is redrawn without it afterwards). */
    private fun flashOverlay() {
        overlayUntil = SystemClock.uptimeMillis() + OVERLAY_MS
        controller.invalidateOverlay()
        if (overlayJob?.isActive == true) return
        overlayJob = controller.scope.launch {
            while (true) {
                val wait = overlayUntil - SystemClock.uptimeMillis()
                if (wait <= 0) break
                delay(wait + 20)
            }
            controller.invalidateOverlay()
        }
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val showTint = overlayAlways || SystemClock.uptimeMillis() < overlayUntil || gesture != null || pinch != null
        if (showTint) {
            val pb = preview.bitmap
            val layer = editLayer
            val mask = layer?.mask
            when {
                preview.isActive && pb != null -> MaskPreview.drawTint(canvas, pb, preview.scale, t)
                // A reduced copy: the full mask would be uploaded again on every frame it changes.
                layer != null && mask != null && layer.maskEnabled -> tint.of(layer, mask)?.let { MaskPreview.drawTint(canvas, it, tint.scale, t) }
            }
        }
        // The brush ring follows the finger while painting.
        (gesture as? Paint)?.let { g ->
            val n = g.xs.size
            if (n > 0) {
                val sp = t.docToScreen(Vec2(g.xs.data[n - 1], g.ys.data[n - 1]))
                ring.strokeWidth = t.dp(1.5f)
                ring.color = 0xCC000000.toInt()
                canvas.drawCircle(sp.x, sp.y, g.size / 2f * t.zoom, ring)
                ring.strokeWidth = t.dp(0.75f)
                ring.color = -1
                canvas.drawCircle(sp.x, sp.y, g.size / 2f * t.zoom, ring)
            }
        }
        val s = displaySpec ?: return
        val sel = selected
        if (sel != null) MaskHandles.drawHandles(canvas, sel, t)
        MaskHandles.drawPins(canvas, s, selectedId, t)
    }

    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }

    internal companion object {
        const val OVERLAY_MS = 1200L

        /** Shown for an erase stroke with no brush part to erase from. */
        const val ERASE_HINT = "Erase works on a brush part: tap its B pin first, or paint with Subtract"
    }
}
