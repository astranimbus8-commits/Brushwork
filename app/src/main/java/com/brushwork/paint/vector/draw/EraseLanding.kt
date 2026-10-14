package com.brushwork.paint.vector.draw

import android.graphics.Path
import com.brushwork.paint.DeferredStep
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.LayerConvert
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * v1.9 (A5): what an erase on a vector layer erases with: the Eraser's swept path, or a tool's
 * outline ([ToolEraseSession]). Applied again to what the layer holds when it changed meanwhile.
 */
internal interface EraseFootprint {
    /** Applies the eraser to [s] (its capsules, in order). */
    fun feed(s: EraseSession)

    /** The region the partial eraser's region cut takes away ([EraseCut.cutAll]). */
    fun region(): Path
}

/**
 * v1.9 (R11 b, c; A5): lands an erase on a vector layer (or a legacy shape layer to convert,
 * [convertFirst]) as ONE undo step [label]: the Eraser's gesture at the lift
 * ([VectorEraserRecorder]) and a tool's erase at its commit ([ToolEraseSession]). Main thread.
 *
 * [start] queues the erase behind the eraser and bucket updates of the layer still on their way
 * ([VectorDrawState.serial]), then removes what the [EraseSession] marked from what the layer
 * holds NOW (the same [footprint] applied again when it changed since). The partial eraser's
 * region cut of shapes and filled paths ([EraseCut.cutAll]) runs on `Dispatchers.Default` over the
 * immutable objects ([cutInBackground]; the busy overlay shows after [CUT_BUSY_AFTER_MS]); when the
 * layer changed while it ran, the whole erase runs again on what it holds then. It runs on this
 * thread for at most [syncTargets] targets, inside another step, under another busy operation,
 * while a pending step is flushed and after [MAX_RERUNS] reruns.
 *
 * [onDone] is called once: true when the step was recorded. The owner's preview goes then.
 */
