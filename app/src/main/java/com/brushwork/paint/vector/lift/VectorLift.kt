package com.brushwork.paint.vector.lift

import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.ObjectLift
import com.brushwork.paint.tools.transform.ObjectLiftProvider
import com.brushwork.paint.tools.transform.RefusingLiftProvider
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.MirrorLabels110
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.copies.CopyEdit
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.select.ObjectBounds
import com.brushwork.paint.vector.select.ObjectTouch
import com.brushwork.paint.vector.select.PendingRenders
import com.brushwork.paint.vector.select.TapCycle
import kotlinx.coroutines.Job
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max

/**
 * The Transform tool on vector layers (v1.5 §4.9, owned by A2): lifts objects (the object
 * selection, else what the pixel selection touches, else all) instead of pixels. Reached through
 * `VectorLayers.liftProvider`, which asks for the provider every time: each editor gets ONE
 * stable provider (it remembers the last tap, for cycling through overlapping objects) for as long
 * as the Transform tool holds it (its sessions keep it; the registry only refers to it weakly, so
 * a closed editor is never kept alive by it).
 *
 * A vector layer without any object (and no pixel selection) gets [RefusingLiftProvider]: there
 * is nothing to lift as objects, and the pixel path finds nothing either (the cache of an empty
 * vector layer is empty), so the Transform tool says "Nothing to transform on this layer" as on
 * any empty layer. With a pixel selection the object provider answers instead, so transparent
 * pixels are never lifted (committing them would turn the layer into a raster layer).
 */
object VectorLift {
    private val providers = WeakHashMap<EditorController, WeakReference<VectorLiftProvider>>()

    fun provider(c: EditorController): ObjectLiftProvider {
        val content = c.activeLayer.vector
        if ((content == null || content.objects.isEmpty()) && c.selection == null) return RefusingLiftProvider
        return providerOf(c)
    }

    /** This editor's object provider. */
    internal fun providerOf(c: EditorController): VectorLiftProvider =
        providers[c]?.get() ?: VectorLiftProvider(c).also { providers[c] = WeakReference(it) }

    /** The objects the Transform tool holds lifted right now in [c] (null when none). */
    internal fun activeLift(c: EditorController): VectorObjectLift? = providers[c]?.get()?.current?.takeIf { it.isOpen }

    /** A lift whose result is still being rendered in the background (shown where it goes meanwhile), or null. */
    internal fun landingLift(c: EditorController): VectorObjectLift? = providers[c]?.get()?.current?.takeIf { it.isLanding }

    /** v1.10 (R1): a drag, pinch or handle gesture of the Transform tool ended in [c] (the tap cycle starts again). */
    internal fun gestureEnded(c: EditorController) {
        providers[c]?.get()?.gestureEnded()
    }

    /** v1.10 (R1): the Transform tool of [c] closed (the tap cycle starts again; no side stays picked). */
    internal fun toolLeft(c: EditorController) {
        providers[c]?.get()?.toolLeft()
    }
}

/** Lifts the objects of one editor's vector layers for its Transform tool (see [VectorLift]). */
internal class VectorLiftProvider(private val c: EditorController) : ObjectLiftProvider {
    /** The lift handed to the Transform tool and not ended yet (also while its result lands). */
    var current: VectorObjectLift? = null
        private set

    /**
     * A tap on empty canvas asked for every object to be lifted next, whatever the pixel
     * selection touches. It holds only while nothing was selected since: the pixel selection is
     * still [selection] and no object is selected.
     */
    private class LiftAll(val selection: Selection?)

    private var liftAll: LiftAll? = null

    /**
     * What the last tap picked: object [id], whole ([side] -1) or one of its copies ([side] = k,
     * v1.10 R1), and the object [selection] it left, so a tap at the same spot goes on from there.
     */
    private class Tap(layer: Layer, val id: Long, val side: Int, val selection: Set<Long>) {
        private val ref = WeakReference(layer)
        val layer: Layer? get() = ref.get()
    }

