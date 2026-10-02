package com.brushwork.paint.qa

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.draw.VectorDrawState
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits
import com.brushwork.paint.vector.select.VectorObjectSelection
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * QA (I1, I2, §4.9e at the user's level): seeded random sessions on the phone — finger and stylus
 * strokes, the three eraser modes, Lasso + every Object bar action, Transform (move / scale /
 * rotate, never whole pixels), the bucket, Shape, Curve and Polyline, Duplicate / Flip / Merge of
 * layers, canvas flips, the Vector button, saves and reloads, undo and redo bursts — through the
 * real canvas view. After every action: at most one step, nothing changed without a step, every
 * vector layer's pixels are its objects' rendering; every undo / redo gives back exactly the state
 * the document had. The async sessions render in the background (a slow worker) and also undo
 * right after an action, before its render landed. A failure prints the seed's action log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorFuzzQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private lateinit var rnd: Random
    private val log = ArrayList<String>()
    private var async = false

    /**
     * Strict cache checks until a flip / merge / canvas flip. Those move the cache's pixels as the
     * design says (the picture stays exactly what it was): Skia's anti-aliasing is not mirror
     * symmetric, so edge pixels can differ from a fresh rendering of the mirrored objects (about
     * 0.5 % of the painted pixels here) until their tiles are drawn again (a known A1 gap).
     */
    private var tol = 0
    private var permille = 0
    private var near = false

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    private class SlowWorker(private val delayMs: Long) : CoroutineDispatcher() {
        private val ex = Executors.newSingleThreadExecutor { Thread(it, "fuzz-vector-worker").apply { isDaemon = true } }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ex.execute { Thread.sleep(delayMs); block.run() }
        }
    }

    private val w get() = c.doc.width
    private val h get() = c.doc.height

    private fun pt(margin: Float = 24f): Pair<Float, Float> = (margin + rnd.nextFloat() * (w - 2 * margin)) to (margin + rnd.nextFloat() * (h - 2 * margin))

    private fun color(): Int = 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF)

    private fun check(where: String, steps: Int? = 1) {
        r.checkpoint("#${log.size} $where", steps, tol, permille, near)
    }

    private fun loosen() { tol = 2; permille = 10; near = true }

    /** The active layer is a vector layer (the Vector button when it isn't). */
    private fun ensureVector() {
        if (c.isVectorMode) return
        val v = c.doc.layers.lastOrNull { it.isVectorLayer && it.visible && !it.locked }
        if (v != null) c.selectLayer(v) else c.toggleVectorMode()
        check("back to a vector layer", steps = null)
    }

    private fun vec(): Layer = c.activeLayer

    private fun settleTool() {
        val t = c.currentTool
        if (t.hasPendingWork) t.commit()
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering && c.busyMessage == null }
    }

    private fun deselectObjects() {
        if (c.vectors.selectedIds.isNotEmpty()) ObjectActions.deselect(c)
    }

    // ------------------------------------------------------------------ actions

    private fun stroke(stylus: Boolean) {
        ensureVector()
        r.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.defaultBrush.copy(size = 4f + rnd.nextFloat() * 16f)
        c.color = color()
        val a = pt(); val b = pt(); val d = pt()
        log += "stroke stylus=$stylus $a $b $d"
        if (stylus) r.stylusStroke(a, b, d) else r.stroke(a, b, d)
        check("stroke")
    }

    private fun erase() {
        ensureVector()
        r.tool(ToolId.ERASER)
        val mode = VectorEraseMode.entries[rnd.nextInt(VectorEraseMode.entries.size)]
        VectorEraserModes.setMode(c, mode)
        c.eraser = c.eraser.copy(size = 8f + rnd.nextFloat() * 20f)
        val a = pt(); val b = pt()
        log += "erase $mode $a $b"
        r.stroke(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)
        check("erase $mode", steps = null)
    }

    private fun lassoAndAction() {
        ensureVector()
        deselectObjects()
        r.tool(ToolId.LASSO)
        val (x0, y0) = pt(); val (x1, y1) = pt()
        val l = minOf(x0, x1); val t = minOf(y0, y1); val rr = maxOf(x0, x1) + 30f; val b = maxOf(y0, y1) + 30f
        log += "lasso $l,$t-$rr,$b"
        c.message = null
        r.stroke(l to t, rr to t, rr to b, l to b, l to t + 2f)
        Smoke.pumpUntil(5_000) { c.vectors.selectedIds.isNotEmpty() || c.message == VectorObjectSelection.NOTHING_THERE }
        check("lasso", steps = 0)
        if (c.vectors.selectedIds.isEmpty()) return
        when (rnd.nextInt(6)) {
            0 -> { log += "  delete"; ObjectActions.delete(c); check("delete") }
            1 -> { log += "  duplicate"; ObjectActions.duplicate(c); check("duplicate") }
            2 -> {
                val how = ObjectEdits.Arrange.entries[rnd.nextInt(ObjectEdits.Arrange.entries.size)]
                log += "  arrange $how"; ObjectActions.arrange(c, how); check("arrange $how", steps = null)
            }
            3 -> { c.color = color(); log += "  recolor"; ObjectActions.recolor(c, linesOnly = rnd.nextBoolean()); check("recolor", steps = null) }
            4 -> {
                log += "  transform from the Object bar"
                ObjectActions.transform(c)
                val tt = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
                Smoke.pumpUntil(10_000) { tt.transformState != null }
                if (tt.transformState != null) {
                    tt.moveBy(rnd.nextInt(-40, 40) + 0.5f, rnd.nextInt(-40, 40) + 0.5f)
                    tt.commit()
                }
                check("transform selected", steps = null)
                r.tool(ToolId.BRUSH)
            }
            else -> { log += "  deselect"; ObjectActions.deselect(c); check("deselect", steps = 0) }
        }
        deselectObjects()
    }

    private fun transformAll() {
        ensureVector()
        deselectObjects()
        r.tool(ToolId.TRANSFORM)
        val tt = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        Smoke.pumpUntil(3_000) { tt.transformState != null }
        if (tt.transformState == null) { log += "transform: nothing to lift"; r.tool(ToolId.BRUSH); check("nothing to transform", steps = 0); return }
        when (rnd.nextInt(3)) {
            0 -> { val dx = rnd.nextInt(-30, 30) + 0.5f; val dy = rnd.nextInt(-30, 30) + 0.5f; log += "transform move $dx,$dy"; tt.moveBy(dx, dy) }
            1 -> { val s = (80.0 + rnd.nextInt(40)).let { if (it == 100.0) 104.0 else it }; log += "transform scale $s"; tt.setScalePercent(s); tt.endNumericEdit() }
            else -> { val a = rnd.nextInt(-25, 25).toDouble() + 0.5; log += "transform rotate $a"; tt.setRotation(a); tt.endNumericEdit() }
        }
        tt.commit()
        check("transform")
        r.tool(ToolId.BRUSH)
    }

    private fun bucket() {
        ensureVector()
        r.tool(ToolId.FILL)
        c.color = color()
        val p = pt()
        log += "bucket $p"
        r.tap(p.first, p.second)
        Smoke.pumpUntil(10_000) { !VectorDrawState.of(c).filling && !c.vectors.isRendering && c.busyMessage == null }
        check("bucket", steps = null)
    }

    private fun shape() {
        ensureVector()
        deselectObjects()
        r.tool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        val type = listOf(ShapeType.RECTANGLE, ShapeType.ELLIPSE)[rnd.nextInt(2)]
        val style = ShapeStyle.entries[rnd.nextInt(ShapeStyle.entries.size)]
        tool.update { it.copy(type = type, style = style, useBrushSize = false, strokeWidth = 2f + rnd.nextInt(8), fillColor = color(), editable = true) }
        val a = pt(); val b = (a.first + 40f + rnd.nextFloat() * 120f).coerceAtMost(w - 4f) to (a.second + 30f + rnd.nextFloat() * 90f).coerceAtMost(h - 4f)
        log += "shape $type $style $a $b"
        r.stroke(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)
        val placed = tool.hasPendingWork
        if (placed) tool.commit()
        check("shape", steps = if (placed && !tool.editingObject) null else null)
    }

    private fun curve() {
        ensureVector()
        deselectObjects()
        val id = if (rnd.nextBoolean()) ToolId.CURVE else ToolId.POLYLINE
        r.tool(id)
        val tool = c.tools.getValue(id) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, fill = rnd.nextInt(4) == 0) }
        val pts = List(3) { pt() }
        log += "$id $pts"
        for (p in pts) r.tap(p.first, p.second)
        if (tool.anchors.size >= 2 && rnd.nextBoolean()) {
            val k = rnd.nextInt(tool.anchors.size)
            tool.select(k); tool.setWidth(k, 0.5f + rnd.nextFloat() * 2f); tool.endNumericEdit()
        }
        if (tool.hasPendingWork) tool.commit()
        check("$id", steps = null)
        r.tool(ToolId.BRUSH)
    }

    private fun undoRedo() {
        val k = 1 + rnd.nextInt(3)
        log += "undo x$k, redo x${k - rnd.nextInt(k + 1).coerceAtMost(k)}"
        settleTool()
        var done = 0
        repeat(k) { if (c.undoManager.canUndo && r.history.containsKey(c.undoManager.undoCount - 1)) { r.undoAndCheck("#${log.size} undo"); done++ } }
        val back = rnd.nextInt(done + 1)
        repeat(back) { if (c.undoManager.canRedo && r.history.containsKey(c.undoManager.undoCount + 1)) r.redoAndCheck("#${log.size} redo") }
        r.resync()
    }

    private fun layerOp() {
        settleTool()
        deselectObjects()
        val vectors = c.doc.layers.filter { it.isVectorLayer }
        when (rnd.nextInt(5)) {
            0 -> if (c.doc.layers.size < 5) {
                ensureVector()
                log += "duplicate layer ${vec().name}"
                c.duplicateLayer(vec()); check("duplicate layer")
            }
            1 -> { ensureVector(); val hz = rnd.nextBoolean(); log += "flip layer ${vec().name} h=$hz"; c.flipLayer(vec(), hz); loosen(); check("flip layer", steps = null) }
            2 -> {
                ensureVector()
                val idx = c.doc.indexOf(vec())
                if (idx > 0 && c.doc.layers[idx - 1].isVectorLayer) { log += "merge down ${vec().name}"; c.mergeDown(vec()); loosen(); check("merge down") }
                else if (c.doc.layers.size < 5) { log += "new vector layer"; c.addVectorLayer(); check("new vector layer") }
            }
            3 -> {
                log += "Vector off and on"
                if (c.isVectorMode) { c.toggleVectorMode(); check("Vector off", steps = null) }
                c.toggleVectorMode(); check("Vector on", steps = null)
            }
            else -> if (vectors.size > 1) {
                val victim = vectors.last()
                log += "delete layer ${victim.name}"
                c.deleteLayer(victim); check("delete layer")
            }
        }
    }

    private fun canvasFlip() {
        settleTool()
        val hz = rnd.nextBoolean()
        log += "flip canvas h=$hz"
        assertTrue(CanvasOps.applyFlip(c, hz))
        assertTrue(Smoke.pumpUntil(20_000) { c.busyMessage == null && !c.vectors.isRendering })
        loosen()
        check("flip canvas")
    }

    /** More of the editor: canvas turns, masks, layer order and visibility, the Selection sheet. */
    private fun extendedOp() {
        settleTool()
        deselectObjects()
        when (rnd.nextInt(7)) {
            6 -> {
                // An editable gradient mask on the vector layer (Masks tool, This layer).
                ensureVector()
                val l = vec()
                if (l.mask != null) return
                log += "gradient mask on ${l.name}"
                com.brushwork.paint.ui.layers.LayerOps.addGradientMask(c, l)
                Smoke.pump(40)
                r.stroke(pt(), pt())
                Smoke.pumpUntil(5_000) { l.maskSpec != null }
                check("gradient mask", steps = null)
                r.tool(ToolId.BRUSH)
                if (c.activeLayer !== l) c.selectLayer(l)
                if (l.editingMask) com.brushwork.paint.ui.layers.LayerOps.editTarget(c, l, mask = false)
                check("back to the objects", steps = null)
            }
            0 -> {
                val rot = com.brushwork.paint.engine.CanvasRotation.entries[rnd.nextInt(3)]
                log += "canvas ${rot.label}"
                assertTrue(CanvasOps.applyRotate(c, rot))
                assertTrue(Smoke.pumpUntil(20_000) { c.busyMessage == null && !c.vectors.isRendering && c.undoManager.undoLabel == rot.label })
                r.view.fitToScreen()
                Smoke.pump(40)
                loosen()
                check("canvas turn")
            }
            1 -> {
                ensureVector()
                val l = vec()
                if (l.mask == null) {
                    log += "add mask to ${l.name}"
                    com.brushwork.paint.ui.layers.LayerOps.addMask(c, l, fromSelection = false)
                    check("add mask")
                }
                log += "paint the mask of ${l.name}"
                com.brushwork.paint.ui.layers.LayerOps.editTarget(c, l, mask = true)
                r.tool(ToolId.BRUSH)
                c.color = if (rnd.nextBoolean()) 0xFF000000.toInt() else 0xFF808080.toInt()
                c.brush = BrushLibrary.defaultBrush.copy(size = 10f + rnd.nextFloat() * 20f)
                r.stroke(pt(), pt())
                check("paint the mask")
                com.brushwork.paint.ui.layers.LayerOps.editTarget(c, l, mask = false)
                check("back to the content", steps = 0)
            }
            2 -> {
                ensureVector()
                val l = vec()
                val to = rnd.nextInt(c.doc.layers.size)
                log += "move ${l.name} to $to"
                c.moveLayer(l, to)
                check("move layer", steps = null)
            }
            3 -> {
                val l = c.doc.layers[rnd.nextInt(c.doc.layers.size)]
                if (l === c.activeLayer) return
                log += "hide and show ${l.name}"
                c.toggleVisibility(l); check("hide")
                c.toggleVisibility(l); check("show")
            }
            else -> {
                ensureVector()
                // (A pixel selection left from a raster layer goes first: the wand's lands later.)
                if (c.selection != null) { c.deselect(); check("deselect first", steps = null) }
                r.tool(ToolId.MAGIC_WAND)
                val p = pt()
                log += "wand $p, Selection sheet ${if (rnd.nextBoolean()) "Fill" else "Clear"}"
                (c.tools.getValue(ToolId.MAGIC_WAND) as com.brushwork.paint.tools.select.MagicWandTool).selectAt(p.first, p.second)
                Smoke.pumpUntil(5_000) { c.selection != null }
                check("wand", steps = null)
                if (c.selection != null) {
                    c.color = color()
                    if (log.last().endsWith("Fill")) com.brushwork.paint.tools.select.SelectionEdits.fillSelection(c, c.color)
                    else com.brushwork.paint.tools.select.SelectionEdits.clearSelection(c)
                    check("sheet fill / clear", steps = null)
                    assertTrue("still a vector layer", c.activeLayer.isVectorLayer)
                    c.deselect()
                    check("deselect", steps = null)
                }
                r.tool(ToolId.BRUSH)
            }
        }
    }

    private fun saveReload() {
        settleTool()
        deselectObjects()
        log += "save + reload"
        val s = r.snapshot()
        r.saveAndReopen()
        r.assertState("#${log.size} reloaded", s)
        for (l in c.doc.layers) if (l.isVectorLayer) r.assertCacheFresh("#${log.size} reloaded", l, tol, permille, near)
        if (async) { c.vectors.workerDispatcher = SlowWorker(40); c.vectors.policy = VectorLayers.Policy.ASYNC }
    }

    /** An action, then Undo at once (its render may still be on its way): the state before it, exactly. */
    private fun actionThenUndo() {
        ensureVector()
        settleTool()
        deselectObjects()
        val n = c.undoManager.undoCount
        // Stale states above n (a redo stack the action clears) are dropped.
        r.history.keys.filter { it > n }.forEach { r.history.remove(it) }
        val before = r.snapshot()
        when (rnd.nextInt(3)) {
            0 -> {
                r.tool(ToolId.ERASER)
                VectorEraserModes.setMode(c, VectorEraseMode.entries[rnd.nextInt(3)])
                c.eraser = c.eraser.copy(size = 30f)
                val a = pt(); val b = pt()
                log += "erase + undo $a $b"
                r.stroke(a, b)
            }
            1 -> {
                r.tool(ToolId.TRANSFORM)
                val tt = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
                Smoke.pumpUntil(3_000) { tt.transformState != null }
                log += "transform + undo"
                if (tt.transformState != null) { tt.setRotation(7.5); tt.endNumericEdit(); tt.commit() }
            }
            else -> {
                val objs = vec().vector!!.objects
                log += "Object bar delete + undo"
                if (objs.isNotEmpty()) { c.vectors.setSelection(vec(), setOf(objs[rnd.nextInt(objs.size)].id)); ObjectActions.delete(c) }
            }
        }
        val recorded = c.vectors.isRendering || c.undoManager.undoCount > n
        c.undo()
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering && c.busyMessage == null })
        Smoke.pump(40)
        val now = c.undoManager.undoCount
        val expected = r.history[now] ?: throw AssertionError("#${log.size}: no state at $now (recorded=$recorded)")
        if (now == n) r.assertState("#${log.size} action + undo", before)
        r.assertState("#${log.size} action + undo (at $now)", expected)
        for (l in c.doc.layers) if (l.isVectorLayer) r.assertCacheFresh("#${log.size} action + undo", l, tol, permille, near)
        r.resync()
        // Redo gives the action back (its pixels its objects' rendering); undo again, exactly.
        if (now == n && c.undoManager.canRedo) {
            c.redo()
            assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering && c.busyMessage == null })
            assertEquals(n + 1, c.undoManager.undoCount)
            for (l in c.doc.layers) if (l.isVectorLayer) r.assertCacheFresh("#${log.size} action redone", l, tol, permille, near)
            r.history[n + 1] = r.snapshot()
            r.labels[n + 1] = c.undoManager.undoLabel ?: ""
            r.undoAndCheck("#${log.size} undo the redone action")
        }
        deselectObjects()
        r.tool(ToolId.BRUSH)
    }

    // ------------------------------------------------------------------ the session

    private fun session(seed: Int, actions: Int, asyncRenders: Boolean, extended: Boolean = false) {
        rnd = Random(seed)
        async = asyncRenders
        r = VectorQaRig(480, 320)
        if (async) {
            c.vectors.workerDispatcher = SlowWorker(40)
            c.vectors.policy = VectorLayers.Policy.ASYNC
            // Background renders draw each piece into its own bitmap at its own origin: a rare
            // pixel comes out 1-2 levels off a whole-canvas rendering (seen 2 pixels in 28 runs).
            tol = 2
        }
        try {
            c.toggleVectorMode()
            check("Vector on")
            repeat(4) { stroke(rnd.nextBoolean()) }
            repeat(actions) {
                // (Extended sessions draw one more number per action; the others keep their sequences.)
                if (extended && rnd.nextInt(8) == 0) { extendedOp(); return@repeat }
                when (val k = rnd.nextInt(100)) {
                    in 0..17 -> stroke(rnd.nextInt(3) == 0)
                    in 18..29 -> erase()
                    in 30..41 -> lassoAndAction()
                    in 42..51 -> transformAll()
                    in 52..57 -> bucket()
                    in 58..65 -> shape()
                    in 66..71 -> curve()
                    in 72..81 -> undoRedo()
                    in 82..87 -> layerOp()
                    in 88..89 -> canvasFlip()
                    in 90..92 -> saveReload()
                    else -> if (async) actionThenUndo() else undoRedo().also { if (k < 0) error("") }
                }
            }
            // The whole history back and forth, exactly.
            settleTool()
            deselectObjects()
            r.resync()
            log += "undo all ${c.undoManager.undoCount}"
            while (c.undoManager.canUndo && r.history.containsKey(c.undoManager.undoCount - 1)) r.undoAndCheck("final undo")
            log += "redo all"
            while (c.undoManager.canRedo && r.history.containsKey(c.undoManager.undoCount + 1)) r.redoAndCheck("final redo")
            saveReload()
            stroke(false)
            assertEquals(0, Smoke.scopeErrors.size)
        } catch (t: Throwable) {
            System.err.println("[fuzz] seed $seed async=$async failed after:\n" + log.withIndex().joinToString("\n") { (i, s) -> "  #${i + 1} $s" })
            throw AssertionError("seed $seed (async=$async) at action #${log.size} \"${log.lastOrNull()}\": ${t.message}", t)
        }
    }

    /** With canvas turns, masks, layer order and visibility, the Selection sheet. */
    @Test
    fun extendedSeed1299709() = session(1299709, 60, asyncRenders = false, extended = true)

    @Test
    fun extendedSeed3899127() = session(3899127, 60, asyncRenders = true, extended = true)

    @Test
    fun seed1505() = session(1505, 45, asyncRenders = false)

    @Test
    fun seed2408() = session(2408, 45, asyncRenders = false)

    /** (Found the Flip layer + canvas operation history bug, see [VectorHistoryQaTest].) */
    @Test
    fun seed166299() = session(166299, 85, asyncRenders = false)

    @Test
    fun seed392() = session(392, 40, asyncRenders = true)

    @Test
    fun seed606() = session(606, 40, asyncRenders = true)

    @Test
    fun seed3246599() = session(3246599, 60, asyncRenders = true)
}