internal class EraseLanding(
    private val c: EditorController,
    private val layer: Layer,
    private val mode: VectorEraseMode,
    private val convertFirst: Boolean,
    private val label: String,
    private val footprint: EraseFootprint,
    /** Region cuts of at most this many targets run on this thread. */
    private val syncTargets: Int,
    private val onDone: (applied: Boolean) -> Unit,
) {
    private val state = VectorDrawState.of(c)

    /**
     * What the erase works on now: the layer's objects, or (on a shape layer to convert) the
     * object its shape becomes; null when there is none (the layer changed kind meanwhile).
     */
    fun contentNow(): VectorContent? =
        layer.vector ?: if (convertFirst) LayerConvert.shapeToVector(layer) else null

    /** Lands [session] (over what the layer held when it began) once the updates before it are applied. */
    fun start(session: EraseSession) {
        state.serial(layer) { finish -> apply(session, finish, reruns = 0) }
    }

    private fun done(applied: Boolean, finish: () -> Unit) {
        onDone(applied)
        finish()
    }

    /** [finish] is called once the erase was applied (or nothing was to be done). */
    private fun apply(session: EraseSession, finish: () -> Unit, reruns: Int) {
        val current = contentNow()
        if (current == null || c.doc.indexOf(layer) < 0) { done(false, finish); return }
        // A shape layer still to convert: its shape decodes again into an equal object.
        val converting = layer.vector == null
        // The layer changed since the erase began (an update landed meanwhile): the same eraser
        // is applied to what the layer holds now.
        val same = current === session.content || (converting && current == session.content)
        val final = if (same) session else EraseSession(current, mode, state.targets).also { footprint.feed(it) }
        val targets = final.cutTargets()
        if (targets.isEmpty()) { land(final, EraseCut.Cuts.NONE, finish); return }
        val region = footprint.region()
        val now = targets.size <= syncTargets || state.cutsNow > 0 || reruns >= MAX_RERUNS ||
            c.editDepth != 0 || c.busyMessage != null || !c.scope.isActive
        if (now) {
            val cuts = try {
                PerfTrace.span(PerfTrace.CUT) { EraseCut.cutAll(targets, region) }
            } catch (e: Exception) {
                EraseCut.Cuts.FAILED
            }
            land(final, cuts, finish)
            return
        }
        cutInBackground(Cutting(final, current, finish, reruns), targets, region)
    }

    /**
     * A region cut on its way in the background, for [final] over [base] (what the layer held).
     * As the editor's [DeferredStep] it lands at once when anything else records a step.
     */
    private inner class Cutting(val final: EraseSession, val base: VectorContent, val finish: () -> Unit, val reruns: Int) : DeferredStep {
        var job: Deferred<EraseCut.Cuts>? = null
        var done = false
        val completion = CompletableDeferred<Unit>()

        override fun flush() {
            if (done) return
            val cuts = try {
                runBlocking { job?.await() }
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                EraseCut.Cuts.FAILED
            }
            // Whatever lands (or runs again) now does it on this thread, as one step.
            state.cutsNow++
            try {
                c.editScope { landed(this, cuts) }
            } finally {
                state.cutsNow--
            }
        }
    }

    private fun cutInBackground(p: Cutting, targets: List<VObject>, region: Path) {
        val job = c.scope.async(state.cutDispatcher ?: Dispatchers.Default) { PerfTrace.span(PerfTrace.CUT) { EraseCut.cutAll(targets, region) } }
        p.job = job
        c.addDeferredStep(p)
        // (Dispatchers.Main, not immediate, as VectorLayers' renders: it never lands inside another operation.)
        c.scope.launch(Dispatchers.Main) {
            val cuts = try {
                job.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EraseCut.Cuts.FAILED
            }
            landed(p, cuts)
        }
        c.scope.launch {
            delay(CUT_BUSY_AFTER_MS)
            if (!p.done && c.busyMessage == null) c.runBusy(label) { p.completion.await() }
        }
    }

    /**
     * The region cut [p] is back ([cuts]; null: it was dropped): applied when the layer still
     * holds what it was cut from, else the erase runs again on what it holds now.
     */
    private fun landed(p: Cutting, cuts: EraseCut.Cuts?) {
        if (p.done) return
        p.done = true
        c.removeDeferredStep(p)
        p.completion.complete(Unit)
        val now = contentNow()
        val same = now != null && c.doc.indexOf(layer) >= 0 && (now === p.base || (layer.vector == null && now == p.base))
        if (!same || cuts == null) { apply(p.final, p.finish, p.reruns + 1); return }
        land(p.final, cuts, p.finish)
    }

    /**
     * Applies [final]'s result with the region cut's pieces [cuts] (the layer holds
     * [EraseSession.content]). Once the step is recorded one note says what else happened (a
     * toast shows one text): [EraseLabels19.TOO_COMPLEX] when objects were left as they were (a
     * cap or a failed cut), else [EraseLabels19.COPIES_BAKED] when an object's live copies became
     * pieces of their own. Objects left as they were with nothing else erased (no step) say so too.
     */
    private fun land(final: EraseSession, cuts: EraseCut.Cuts, finish: () -> Unit) {
        val converting = layer.vector == null
        // (result() first: it sets the session's bakedCopies.)
        val after = final.result(cuts.pieces)
        val note = when {
            cuts.tooComplex -> EraseLabels19.TOO_COMPLEX
            cuts.copiesBaked || final.bakedCopies -> EraseLabels19.COPIES_BAKED
            else -> null
        }
        if (after == null) {
            if (cuts.tooComplex && c.doc.indexOf(layer) >= 0) c.toast(EraseLabels19.TOO_COMPLEX)
            done(false, finish)
            return
        }
        val landed = { ok: Boolean ->
            if (ok && note != null) c.toast(note)
            done(ok, finish)
        }
        try {
            if (!converting) {
                state.update(c, layer, after, label, landed)
            } else if (c.doc.effectiveLocked(layer) || !c.doc.effectiveVisible(layer)) {
                // (The update would refuse it: no conversion either.)
                done(false, finish)
            } else {
                // ONE step: the conversion, then the erase on the vector layer it made (rendered
                // synchronously inside the step).
                c.groupUndo(label) {
                    LayerConvert.toVectorAction(c, layer, label = label)?.let(c::pushUndo)
                    state.update(c, layer, after, label, landed)
                }
            }
        } catch (e: Throwable) {
            onDone(false)
            throw e
        }
    }

    companion object {
        /** A region cut still running after this long (ms) shows the busy overlay (as `VectorLayers.BUSY_AFTER_MS`). */
        const val CUT_BUSY_AFTER_MS = 400L

        /** After this many reruns (the layer kept changing while the cut ran) it runs on this thread. */
        private const val MAX_RERUNS = 2
    }
}
