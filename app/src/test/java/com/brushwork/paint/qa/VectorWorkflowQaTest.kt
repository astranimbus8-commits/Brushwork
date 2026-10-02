package com.brushwork.paint.qa

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.tools.coordinateSourceOf
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * QA, §7 checklist 1 end to end, as the user on the phone (392 dp, fingers and a stylus): a new
 * canvas, Vector, brush strokes (finger, stylus, stabilizer, ruler), the vector eraser's three
 * modes, Lasso, Transform (scale, rotate, Distort, Numbers, X / Y, a pinch with one finger on
 * the objects, a pinch beside them), the Object bar, the bucket on an object and on an enclosed
 * area, the Shape tool (place, reopen by tap, Points, ✓ / ✕), Curve and Polyline with thickness
 * (reopen by tap), then every step undone and redone back and forth (each state exactly as it
 * was), saved and reloaded (still editable), and the Vector button off and on. Every action is
 * checked by [VectorQaRig.checkpoint] (one step, cache = rendering of the objects).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorWorkflowQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private lateinit var vec: Layer

    private val ink = 0xFF1A2A6C.toInt()
    private val green = 0xFF2E9D4A.toInt()
    private val orange = 0xFFF08020.toInt()

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    private fun objects(): List<VObject> = vec.vector!!.objects

    private fun strokesNear(y: Float, tol: Float = 30f) = objects().filterIsInstance<VStroke>().filter { s -> abs(s.points.bounds().centerY() - y) < tol }

    private fun idsOf(objs: List<VObject>) = objs.map { it.id }.toSet()

    // ------------------------------------------------------------------ phases

    private fun newCanvasAndVector() {
        r = VectorQaRig(800, 520)
        assertEquals(listOf("Background", "Layer 1"), c.doc.layers.map { it.name })
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        vec = c.activeLayer
        assertEquals("Vector 1", vec.name)
        assertTrue(c.isVectorMode)
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f)
    }

    private fun brushStrokes() {
        // S1, finger.
        r.stroke(40f to 70f, 380f to 80f, 760f to 70f)
        r.checkpoint("S1 finger")
        assertEquals("Brush", c.undoManager.undoLabel)
        val s1 = objects().single() as VStroke
        assertFalse(s1.stylus)
        assertEquals(ink, s1.color)
        // S2, stylus with changing pressure.
        r.stylusStroke(40f to 140f, 380f to 150f, 760f to 140f)
        r.checkpoint("S2 stylus")
        val s2 = objects().last() as VStroke
        assertTrue("a stylus stroke is recorded as one", s2.stylus)
        assertTrue("its pressures vary", s2.points.p.max() - s2.points.p.min() > 0.3f)
        // S3 with the stabilizer.
        c.updateStabilizer(StabilizerSettings(mode = StabilizerMode.SMOOTH, strength = 0.7f))
        r.stroke(40f to 210f, 160f to 240f, 300f to 200f)
        r.checkpoint("S3 stabilized")
        c.updateStabilizer(StabilizerSettings())
        // S4 along a straight ruler (horizontal): the stroke follows a parallel line.
        c.updateRuler(c.ruler.copy(enabled = true, type = RulerType.STRAIGHT, centerX = 400f, centerY = 400f, angleDeg = 0f))
        r.stroke(60f to 300f, 170f to 312f, 280f to 318f)
        r.checkpoint("S4 ruler")
        c.updateRuler(c.ruler.copy(enabled = false))
        val s4 = objects().last() as VStroke
        val ys = s4.points.y
        assertTrue("a ruler stroke is straight: y ${ys.min()}..${ys.max()}", ys.max() - ys.min() < 1f)
        assertEquals(4, objects().size)
        assertTrue(objects().all { it is VStroke })
        // H and V cross at (590, 230) (for the eraser's To intersection).
        r.stroke(420f to 230f, 590f to 230f, 760f to 230f)
        r.checkpoint("H")
        r.stroke(590f to 190f, 590f to 260f, 590f to 330f)
        r.checkpoint("V")
        assertEquals(6, objects().size)
    }

    private fun vectorEraser() {
        c.toggleEraser()
        assertEquals(ToolId.ERASER, c.activeToolId)
        c.eraser = c.eraser.copy(size = 14f)
        val before = objects().size
        // Partial: S1 is cut in two at x = 400.
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        r.stroke(400f to 40f, 400f to 75f, 400f to 105f)
        r.checkpoint("partial erase")
        assertEquals("Erase", c.undoManager.undoLabel)
        assertEquals("S1 cut in two", before + 1, objects().size)
        val row1 = strokesNear(75f)
        assertEquals(2, row1.size)
        assertTrue("the cut pieces end at the eraser", row1.all { s -> s.points.x.all { abs(it - 400f) > 7f } })
        // Object: S2 goes whole.
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        r.stroke(600f to 115f, 600f to 145f, 600f to 170f)
        r.checkpoint("object erase")
        assertTrue("S2 is gone", strokesNear(145f, 15f).isEmpty())
        assertEquals(before, objects().size)
        // To intersection: the piece of V below its crossing with H goes; the piece above stays.
        VectorEraserModes.setMode(c, VectorEraseMode.TO_INTERSECTION)
        r.stroke(580f to 300f, 590f to 301f, 600f to 302f)
        r.checkpoint("erase to intersection")
        val v = objects().filterIsInstance<VStroke>().single { s -> s.points.bounds().let { abs(it.centerX() - 590f) < 5f && it.width() < 20f } }
        val vb = v.points.bounds()
        assertTrue("V keeps its top piece: ${vb.top}..${vb.bottom}", vb.top < 200f && vb.bottom in 220f..242f)
        c.toggleEraser()
        assertEquals(ToolId.BRUSH, c.activeToolId)
    }

    private var s3s4: Set<Long> = emptySet()

    private fun lassoSelect() {
        val s3 = strokesNear(220f).single { it.points.bounds().left < 100f }
        val s4 = strokesNear(305f, 20f).single()
        s3s4 = setOf(s3.id, s4.id)
        r.tool(ToolId.LASSO)
        r.stroke(20f to 180f, 330f to 180f, 330f to 345f, 20f to 345f, 20f to 182f)
        assertTrue("objects selected", Smoke.pumpUntil(10_000) { c.vectors.selectedIds.isNotEmpty() })
        r.checkpoint("lasso", steps = 0)
        assertEquals(s3s4, c.vectors.selectedIds)
        assertSame(vec, c.vectors.selectedLayer)
        assertNull("no pixel selection on a vector layer", c.selection)
    }

    private fun transform(): TransformTool {
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("the transform lifted", Smoke.pumpUntil(10_000) { t.transformState != null })
        return t
    }

    private fun relift(): TransformTool {
        ObjectActions.transform(c)
        Smoke.pump(40)
        val t = transform()
        assertEquals("the selected objects are lifted", c.vectors.selectedIds, VectorLift.activeLift(c)?.ids)
        return t
    }

    private fun transformPhase() {
        r.tool(ToolId.TRANSFORM)
        var t = transform()
        assertEquals(s3s4, VectorLift.activeLift(c)!!.ids)
        val before = s3s4.map { vec.vector!!.byId(it)!! }
        // Numbers: scale 150 %, rotate 20°; ✓ is one step.
        t.setScalePercent(150.0); t.endNumericEdit()
        t.setRotation(20.0); t.endNumericEdit()
        r.checkpoint("scaled and rotated (pending)", steps = 0)
        t.commit()
        r.checkpoint("✓ scale + rotate")
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        val scaled = s3s4.map { vec.vector!!.byId(it) as VStroke }
        for ((a, b) in before.zip(scaled)) assertEquals(1.5f * (a as VStroke).sizeScale, b.sizeScale, 1e-3f)
        assertEquals("still selected", s3s4, c.vectors.selectedIds)

        // Distort: a corner dragged with the finger.
        t = relift()
        t.mode = TransformTool.Mode.DISTORT
        val k = t.transformState!!.corner(2)
        r.stroke(k.x to k.y, k.x + 15f to k.y + 10f, k.x + 30f to k.y + 20f)
        assertTrue("distorted", t.transformState!!.isDistorted)
        t.commit()
        r.checkpoint("✓ distort")
        t.mode = TransformTool.Mode.FREE
        assertTrue("strokes stay strokes", s3s4.all { vec.vector!!.byId(it) is VStroke })

        // Numbers: width 80 % with the aspect kept.
        t = relift()
        val w0 = t.transformState!!.width
        t.keepAspect = true
        t.setSize(width = w0 * 0.8)
        t.endNumericEdit()
        assertEquals(w0 * 0.8f, t.transformState!!.width, 0.5f)
        t.commit()
        r.checkpoint("✓ numbers size")

        // X / Y strip: the reference point to (220, 260).
        t = relift()
        val strip = coordinateSourceOf(t)!!.target
        strip.setPosition(220f, null); strip.endPositionEdit()
        strip.setPosition(null, 260f); strip.endPositionEdit()
        assertEquals(Vec2(220f, 260f), strip.position)
        t.commit()
        r.checkpoint("✓ X/Y", tolerance = 32, maxOffPermille = 20)

        // Pinch with ONE finger on the objects: they scale, the view stays.
        t = relift()
        val zoom = c.viewTransform.zoom
        val st = t.transformState!!
        val box = st.bounds()
        val inside = r.screen((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
        val outside = r.screen(box.right + 60f, (box.top + box.bottom) / 2f)
        r.touch.idle(250)
        r.touch.pinch(inside, outside, inside.first - 40f to inside.second, outside.first + 40f to outside.second)
        Smoke.pump(60)
        assertEquals("the view did not zoom", zoom, c.viewTransform.zoom, 1e-4f)
        assertTrue("the objects grew: ${st.width} -> ${t.transformState?.width}", t.transformState!!.width > st.width * 1.05f)
        t.commit()
        r.checkpoint("✓ pinch one finger inside", tolerance = 32, maxOffPermille = 20)

        // Pinch with both fingers BESIDE the objects: the view zooms out, the objects stay.
        t = relift()
        val st2 = t.transformState!!
        val b2 = st2.bounds()
        val a0 = r.screen(b2.right + 25f, b2.top + 10f)
        val b0 = r.screen(b2.right + 25f, b2.bottom - 10f)
        r.touch.idle(250)
        r.touch.pinch(a0, b0, a0.first to a0.second + 30f, b0.first to b0.second - 30f)
        Smoke.pump(60)
        assertTrue("the view zoomed out", c.viewTransform.zoom < zoom * 0.99f)
        assertEquals("the lifted objects did not change", st2, t.transformState)
        t.commit()
        r.checkpoint("✓ untouched after a view pinch", steps = 0)
        // (Back to the fitted view for the rest.)
        r.view.fitToScreen()
        Smoke.pump(40)
    }

    private fun objectBar() {
        r.tool(ToolId.BRUSH)
        assertEquals(s3s4, c.vectors.selectedIds)
        val n = objects().size
        assertTrue(ObjectActions.duplicate(c))
        r.checkpoint("duplicate")
        assertEquals(ObjectActions.DUPLICATE_LABEL, c.undoManager.undoLabel)
        assertEquals(n + 2, objects().size)
        val copies = c.vectors.selectedIds
        assertEquals("the copies are selected", 2, copies.size)
        assertTrue(copies.none { it in s3s4 })
        // The copies overlap their originals: Backward puts them behind.
        assertTrue(ObjectActions.arrange(c, ObjectEdits.Arrange.BACKWARD))
        r.checkpoint("backward")
        val order = objects().map { it.id }
        assertTrue("a copy is behind an original", copies.any { cp -> s3s4.any { o -> order.indexOf(cp) < order.indexOf(o) } })
        assertTrue(ObjectActions.arrange(c, ObjectEdits.Arrange.FRONT))
        r.checkpoint("front")
        assertEquals(copies, objects().takeLast(2).map { it.id }.toSet())
        c.color = green
        assertTrue(ObjectActions.recolor(c, linesOnly = false))
        r.checkpoint("recolor")
        assertTrue(copies.all { (vec.vector!!.byId(it) as VStroke).color == green })
        assertTrue(ObjectActions.delete(c))
        r.checkpoint("delete")
        assertEquals(n, objects().size)
        assertTrue(c.vectors.selectedIds.isEmpty())
        c.color = ink
    }

    private fun shapeTool(): ShapeTool {
        r.tool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    private fun bucket() {
        // A closed rectangle without a fill.
        val shape = shapeTool()
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f) }
        r.stroke(420f to 380f, 470f to 430f, 520f to 480f)
        assertTrue(shape.hasPendingWork)
        shape.commit()
        r.checkpoint("rectangle")
        val rect = objects().last() as VShape
        assertFalse("no fill yet", rect.shape.style.fill)
        r.tool(ToolId.FILL)
        c.color = orange
        r.tap(470f, 430f)
        r.checkpoint("bucket on the rectangle")
        assertEquals("Fill object", c.undoManager.undoLabel)
        val filled = (vec.vector!!.byId(rect.id) as VShape).shape
        assertTrue("a fill was added", filled.style.fill)
        assertEquals(orange, filled.fillColor)
        assertEquals("the line keeps its color", ink, filled.strokeColor)
        // An area enclosed by four strokes.
        r.tool(ToolId.BRUSH)
        c.color = ink
        r.stroke(545f to 380f, 600f to 380f, 655f to 380f); r.checkpoint("square top")
        r.stroke(650f to 375f, 650f to 430f, 650f to 485f); r.checkpoint("square right")
        r.stroke(655f to 480f, 600f to 480f, 545f to 480f); r.checkpoint("square bottom")
        r.stroke(550f to 485f, 550f to 430f, 550f to 375f); r.checkpoint("square left")
        r.tool(ToolId.FILL)
        c.color = orange
        val n = objects().size
        r.tap(600f, 430f)
        assertTrue("the area fill landed", Smoke.pumpUntil(10_000) { objects().size == n + 1 })
        r.checkpoint("bucket on an enclosed area")
        assertEquals("Fill", c.undoManager.undoLabel)
        val fill = objects().first() as VPath
        assertEquals("under the line art", VPaint.Solid(orange), fill.fill)
        val fb = VectorOps.bounds(fill)
        assertTrue("it fills the square: $fb", fb.left in 540f..560f && fb.right in 640f..660f && fb.top in 370f..390f && fb.bottom in 470f..490f)
        c.color = ink
    }

    private fun shapePhase() {
        val tool = shapeTool()
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 5f, fillColor = green) }
        r.stroke(675f to 385f, 725f to 430f, 775f to 475f)
        assertTrue(tool.hasPendingWork)
        tool.commit()
        r.checkpoint("ellipse")
        assertEquals(ShapeTool.SHAPE_LABEL, c.undoManager.undoLabel)
        val ellipse = objects().last() as VShape
        assertEquals(ShapeType.ELLIPSE, ellipse.shape.type)
        // Reopen by a tap; Points mode; a point moved; ✓ is one step in place.
        r.tap(725f, 430f)
        assertTrue("reopened", tool.editingObject)
        tool.setPointEditing(true)
        assertTrue(tool.pointsMode)
        val pts = tool.docAnchors()!!
        val p0 = pts[0].pos
        tool.movePoint(0, Vec2(p0.x + 12f, p0.y - 8f))
        tool.commit()
        r.checkpoint("✓ edit shape")
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        val edited = vec.vector!!.byId(ellipse.id) as VShape
        assertTrue("custom points", edited.shape.points != null)
        assertEquals("kept its place in the stack", objects().indexOfFirst { it.id == ellipse.id }, objects().lastIndex)
        // Reopen, change, ✕: nothing changes, no step.
        r.tap(725f, 430f)
        assertTrue(tool.editingObject)
        tool.nudge(10, 0)
        tool.discard()
        r.checkpoint("✕ edit shape", steps = 0)
        assertSame(edited, vec.vector!!.byId(ellipse.id))
        tool.setPointEditing(false)
    }

    private fun curvePhase() {
        r.tool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.update { it.copy(stroke = CurveStroke.PLAIN, fill = false) }
        r.tap(40f, 440f); r.tap(160f, 500f); r.tap(300f, 440f)
        assertEquals(3, curve.anchors.size)
        curve.select(1)
        curve.setWidth(1, 2.5f)
        curve.endNumericEdit()
        curve.commit()
        r.checkpoint("curve")
        assertEquals("Curve", c.undoManager.undoLabel)
        val path = objects().last() as VPath
        assertEquals(listOf(1f, 2.5f, 1f), path.subpaths.single().anchors.map { it.width })
        assertFalse(path.polyline)

        r.tool(ToolId.POLYLINE)
        val poly = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        poly.update { it.copy(stroke = CurveStroke.PLAIN, fill = false) }
        r.tap(330f, 440f); r.tap(370f, 505f); r.tap(405f, 450f)
        poly.select(2)
        poly.setWidth(2, 0.4f)
        poly.endNumericEdit()
        poly.commit()
        r.checkpoint("polyline")
        val pl = objects().last() as VPath
        assertTrue(pl.polyline)
        assertEquals(0.4f, pl.subpaths.single().anchors[2].width, 0f)

        // Reopen the curve by a tap on it; move its middle point; ✓ is one step in place.
        r.tool(ToolId.CURVE)
        r.tap(160f, 500f)
        assertTrue("the curve reopened", curve.isReopened)
        assertEquals(2.5f, curve.anchors[1].width, 0f)
        curve.moveAnchor(1, Vec2(160f, 488f))
        curve.endNumericEdit()
        curve.commit()
        r.checkpoint("✓ edit path")
        assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
        val again = vec.vector!!.byId(path.id) as VPath
        assertEquals(488f, again.subpaths.single().anchors[1].y, 1e-3f)
        assertEquals(2.5f, again.subpaths.single().anchors[1].width, 0f)
        r.tool(ToolId.BRUSH)
    }

    private fun historyBackAndForth() {
        val top = c.undoManager.undoCount
        assertTrue("a long history: $top", top >= 20)
        // Two-finger taps undo, three-finger taps redo (the canvas gestures).
        repeat(3) { r.undoAndCheck("gesture undo ${it + 1}", viaGesture = true) }
        repeat(2) { r.redoAndCheck("gesture redo ${it + 1}", viaGesture = true) }
        // All the way back to the new canvas...
        while (c.undoManager.undoCount > 0) r.undoAndCheck("undo")
        assertFalse("raster mode at the start", c.isVectorMode)
        assertNull(c.doc.layers[1].vector)
        // ... and forward to the end.
        while (c.undoManager.canRedo) r.redoAndCheck("redo")
        assertEquals(top, c.undoManager.undoCount)
        // Back and forth in the middle.
        repeat(7) { r.undoAndCheck("undo b") }
        repeat(4) { r.redoAndCheck("redo b") }
        repeat(2) { r.undoAndCheck("undo c") }
        while (c.undoManager.canRedo) r.redoAndCheck("redo c")
        assertSame(vec, c.doc.layers[1])
        c.selectLayer(vec)
        r.resync()
    }

    private fun saveReloadEditable() {
        val before = r.snapshot()
        r.saveAndReopen()
        r.assertState("reloaded", before)
        vec = c.doc.layers.first { it.name == "Vector 1" }
        assertTrue(vec.isVectorLayer)
        r.assertCacheFresh("reloaded", vec)
        c.selectLayer(vec)
        assertTrue(c.isVectorMode)
        // Still editable: a stroke, a lasso + move, Undo.
        r.tool(ToolId.BRUSH)
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f)
        c.color = ink
        r.stroke(440f to 300f, 500f to 320f, 540f to 300f)
        r.checkpoint("stroke after reload")
        r.tool(ToolId.LASSO)
        r.stroke(425f to 285f, 555f to 285f, 555f to 335f, 425f to 335f, 425f to 287f)
        assertTrue(Smoke.pumpUntil(10_000) { c.vectors.selectedIds.isNotEmpty() })
        r.checkpoint("lasso after reload", steps = 0)
        r.tool(ToolId.TRANSFORM)
        val t = transform()
        t.moveBy(-20f, 10f)
        t.commit()
        r.checkpoint("move after reload", tolerance = 32, maxOffPermille = 20)
        r.undoAndCheck("undo after reload")
        r.undoAndCheck("undo after reload 2")
        // Saved again and reloaded: the same.
        val s = r.snapshot()
        r.saveAndReopen()
        r.assertState("reloaded twice", s)
        vec = c.doc.layers.first { it.name == "Vector 1" }
    }

    private fun vectorOffAndOn() {
        c.selectLayer(vec)
        assertTrue(c.isVectorMode)
        c.toggleVectorMode()
        r.checkpoint("Vector off", steps = 0)
        assertFalse(c.isVectorMode)
        assertSame(c.doc.layers[0], c.activeLayer)
        c.toggleVectorMode()
        r.checkpoint("Vector on again", steps = 0)
        assertSame("the vector layer is reused", vec, c.activeLayer)
        assertEquals(2, c.doc.layers.size)
    }

    @Test
    fun theWholeVectorWorkflowAsTheUserDoesIt() {
        newCanvasAndVector()
        brushStrokes()
        vectorEraser()
        lassoSelect()
        transformPhase()
        objectBar()
        bucket()
        shapePhase()
        curvePhase()
        assertTrue("still a vector layer after everything", vec.isVectorLayer)
        historyBackAndForth()
        saveReloadEditable()
        vectorOffAndOn()
        assertNotNull(vec.vector)
    }

    // Focused entries into the same flow (a failure in an early phase hides the later ones).

    @Test
    fun drawEraseSelectTransform() {
        newCanvasAndVector()
        brushStrokes()
        vectorEraser()
        lassoSelect()
        transformPhase()
    }

    @Test
    fun objectBarBucketShapesAndPaths() {
        newCanvasAndVector()
        brushStrokes()
        lassoSelect()
        objectBar()
        bucket()
        shapePhase()
        curvePhase()
        historyBackAndForth()
    }
}