    private var lastTap: Tap? = null

    /**
     * v1.10 (R1, J19): counts taps at the same spot, spatially only (no timer); any drag, pinch or
     * handle gesture in between ([gestureEnded]) and a tool switch ([toolLeft]) start it again.
     */
    private val cycle = TapCycle()

    /** v1.10 (R1): copy [k] of object [id] of a layer, picked alone by a tap (see [sidePick]). */
    internal class SidePick(layer: Layer, val id: Long, val k: Int) {
        private val ref = WeakReference(layer)
        val layer: Layer? get() = ref.get()
    }

    /**
     * v1.10 (R1): the side a tap picked; the lifts of that object alone (the object selection is
     * just it) lift that copy only. Transient: a tap elsewhere, all sides or a tool switch clears it.
     */
    internal var sidePick: SidePick? = null
        private set

    /** v1.10 (R1): a drag, pinch or handle gesture ended in the Transform tool: the next tap starts a new cycle. */
    internal fun gestureEnded() = cycle.reset()

    /** v1.10 (R1): the Transform tool closed: the next tap starts a new cycle, and no side stays picked. */
    internal fun toolLeft() {
        cycle.reset()
        lastTap = null
        sidePick = null
    }

    /**
     * The lift request being prepared (waiting for a render in flight, or for the objects a large
     * pixel selection touches); a newer request replaces it, and the replaced one never calls back
     * (the Transform tool already let go of it).
     */
    private var request: Any? = null

    /** The search for the objects a large pixel selection touches. */
    private var search: Job? = null

    /** The background search of a lift being prepared (what the busy overlay's Stop cancels), or null. */
    internal val searchJob: Job? get() = search

    /**
     * How a lift applies its result as one step: `vectors.update` (which may render in the
     * background and call back later). A test seam (e.g. to delay the callback, or to see the
     * shift hint of a whole-pixel move).
     */
    internal var update: (Layer, VectorContent, String, VectorLayers.ShiftHint?, (Boolean) -> Unit) -> Unit =
        { layer, after, label, shift, onDone -> c.vectors.update(layer, after, label, shift = shift, onDone = onDone) }

    override fun lift(layer: Layer, onReady: (ObjectLift?) -> Unit): Boolean {
        // A request that was still being prepared is replaced (the tool already let go of it).
        val ticket = Any()
        request = ticket
        search?.cancel()
        search = null
        val content = layer.vector
        if (content == null || c.doc.indexOf(layer) < 0) return false
        if (content.objects.isEmpty() && !PendingRenders.busy(c)) {
            c.toast(NOTHING_TO_TRANSFORM)
            return false
        }
        val v = c.vectors
        val la = liftAll
        liftAll = null
        val all = la != null && la.selection === c.selection && !(v.selectedLayer === layer && v.selectedIds.isNotEmpty())
        if (PendingRenders.busy(c)) {
            // An update is still rendering: lifting now would show (and later map) the objects
            // where they were before it. The lift starts as soon as it landed.
            PendingRenders.whenIdle(c) {
                if (request !== ticket) return@whenIdle
                if (!stillWanted(layer) || !liftNow(layer, all, ticket, onReady)) {
                    request = null
                    onReady(null)
                }
            }
            return true
        }
        val accepted = liftNow(layer, all, ticket, onReady)
        if (!accepted) request = null
        return accepted
    }

    /**
     * The Transform tool still waits for a lift of [layer]: current, on that layer, preparing
     * (it stops preparing when it lets go: ✓, ✕, another tool).
     */
    private fun stillWanted(layer: Layer): Boolean {
        if (c.activeToolId != ToolId.TRANSFORM || c.activeLayer !== layer || c.doc.indexOf(layer) < 0) return false
        return (c.currentTool as? TransformTool)?.isPreparing != false
    }

