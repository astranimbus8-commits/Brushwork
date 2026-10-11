package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.drag
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.plainLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 (I16, design §3.7a): `CurveTool.editGeometry` is the one place a hand edit clears the
 * generator, in the same in-tool step (one undo brings the generator back with the points), with
 * the hint once per session. Thickness, color and stroke edits keep it; a move or uniform scale
 * of the whole curve carries it along; ✓ stores it, a hand-edited curve is stored without it; a
 * reopened path keeps a generator that still matches and loses a stale one silently.
 */
@RunWith(RobolectricTestRunner::class)
class GeneratorClearTest {
    private fun generated(kind: CurveKind, gen: CurveGenKind = CurveGenKind.ARCHIMEDEAN): Pair<EditorController, CurveTool> {
        val c = controller()
        val t = c.tool(if (kind == CurveKind.PATH) ToolId.PATH else ToolId.CURVE)
        t.plainLine()
        assertTrue(t.startGenerator(gen, null))
        return c to t
    }

    /** A point of [t], not an end, at least [away] px from both generator handles. */
    private fun farPoint(t: CurveTool, away: Float = 40f): Int {
        val h = t.generatorHandles!!
        return (1 until t.pointCount - 1).first { t.pointAt(it).distanceTo(h.centre) > away && t.pointAt(it).distanceTo(h.size) > away }
    }

    /** Runs each edit on the generated curve: it clears the generator, and ONE undo brings back the generator and the points. */
    private fun assertEachClearsInOneStep(c: EditorController, t: CurveTool, edits: Map<String, () -> Unit>) {
        val g = t.generator!!
        val anchors = t.anchors
        val spline = t.spline
        for ((what, edit) in edits) {
            edit()
            assertNull("$what clears the generator", t.generator)
            assertTrue(what, t.undoStep())
            assertEquals("$what: one undo brings the generator back", g, t.generator)
            assertEquals("$what: ... with the points", anchors, t.anchors)
            assertEquals(spline, t.spline)
            t.selectPoints(PointSelection.none(t.pointCount))
        }
        assertNotNull(c)
    }

    @Test
    fun pickingACurvePlacesItAtTheViewCentreAndOpensIt() {
        for (kind in listOf(CurveKind.CURVE, CurveKind.PATH)) for (gen in CurveGenKind.entries) {
            val (_, t) = generated(kind, gen)
            val g = t.generator!!
            assertEquals(gen, g.kind)
            val h = t.generatorHandles!!
            // The whole 400 × 300 canvas shows (no view area): its centre, 0.3 × 300 from it.
            assertEquals(Vec2(200f, 150f), h.centre)
            assertEquals("$kind / $gen", 90f, h.size.distanceTo(h.centre), 1e-3f)
            assertTrue(t.pointCount >= 2)
            val vp = t.buildVPath()!!
            assertEquals(g, vp.generator)
            assertTrue("$kind / $gen: the open curve is its generator's", CurveGenerators.matches(vp))
            assertFalse("only while nothing is open", t.startGenerator(gen, null))
        }
        val c = controller()
        val poly = c.tool(ToolId.POLYLINE)
        assertFalse("Polyline has no generators", poly.startGenerator(CurveGenKind.ROSE, null))
        assertEquals(0, poly.pointCount)
    }

