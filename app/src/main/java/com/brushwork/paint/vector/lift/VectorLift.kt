package com.brushwork.paint.vector.lift

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.ObjectLift
import com.brushwork.paint.tools.transform.ObjectLiftProvider
import com.brushwork.paint.tools.transform.RefusingLiftProvider
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.select.ObjectTouch
import com.brushwork.paint.vector.select.PendingRenders
import kotlinx.coroutines.Job
import java.lang.ref.WeakReference
import java.util.WeakHashMap

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

    /** The last object a tap picked (and where), so tapping there again goes one object deeper. */
    private class Tap(layer: Layer, val p: Vec2, val id: Long) {
        private val ref = WeakReference(layer)
        val layer: Layer? get() = ref.get()
    }

    private var lastTap: Tap? = null

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
            if (ids.isNotEmpty()) return begin(layer, ids, onReady)
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
                    accepted = begin(layer, ids, onReady)
                } else if (!begin(layer, ids, onReady)) {
                    onReady(null)
                }
            }
            inline = false
            if (job != null && job.isActive) search = job
            return accepted
        }
        // 3. Every object: the cache is exactly their rendering (nothing to render).
        return begin(layer, content.objects.mapTo(LinkedHashSet()) { it.id }, onReady)
    }

    /** Starts the preview of [ids] ([onReady] gets the lift, or null); false = refused right away. */
    private fun begin(layer: Layer, ids: Set<Long>, onReady: (ObjectLift?) -> Unit): Boolean {
        request = null
        var refused = false
        var sync = true
        c.vectors.beginEdit(layer, ids) { session ->
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
            val lift = VectorObjectLift(c, session, session.ids, apply) { ended -> if (current === ended) current = null }
            current = lift
            onReady(lift)
        }
        sync = false
        return !refused
    }

    /**
     * A tap outside the lifted objects' box at [p]: the object there is selected alone and lifted
     * instead (tapping the same spot again goes one object deeper among overlapping ones); a tap
     * on empty canvas goes back to all objects. False when nothing would change.
     */
    override fun tapped(p: Vec2): Boolean {
        val layer = c.activeLayer
        val content = layer.vector ?: return false
        if (!p.x.isFinite() || !p.y.isFinite()) return false
        val t = c.viewTransform
        val tol = t.screenToDocLength(t.dp(TAP_TOLERANCE_DP))
        val prev = lastTap?.takeIf { memo ->
            memo.layer === layer && t.docToScreen(memo.p).distanceTo(t.docToScreen(p)) <= t.dp(SAME_SPOT_DP) &&
                content.byId(memo.id)?.let { VectorOps.hit(it, p, tol) } == true
        }
        val hit: VObject? = c.vectors.hitTest(layer, p, tol, below = prev?.id)
        val lifted = current?.takeIf { it.isOpen && it.layer === layer }?.ids
        val v = c.vectors
        if (hit == null) {
            lastTap = null
            // Empty canvas: back to every object (unless that is what is lifted already).
            if (lifted != null && lifted.size == content.objects.size) return false
            v.setSelection(null, emptySet())
            liftAll = LiftAll(c.selection)
            return true
        }
        lastTap = Tap(layer, p, hit.id)
        if (lifted != null && lifted.size == 1 && hit.id in lifted) return false
        liftAll = null
        v.setSelection(layer, setOf(hit.id))
        return true
    }

    companion object {
        const val NOTHING_TO_TRANSFORM = "Nothing to transform on this layer"
        const val SELECTION_TOUCHES_NOTHING = "The selection doesn't touch any object"

        /** How far (screen dp) from an object a tap still picks it. */
        const val TAP_TOLERANCE_DP = 10f

        /** Taps this close (screen dp) to the previous one count as the same spot (cycling). */
        const val SAME_SPOT_DP = 16f
    }
}