    /**
     * Lifts the object selection of [layer], else the objects the pixel selection touches, else
     * every object ([all]: every object whatever is selected). False = refused right away (the
     * caller reports it); [onReady] gets the lift, now or later.
     */
    private fun liftNow(layer: Layer, all: Boolean, ticket: Any, onReady: (ObjectLift?) -> Unit): Boolean {
        val content = layer.vector
        if (content == null || c.doc.indexOf(layer) < 0) return false
        if (content.objects.isEmpty()) {
            c.toast(NOTHING_TO_TRANSFORM)
            return false
        }
        val v = c.vectors
        // 1. The object selection (on this layer).
        if (!all && v.selectedLayer === layer) {
            val ids = v.selectedIds
            // v1.10 (R1): just the object a side of which was picked: that side alone.
            if (ids.isNotEmpty()) return begin(layer, ids, sideOf(layer, content, ids), onReady)
        }
        // 2. The objects the pixel selection touches (they become the object selection).
        val sel = c.selection
        if (!all && sel != null) {
            if (sel.isEmpty) {
                c.toast(SELECTION_TOUCHES_NOTHING)
                return false
            }
            var accepted = true
            var inline = true
            val job = ObjectTouch.run(
                c, content, sel, "Finding objects…",
                onFailed = { if (inline) accepted = false else if (request === ticket) { request = null; onReady(null) } },
                // Stopped (busy overlay) before it found them: the tool stops waiting.
                onCancelled = {
                    if (request === ticket) {
                        request = null
                        search = null
                        onReady(null)
                    }
                },
            ) { touched ->
                if (!inline) {
                    // Found in the background: replaced meanwhile (nothing to say), or the
                    // Transform tool let go / the layer changed (no preview: it would replace
                    // another tool's).
                    if (request !== ticket) return@run
                    request = null
                    search = null
                    if (!stillWanted(layer)) {
                        onReady(null)
                        return@run
                    }
                }
                val now = layer.vector?.objects.orEmpty().mapTo(HashSet()) { it.id }
                val ids = touched.filterTo(LinkedHashSet()) { it in now }
                if (ids.isEmpty()) {
                    c.toast(SELECTION_TOUCHES_NOTHING)
                    if (inline) accepted = false else onReady(null)
                    return@run
                }
                v.setSelection(layer, ids)
                if (inline) {
                    accepted = begin(layer, ids, -1, onReady)
                } else if (!begin(layer, ids, -1, onReady)) {
                    onReady(null)
                }
            }
            inline = false
            if (job != null && job.isActive) search = job
            return accepted
        }
        // 3. Every object: the cache is exactly their rendering (nothing to render).
        return begin(layer, content.objects.mapTo(LinkedHashSet()) { it.id }, -1, onReady)
    }

    /**
     * v1.10 (R1): the copy of [sidePick] when the lift of [ids] is just its object and that object
     * still has it (and can show it alone), else -1 (the pick is forgotten then).
     */
    private fun sideOf(layer: Layer, content: VectorContent, ids: Set<Long>): Int {
        val s = sidePick ?: return -1
        val o = if (s.layer === layer && ids.size == 1 && s.id in ids) content.byId(s.id) else null
        if (o == null || !CopyEdit.canLift(o, s.k)) {
            sidePick = null
            return -1
        }
        return s.k
    }

    /** The objects of the lift whose preview is being prepared (see [liftBox]), with their layer and the side (v1.10, or -1). */
    private class Preparing(layer: Layer, val ids: Set<Long>, val side: Int) {
        private val ref = WeakReference(layer)
        val layer: Layer? get() = ref.get()
    }

    private var preparing: Preparing? = null