    @Test
    fun everyHandEditOfABezierCurveClearsItInTheSameStep() {
        val (c, t) = generated(CurveKind.CURVE)
        val k = farPoint(t)
        val p = t.pointAt(k)
        assertEachClearsInOneStep(c, t, linkedMapOf(
            "a point moved (numbers)" to { t.moveAnchor(k, p + Vec2(3f, 0f)) },
            "a point added (numbers)" to { t.addAnchor(Vec2(20f, 20f)) },
            "a point deleted" to { t.deleteAnchor(k) },
            "Sharp corner" to { t.setSharp(k, true) },
            "Auto tangent" to { t.resetTangent(k) },
            "the handle lengths" to { t.applyHandleScale(150f) },
            "Closed" to { t.setClosed(true) },
            "a point nudged" to { t.select(k); t.nudge(1, 0) },
            "two points nudged" to { t.selectPoints(PointSelection.none(t.pointCount).plusAll(listOf(k, k + 1))); t.nudge(0, 1) },
            "a point dragged" to { c.drag(p.x to p.y, p.x + 10f to p.y, p.x + 25f to p.y + 5f) },
            "a tap on empty canvas" to { c.tap(15f, 15f) },
            "a tangent handle dragged" to {
                t.select(k)
                val (_, out) = t.handlesOf(k)
                val e = p + out
                c.drag(e.x to e.y, e.x + 10f to e.y + 4f, e.x + 20f to e.y + 8f)
            },
            "an uneven scale" to { t.objectScale!!.setScale(150f, 100f) },
        ))
        assertFalse("undoing Closed opens the spiral again", t.settings.closed)
    }

    @Test
    fun everyHandEditOfAPathClearsItInTheSameStep() {
        val (c, t) = generated(CurveKind.PATH, CurveGenKind.SINE)
        val k = farPoint(t, away = 30f)
        val p = t.pointAt(k)
        assertEachClearsInOneStep(c, t, linkedMapOf(
            "a control point moved (numbers)" to { t.moveAnchor(k, p + Vec2(0f, 2f)) },
            "a point added (numbers)" to { t.addAnchor(Vec2(20f, 20f)) },
            "a point deleted" to { t.deleteAnchor(k) },
            "Sharp" to { t.setSharp(k, true) },
            "a weight" to { t.setWeight(k, 2f) },
            "several weights" to { t.setWeights(listOf(k, k + 1), floatArrayOf(3f, 3f)) },
            "the order" to { t.setOrder(3) },
            "Cyclic" to { t.setCyclic(true) },
            "Endpoint" to { t.setEndpoint(false) },
            "a point nudged" to { t.select(k); t.nudge(-1, 0) },
            "a point dragged" to { c.drag(p.x to p.y, p.x to p.y + 12f, p.x + 3f to p.y + 30f) },
            "a tap on empty canvas" to { c.tap(15f, 285f) },
        ))
    }

    @Test
    fun thicknessColorAndStrokeKeepIt() {
        for (kind in listOf(CurveKind.CURVE, CurveKind.PATH)) {
            val (c, t) = generated(kind, CurveGenKind.ROSE)
            val g = t.generator!!
            t.setWidth(2, 2.5f)
            t.setWidths(listOf(0, 1), floatArrayOf(0.5f, 1.5f))
            t.resetAllWidths()
            t.setLineWidth(12f)
            t.setStrokeKind(CurveStroke.BRUSH)
            t.setPaintMode(CurvePaint.BOTH)
            t.update { it.copy(taper = true) }
            c.color = 0xFF00AA00.toInt()
            assertEquals("$kind: thickness, color and stroke are no geometry", g, t.generator)
            assertTrue(CurveGenerators.matches(t.buildVPath()!!))
            if (kind == CurveKind.CURVE) assertTrue("a rose is closed", t.settings.closed)
        }
    }

    @Test
    fun aMoveOrUniformScaleOfTheWholeCurveCarriesItAlong() {
        for (kind in listOf(CurveKind.CURVE, CurveKind.PATH)) {
            val (_, t) = generated(kind, CurveGenKind.GOLDEN)
            val g = t.generator!!
            // The pill's Center with no point selected moves the whole curve.
            val at = t.pillPosition.position!!
            t.pillPosition.setPosition(at.x + 30f, at.y - 10f)
            assertEquals(g.cx + 30f, t.generator!!.cx, 1e-3f)
            assertEquals(g.cy - 10f, t.generator!!.cy, 1e-3f)
            assertTrue("$kind: moved", CurveGenerators.matches(t.buildVPath()!!))
            t.nudge(1, 0)
            assertEquals(g.cx + 31f, t.generator!!.cx, 1e-3f)
            t.objectScale!!.setScale(50f, 50f)
            assertEquals(g.size / 2f, t.generator!!.size, 1e-3f)
            assertTrue("$kind: scaled alike", CurveGenerators.matches(t.buildVPath()!!))
            // Every point selected and moved together is the same move.
            t.selectPoints(PointSelection.all(t.pointCount))
            t.nudge(0, 1)
            assertNotNull(t.generator)
            assertTrue(CurveGenerators.matches(t.buildVPath()!!))
            t.selectPoints(PointSelection.none(t.pointCount))
            t.objectScale!!.setScale(50f, 80f)
            assertNull("$kind: a stretch is an edit", t.generator)
        }
    }