    /**
     * Starts the preview of [ids] ([onReady] gets the lift, or null); false = refused right away.
     * v1.10 (R1): [side] ≥ 0 lifts that copy of the one object [ids] names alone (a [CopyLift]).
     */
    private fun begin(layer: Layer, ids: Set<Long>, side: Int, onReady: (ObjectLift?) -> Unit): Boolean {
        request = null
        var refused = false
        var sync = true
        val prep = Preparing(layer, ids, side)
        preparing = prep
        c.vectors.beginEdit(layer, ids, side) { session ->
            if (preparing === prep) preparing = null
            if (session == null) {
                if (sync) refused = true else onReady(null)
                return@beginEdit
            }
            if (session.floating == null || session.floatingRect.width() <= 0 || session.floatingRect.height() <= 0) {
                // Nothing of them can be shown (empty or far off the canvas).
                session.cancel()
                session.floating?.let { if (!it.isRecycled) it.recycle() }
                c.toast(NOTHING_TO_TRANSFORM)
                if (sync) refused = true else onReady(null)
                return@beginEdit
            }
            val apply: (Layer, VectorContent, String, VectorLayers.ShiftHint?, (Boolean) -> Unit) -> Unit =
                { l, after, label, shift, done -> update(l, after, label, shift, done) }
            val onEnded: (VectorObjectLift) -> Unit = { ended -> if (current === ended) current = null }
            // (The session shows the side alone only when the object can still show it: the same test.)
            val one = session.ids.singleOrNull()
            val obj = one?.let { layer.vector?.byId(it) }
            val lift = if (side >= 0 && obj != null && CopyEdit.canLift(obj, side)) CopyLift(c, session, obj.id, side, apply, onEnded)
            else VectorObjectLift(c, session, session.ids, apply, onEnded)
            current = lift
            onReady(lift)
        }
        sync = false
        return !refused
    }

    /** A tap outside the lifted objects' box at [p] (see [tap]). */
    override fun tapped(p: Vec2): Boolean = tap(p, inside = false, moved = false)

    /**
     * A tap at [p] while objects are lifted: the object there is selected alone and lifted
     * instead; tapping the same spot again goes one object deeper among overlapping ones (and
     * round again from the top). Outside the box, a tap on empty canvas goes back to all
     * objects. Inside the box (lead, v1.5 integration: after the default "every object" lift the
     * box covers everything, so this is where objects are picked) a tap on empty space does
     * nothing, and so does any tap once the lifted objects were [moved]: they are not where the
     * layer's data has them, and the user is placing them. Lifted objects that were moved are
     * not hit where they were (their hole shows there). False when nothing would change.
     *
     * v1.10 (R1, J19): on an object with live copies the first tap picks the copy under the finger
     * ([sidePick]; the top-most one where copies overlap), a tap at the same spot ([TapCycle]: no
     * time limit) picks all sides, and the next ones go on with v1.9's deeper cycle (the next
     * object below, its side first). A tap inside a picked side that was [moved] applies the move
     * (the caller's one step) and picks all sides. A Perspective array's copies are plain
     * objects: each is picked whole, and the same spot goes deeper.
     */
    override fun tap(p: Vec2, inside: Boolean, moved: Boolean): Boolean {
        val layer = c.activeLayer
        val content = layer.vector ?: return false
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        val t = c.viewTransform
        val tol = t.screenToDocLength(t.dp(TAP_TOLERANCE_DP))
        val open = current?.takeIf { it.isOpen && it.layer === layer }
        val lifted = open?.ids
        val liftedSide = open?.takeIf { it.ids.size == 1 }?.side ?: -1
        val v = c.vectors
        val sp = t.docToScreen(p)
        val dp = t.dp(1f).takeIf { it > 0f && it.isFinite() } ?: 1f
        var depth = cycle.next(sp.x / dp, sp.y / dp, layer.id)
        if (inside && moved) {
            // A picked side that moved: its move is applied and all sides are lifted together.
            if (lifted == null || liftedSide < 0) return false
            val id = lifted.first()
            sidePick = null
            liftAll = null
            v.setSelection(layer, setOf(id))
            lastTap = Tap(layer, id, -1, selectionOf(layer))
            return true
        }
        // Lifted objects that moved are not hit where they were (their hole shows), nor a moved side.
        val away = if (moved && lifted != null && liftedSide < 0) lifted else emptySet()
        val skip = if (moved && liftedSide >= 0 && lifted != null) SidePick(layer, lifted.first(), liftedSide) else null
        val prev = lastTap?.takeIf { memo ->
            memo.layer === layer && memo.id !in away && memo.selection == selectionOf(layer) &&
                content.byId(memo.id)?.let { hitCopy(it, p, tol, skip) >= 0 } == true
        }
        // Not the same spot as a pick that still holds: the cycle starts again.
        if (depth > 0 && prev == null) {
            cycle.reset()
            depth = cycle.next(sp.x / dp, sp.y / dp, layer.id)
        }
        val pick: Pair<VObject, Int>? = when {
            depth == 0 || prev == null -> topPick(layer, content, p, tol, null, away, skip)
            // A side, then all sides of the same object.
            prev.side >= 0 -> content.byId(prev.id)?.let { it to -1 }
            else -> topPick(layer, content, p, tol, prev.id, away, skip)
        }
        if (pick == null) {
            lastTap = null
            // Empty space inside the box: the lifted objects stay as they are.
            if (inside) return false
            // Empty canvas: back to every object (unless that is what is lifted already).
            if (lifted != null && liftedSide < 0 && lifted.size == content.objects.size) return false
            sidePick = null
            v.setSelection(null, emptySet())
            liftAll = LiftAll(c.selection)
            return true
        }
        val (hit, side) = pick
        val same = lifted != null && lifted.size == 1 && hit.id in lifted && liftedSide == side
        if (!same) {
            liftAll = null
            sidePick = if (side >= 0) SidePick(layer, hit.id, side) else null
            v.setSelection(layer, setOf(hit.id))
            if (side >= 0) countSidePick()
        }
        lastTap = Tap(layer, hit.id, side, selectionOf(layer))
        return !same
    }

    /** The object selection of [layer] (empty when another layer's objects, or none, are selected). */
    private fun selectionOf(layer: Layer): Set<Long> {
        val v = c.vectors
        return if (v.selectedLayer === layer) v.selectedIds else emptySet()
    }

    /** [VectorOps.hitCopy] of [o], leaving out the copy [skip] names when it is [o]'s. */
    private fun hitCopy(o: VObject, p: Vec2, tol: Float, skip: SidePick?): Int =
        VectorOps.hitCopy(o, p, tol, if (skip != null && skip.id == o.id) skip.k else -1)

    /**
     * The topmost object under [p] (below [below] when given, as `VectorLayers.hitTest`) that is
     * not one of [away] (nor hit only on the copy [skip]), with the copy hit there when it has
     * live copies (else -1: the whole object); null when none.
     */
    private fun topPick(layer: Layer, content: VectorContent, p: Vec2, tol: Float, below: Long?, away: Set<Long>, skip: SidePick?): Pair<VObject, Int>? {
        val o = (if (away.isEmpty() && skip == null) c.vectors.hitTest(layer, p, tol, below = below) else hitExcept(content, p, tol, below, away, skip)) ?: return null
        val k = if (ObjectCopies.hasCopies(o)) hitCopy(o, p, tol, skip) else -1
        return o to k
    }

    /** One more side pick: the first [SIDE_HINT_PICKS] on this phone show the hint (`UiPrefs.hintSidePicks`). */
    private fun countSidePick() {
        val prefs = UiPrefs(c.appContext)
        val n = prefs.hintSidePicks
        if (n >= SIDE_HINT_PICKS) return
        prefs.hintSidePicks = n + 1
        c.toast(MirrorLabels110.SIDE_HINT)
    }