    @Test
    fun theHintShowsOncePerSessionAndNotForACancelledTouch() {
        val (c, t) = generated(CurveKind.CURVE, CurveGenKind.FERMAT)
        c.message = null
        // The first finger of a two-finger tap adds a point, then the touch is cancelled.
        c.pointerDown(ToolPoint(15f, 15f))
        assertNull("the hint waits for the touch to end", c.message)
        c.pointerCancel()
        assertNotNull("a cancelled touch edited nothing", t.generator)
        assertNull(c.message)
        t.deleteAnchor(farPoint(t))
        assertEquals(CurveGenLabels18.EDITED_HINT, c.message)
        assertTrue(t.undoStep())
        c.message = null
        t.deleteAnchor(farPoint(t))
        assertNull("once per session", t.generator)
        assertNull(c.message)
    }

    @Test
    fun theCommitStoresItAndAHandEditedCurveIsStoredWithout() {
        for (kind in listOf(CurveKind.CURVE, CurveKind.PATH)) {
            val (c, t) = generated(kind, CurveGenKind.LOGARITHMIC)
            val g = t.generator!!
            t.commit()
            val layer = c.doc.activeLayer
            val stored = layer.vector!!.objects.single() as VPath
            assertEquals(g, stored.generator)
            assertTrue("$kind: I16 holds in the document", CurveGenerators.matches(stored))
            assertTrue(t.startGenerator(CurveGenKind.ARCHIMEDEAN, null))
            t.moveAnchor(farPoint(t), Vec2(20f, 20f))
            t.commit()
            val edited = layer.vector!!.objects.last() as VPath
            assertNull("$kind: a hand-edited curve is a plain path", edited.generator)
        }
    }

    @Test
    fun aReopenedPathKeepsAMatchingGeneratorAndLosesAStaleOne() {
        for (kind in listOf(CurveKind.CURVE, CurveKind.PATH)) {
            val (c, t) = generated(kind, CurveGenKind.ROSE)
            val g = t.generator!!
            t.commit()
            val layer = c.doc.activeLayer
            val stored = layer.vector!!.objects.single() as VPath
            assertTrue(t.reopen(stored))
            assertTrue(t.isReopened)
            assertEquals("$kind: it still matches", g, t.generator)
            assertNotNull(t.generatorHandles)
            t.commit()
            assertEquals("untouched: stored as it was", stored, layer.vector!!.objects.single())

            // Another tool moved one of its points but kept the generator: it fails I16.
            val s = stored.subpaths[0]
            val stale = if (kind == CurveKind.PATH) {
                val sp = stored.spline!!
                val moved = sp.copy(points = sp.points.mapIndexed { i, q -> if (i == 3) q.copy(x = q.x + 4f) else q })
                stored.copy(spline = moved, subpaths = listOf(com.brushwork.paint.tools.vector.spline.SplineBezier.toSubpath(moved)))
            } else {
                stored.copy(subpaths = listOf(s.copy(anchors = s.anchors.mapIndexed { i, a -> if (i == 3) a.copy(x = a.x + 4f) else a })))
            }
            layer.vector = VectorContent(objects = listOf(stale))
            assertTrue(t.reopen(stale))
            assertTrue(t.isReopened)
            assertNull("$kind: a stale generator goes silently", t.generator)
            assertNull(t.generatorHandles)
            t.discard()
        }
    }
}