    /**
     * The topmost object of [content] under [p] (within [tol]) that is not one of [away] (nor hit
     * only on the copy [skip], v1.10); with [below], only objects under that one, else from the
     * top again (as `VectorLayers.hitTest`).
     */
    private fun hitExcept(content: VectorContent, p: Vec2, tol: Float, below: Long?, away: Set<Long>, skip: SidePick? = null): VObject? {
        val objs = content.objects
        val pad = if (tol.isFinite()) max(0f, tol) else 0f
        fun hits(o: VObject): Boolean {
            if (o.id in away) return false
            val b = ObjectBounds.of(content, o)
            if (p.x < b.left - pad || p.x > b.right + pad || p.y < b.top - pad || p.y > b.bottom + pad) return false
            return hitCopy(o, p, tol, skip) >= 0
        }
        val from = below?.let { id -> objs.indexOfFirst { it.id == id } } ?: -1
        for (i in (if (from >= 0) from - 1 else objs.lastIndex) downTo 0) if (hits(objs[i])) return objs[i]
        if (from >= 0) for (i in objs.lastIndex downTo from) if (hits(objs[i])) return objs[i]
        return null
    }

    /**
     * The box (document px) of the objects a lift of [layer] shows: those being prepared, else
     * the ones [lift] would take now (the object selection, else the pixel selection's bounds,
     * else every object); null for a layer without objects.
     */
    override fun liftBox(layer: Layer): RectF? {
        val content = layer.vector ?: return null
        if (content.objects.isEmpty()) return null
        preparing?.takeIf { it.layer === layer }?.let { return sideBox(content, it.ids, it.side) ?: boxOf(content, it.ids) }
        val v = c.vectors
        val la = liftAll
        val all = la != null && la.selection === c.selection && !(v.selectedLayer === layer && v.selectedIds.isNotEmpty())
        if (!all && v.selectedLayer === layer) {
            val ids = v.selectedIds
            // v1.10 (R1): a picked side: that copy's box.
            val k = sidePick?.takeIf { it.layer === layer && ids.size == 1 && it.id in ids }?.k ?: -1
            if (ids.isNotEmpty()) return sideBox(content, ids, k) ?: boxOf(content, ids)
        }
        val sel = c.selection
        if (!all && sel != null) return if (sel.isEmpty) null else RectF(sel.bounds)
        return boxOf(content, null)
    }

    /** v1.10 (R1): the box of copy [k] when [ids] is just one object that has it, else null. */
    private fun sideBox(content: VectorContent, ids: Set<Long>, k: Int): RectF? {
        if (k < 0) return null
        val o = ids.singleOrNull()?.let { content.byId(it) } ?: return null
        if (!CopyEdit.canLift(o, k)) return null
        return CopyPick.boundsOf(o, k).takeUnless { it.isEmpty }
    }

    /** The union of the paint bounds of [ids] of [content] (null = every object), or null when empty. */
    private fun boxOf(content: VectorContent, ids: Set<Long>?): RectF? {
        val box = RectF()
        for (o in content.objects) {
            if (ids != null && o.id !in ids) continue
            val b = ObjectBounds.of(content, o)
            if (!b.isEmpty) box.union(b)
        }
        return box.takeUnless { it.isEmpty }
    }

    companion object {
        const val NOTHING_TO_TRANSFORM = "Nothing to transform on this layer"
        const val SELECTION_TOUCHES_NOTHING = "The selection doesn't touch any object"

        /** How far (screen dp) from an object a tap still picks it. */
        const val TAP_TOLERANCE_DP = 10f

        /** Taps this close (screen dp) to the previous one count as the same spot (cycling). */
        const val SAME_SPOT_DP = TapCycle.SAME_SPOT_DP

        /** v1.10 (R1): how many side picks on one phone show `MirrorLabels110.SIDE_HINT`. */
        const val SIDE_HINT_PICKS = 3
    }
}
