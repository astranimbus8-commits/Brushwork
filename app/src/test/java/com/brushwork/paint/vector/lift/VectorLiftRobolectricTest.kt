package com.brushwork.paint.vector.lift

import android.graphics.Canvas
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.transform.RefusingLiftProvider
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * v1.5 A2 (§4.9 Transform row, §6 A2): the Transform tool lifts vector OBJECTS — the object
 * selection, else what the pixel selection touches, else all — and ✓ maps their geometry exactly
 * as one undo step; taps pick objects; Distort makes paths; brush sizes scale by √|det|; whole-pixel
 * moves stay pixel-exact; Numbers / X-Y and pinch drive the same lift.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLiftRobolectricTest {
    private var kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun lifted(c: com.brushwork.paint.EditorController): Set<Long> = VectorLift.activeLift(c)?.ids ?: emptySet()

    private fun anchorsOf(p: VPath) = p.subpaths.flatMap { s -> s.anchors.map { Vec2(it.x, it.y) } }

    @Test
    fun anObjectSelectionIsLiftedAloneAndCommittedAsOneStep() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(260f, 40f, 360f, 140f), kit.ellipse(150f, 250f)), "Add")
        val before = layer.vector!!
        val pixBefore = kit.pixels(layer.bitmap)
        c.vectors.setSelection(layer, setOf(2L))
        val tool = kit.transform(c)
        val st = tool.transformState
        assertNotNull(st)
        assertEquals(setOf(2L), lifted(c))
        val b = VectorOps.bounds(before.byId(2)!!)
        assertEquals((ceil(b.right) - floor(b.left)).toInt(), st!!.srcW)
        assertEquals((ceil(b.bottom) - floor(b.top)).toInt(), st.srcH)
        assertSame("nothing changes while lifted", before, layer.vector)

        val steps = c.undoManager.undoCount
        tool.moveBy(30f, 20f)
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        assertNull(c.renderOverride)
        val after = layer.vector!!
        assertEquals(listOf(1L, 2L, 3L), after.objects.map { it.id })
        assertSame(before.objects[0], after.objects[0])
        assertSame(before.objects[2], after.objects[2])
        val moved = after.byId(2) as VPath
        assertEquals(anchorsOf(before.byId(2) as VPath).map { it + Vec2(30f, 20f) }, anchorsOf(moved))
        assertArrayEquals(kit.render(after), kit.pixels(layer.bitmap))
        assertEquals("the object stays selected", setOf(2L), c.vectors.selectedIds)

        c.undo()
        assertSame(before, layer.vector)
        assertArrayEquals(pixBefore, kit.pixels(layer.bitmap))
        c.redo()
        assertSame(after, layer.vector)
        assertArrayEquals(kit.render(after), kit.pixels(layer.bitmap))
    }

    @Test
    fun withoutASelectionEverythingIsLiftedAndThePreviewIsTheCache() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(260f, 40f, 360f, 140f), kit.ellipse(150f, 250f)), "Add")
        val tool = kit.transform(c)
        assertNotNull(tool.transformState)
        assertEquals(setOf(1L, 2L, 3L), lifted(c))
        assertNotSame(RefusingLiftProvider, c.vectors.liftProvider)
        // The lifted preview shows exactly what the layer showed.
        val withPreview = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        val plain = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        c.compositor.drawDocument(Canvas(withPreview), null, useOverrides = true, target = null)
        c.compositor.drawDocument(Canvas(plain), null, useOverrides = false, target = null)
        assertArrayEquals(kit.pixels(plain), kit.pixels(withPreview))
        // An untouched lift records nothing.
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps, c.undoManager.undoCount)
        assertNull(VectorLift.activeLift(c))
    }

    @Test
    fun transformAfterTheMagicWandLiftsTheObjectsItTouches() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(260f, 40f, 360f, 140f), kit.ellipse(150f, 250f)), "Add")
        c.selectTool(ToolId.MAGIC_WAND)
        (c.tools.getValue(ToolId.MAGIC_WAND) as MagicWandTool).selectAt(310f, 90f)
        assertTrue(kit.idleUntil { c.selection != null })
        val before = layer.vector!!
        val tool = kit.transform(c)
        assertEquals(setOf(2L), lifted(c))
        assertEquals("the touched objects become the object selection", setOf(2L), c.vectors.selectedIds)
        assertNotNull("the pixel selection stays", c.selection)
        tool.moveBy(0f, 100f)
        tool.commit()
        val after = layer.vector!!
        assertSame(before.objects[0], after.objects[0])
        assertSame(before.objects[2], after.objects[2])
        assertEquals(anchorsOf(before.byId(2) as VPath).map { it + Vec2(0f, 100f) }, anchorsOf(after.byId(2) as VPath))
        assertNotNull(layer.vector)
    }

    @Test
    fun aLargePixelSelectionIsSearchedInTheBackground() {
        val c = kit.controller()
        val layer = c.vec
        val boxes = (0 until 70).map { i -> kit.box(10f + (i % 10) * 40f, 10f + (i / 10) * 40f, 40f + (i % 10) * 40f, 40f + (i / 10) * 40f) }
        c.vectors.addObjects(layer, boxes, "Add")
        c.setSelection(kit.rectSelection(0, 0, 130, 85))
        val tool = kit.transform(c)
        assertTrue(kit.idleUntil { tool.transformState != null })
        // Columns 0..2 of rows 0..1 (the boxes reaching into 0..130 x 0..85).
        val expected = layer.vector!!.objects.filter { o -> val r = VectorOps.bounds(o); r.left < 130f && r.top < 85f }.map { it.id }.toSet()
        assertEquals(expected, lifted(c))
        tool.discard()
    }

    @Test
    fun tappingAnObjectOutsideTheBoxLiftsItAndTapsCycleThroughOverlaps() {
        val c = kit.controller()
        val layer = c.vec
        // A (bottom) and B (top) overlap at p; C is elsewhere.
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 240f, 200f), kit.ellipse(140f, 120f), kit.box(400f, 250f, 480f, 330f)), "Add")
        val p = Vec2(140f, 120f)
        c.vectors.setSelection(layer, setOf(3L))
        val tool = kit.transform(c)
        assertEquals(setOf(3L), lifted(c))
        // A tap on B, outside C's box: B alone is lifted.
        tool.onDown(ToolPoint(p.x, p.y))
        tool.onUp(ToolPoint(p.x, p.y))
        assertEquals(setOf(2L), lifted(c))
        assertEquals(setOf(2L), c.vectors.selectedIds)
        assertNotNull(tool.transformState)
        // A tap on empty canvas goes back to every object.
        tool.onDown(ToolPoint(480f, 40f))
        tool.onUp(ToolPoint(480f, 40f))
        assertEquals(setOf(1L, 2L, 3L), lifted(c))
        assertTrue(c.vectors.selectedIds.isEmpty())
        val all = VectorLift.activeLift(c)
        // ... and another one changes nothing (outside the box of everything).
        tool.onDown(ToolPoint(505f, 378f))
        tool.onUp(ToolPoint(505f, 378f))
        assertSame(all, VectorLift.activeLift(c))
        tool.discard()

        // Taps on the same spot go one object deeper each time, then wrap around.
        val provider = VectorLift.providerOf(c)
        assertTrue(provider.tapped(p))
        assertEquals(setOf(2L), c.vectors.selectedIds)
        assertTrue(provider.tapped(p))
        assertEquals(setOf(1L), c.vectors.selectedIds)
        assertTrue(provider.tapped(p))
        assertEquals(setOf(2L), c.vectors.selectedIds)
        // A tap elsewhere starts from the top again.
        assertTrue(provider.tapped(Vec2(440f, 290f)))
        assertEquals(setOf(3L), c.vectors.selectedIds)
    }

    @Test
    fun distortTurnsARectangleShapeIntoAPathWithMappedCorners() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.rectangle(200f, 150f, 120f, 80f)), "Add")
        val shape = layer.vector!!.byId(1) as VShape
        c.vectors.setSelection(layer, setOf(1L))
        val tool = kit.transform(c)
        tool.mode = TransformTool.Mode.DISTORT
        val corner = tool.transformState!!.corner(2)
        tool.onDown(ToolPoint(corner.x, corner.y))
        tool.onMove(ToolPoint(corner.x + 30f, corner.y + 20f))
        tool.onUp(ToolPoint(corner.x + 30f, corner.y + 20f))
        val st = tool.transformState!!
        assertTrue(st.isDistorted)
        val src = VectorLift.activeLift(c)!!.sourceRect
        val m = LiftGeometry.matrix(st, src.left, src.top)!!
        assertTrue("a perspective map", m[6] != 0f || m[7] != 0f)
        tool.commit()
        val path = layer.vector!!.byId(1)
        assertTrue("a distorted shape becomes a path", path is VPath)
        path as VPath
        val corners = anchorsOf(VectorOps.toPaths(shape)[0])
        assertEquals(corners.map { LiftGeometry.map(m, it.x, it.y) }, anchorsOf(path))
        assertEquals(VectorOps.transformed(shape, m).withId(1L), path)
        // The corner that was dragged moved the most; the opposite one hardly.
        val moves = corners.zip(anchorsOf(path)).map { (a, b) -> a.distanceTo(b) }
        assertTrue(moves.max() > 15f)
        assertTrue(moves.min() < 2f)
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
        c.undo()
        assertSame(shape, layer.vector!!.byId(1))
    }

    @Test
    fun scalingAStrokeScalesItsBrushBySqrtDet() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(60f, 100f, 200f, 120f)), "Add")
        val s0 = layer.vector!!.byId(1) as VStroke
        c.vectors.setSelection(layer, setOf(1L))
        var tool = kit.transform(c)
        tool.setScalePercent(200.0)
        tool.endNumericEdit()
        tool.commit()
        val s1 = layer.vector!!.byId(1) as VStroke
        assertEquals(2f, s1.sizeScale, 1e-4f)
        val n = s0.points.size - 1
        val d0 = Vec2(s0.points.x[0], s0.points.y[0]).distanceTo(Vec2(s0.points.x[n], s0.points.y[n]))
        val d1 = Vec2(s1.points.x[0], s1.points.y[0]).distanceTo(Vec2(s1.points.x[n], s1.points.y[n]))
        assertEquals(2f * d0, d1, 1e-2f)
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
        // Stretched 3x along one axis: √3 more.
        tool.start()
        tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.keepAspect = false
        tool.setSize(width = tool.transformState!!.width * 3.0, height = null)
        tool.endNumericEdit()
        tool.commit()
        val s2 = layer.vector!!.byId(1) as VStroke
        assertEquals(2f * sqrt(3f), s2.sizeScale, 1e-3f)
    }

    @Test
    fun aWholePixelMoveIsPixelExact() {
        kit.close()
        kit = ObjectTestKit(768, 320)
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(20f, 60f, 220f, 90f), kit.box(40f, 150f, 200f, 260f), kit.ellipse(120f, 200f)), "Add")
        val before = layer.vector!!
        val old = kit.pixels(layer.bitmap)
        val tool = kit.transform(c)
        assertEquals(setOf(1L, 2L, 3L), lifted(c))
        val shifts = c.vectors.shiftCount
        tool.moveBy(256f, 0f)
        tool.commit()
        // (v1.5 integration) The lift passes its exact whole-pixel move as a ShiftHint, and A1's
        // fast path takes it: the cache is shifted, not re-rendered.
        assertEquals("the move shifted the cache", shifts + 1, c.vectors.shiftCount)
        val after = layer.vector!!
        // The data moved by exactly 256 px (the shape is still a shape).
        val s0 = before.byId(1) as VStroke
        val s1 = after.byId(1) as VStroke
        for (i in 0 until s0.points.size) assertEquals(s0.points.x[i] + 256f, s1.points.x[i], 0f)
        assertArrayEquals(s0.points.y, s1.points.y, 0f)
        assertEquals(1f, s1.sizeScale, 0f)
        assertEquals(anchorsOf(before.byId(2) as VPath).map { it + Vec2(256f, 0f) }, anchorsOf(after.byId(2) as VPath))
        val e1 = after.byId(3) as VShape
        assertEquals((before.byId(3) as VShape).shape.cx + 256f, e1.shape.cx, 0f)
        // The pixels (VectorLayers.update's ShiftHint contract): exactly the old ones moved right
        // by 256 — which is a fresh render of the moved data except at anti-aliased edges where
        // the renderer's 256 px tile grid cuts a path (float rounding of the farther coordinates).
        val now = kit.pixels(layer.bitmap)
        for (y in 0 until kit.h) for (x in 0 until kit.w - 256) assertEquals("nothing left behind at $x,$y", 0, old[y * kit.w + x + 256])
        assertArrayEquals("the old cache shifted exactly", shiftedBy(old, 256, 0), now)
        assertCloseToARender(now, after)
        // Any whole-pixel move maps the data exactly, and shifts the cache exactly too.
        tool.start()
        tool.moveBy(-37f, 5f)
        tool.commit()
        assertEquals("the second move shifted the cache", shifts + 2, c.vectors.shiftCount)
        val again = layer.vector!!
        val s2 = again.byId(1) as VStroke
        for (i in 0 until s0.points.size) {
            assertEquals(s1.points.x[i] - 37f, s2.points.x[i], 0f)
            assertEquals(s1.points.y[i] + 5f, s2.points.y[i], 0f)
        }
        val moved = kit.pixels(layer.bitmap)
        assertArrayEquals("the cache shifted exactly", shiftedBy(now, -37, 5), moved)
        assertCloseToARender(moved, again)
    }

    /** [px] (the kit's document) moved by whole ([dx], [dy]) px, transparent where nothing lands. */
    private fun shiftedBy(px: IntArray, dx: Int, dy: Int): IntArray {
        val out = IntArray(px.size)
        for (y in 0 until kit.h) for (x in 0 until kit.w) {
            val sx = x - dx
            val sy = y - dy
            if (sx in 0 until kit.w && sy in 0 until kit.h) out[y * kit.w + x] = px[sy * kit.w + sx]
        }
        return out
    }

    /** [px] equals a fresh render of [content] but at a few anti-aliased edge pixels (the shift path's contract). */
    private fun assertCloseToARender(px: IntArray, content: com.brushwork.paint.vector.VectorContent) {
        val fresh = kit.render(content)
        var differing = 0
        var painted = 0
        for (i in px.indices) {
            if (fresh[i] != 0) painted++
            if (px[i] == fresh[i]) continue
            differing++
            for (sh in 0..24 step 8) {
                val d = kotlin.math.abs(((px[i] ushr sh) and 0xFF) - ((fresh[i] ushr sh) and 0xFF))
                assertTrue("channel off by $d at ${i % kit.w},${i / kit.w}", d <= 32)
            }
        }
        assertTrue("$differing of $painted pixels differ", differing * 50 <= painted)
    }

    @Test
    fun aBackgroundRenderKeepsShowingTheResultUntilItLands() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.stroke(200f, 200f, 300f, 220f)), "Add")
        val before = layer.vector!!
        c.vectors.setSelection(layer, setOf(1L))
        val tool = kit.transform(c)
        val provider = VectorLift.providerOf(c)
        val floating = VectorLift.activeLift(c)!!.floating
        var land: (() -> Unit)? = null
        val hints = ArrayList<VectorLayers.ShiftHint?>()
        provider.update = { l, after, label, shift, done ->
            hints += shift
            land = { c.vectors.update(l, after, label, shift = shift, onDone = done) }
        }
        tool.moveBy(256f, 100f)
        tool.commit()
        // A whole-pixel move: the vector service is told it may shift the cache.
        assertEquals(listOf(VectorLayers.ShiftHint(setOf(1L), 256, 100)), hints)
        // Still rendering: the layer is unchanged, but it shows the box where it goes.
        assertNull(tool.transformState)
        assertSame(before, layer.vector)
        assertNotNull(c.renderOverride)
        assertFalse("the preview is kept until then", floating.isRecycled)
        assertNull("no lift is open meanwhile (nothing can commit it twice)", VectorLift.activeLift(c))
        val shown = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        c.compositor.drawDocument(Canvas(shown), null, useOverrides = true, target = null)
        assertTrue(android.graphics.Color.alpha(shown.getPixel(90 + 256, 80 + 100)) == 255)
        assertEquals("the hole", 0, shown.getPixel(90, 80))
        assertTrue("the other objects stay", android.graphics.Color.alpha(shown.getPixel(250, 210)) > 0)
        // The render lands: the real pixels, one step, everything freed.
        land!!.invoke()
        assertNull(c.renderOverride)
        assertTrue(floating.isRecycled)
        val after = layer.vector!!
        assertEquals(anchorsOf(before.byId(1) as VPath).map { it + Vec2(256f, 100f) }, anchorsOf(after.byId(1) as VPath))
        assertArrayEquals(kit.render(after), kit.pixels(layer.bitmap))
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        // A delete that renders in the background keeps the hole meanwhile.
        tool.start()
        tool.deleteContent()
        assertSame(after, layer.vector)
        val holed = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        c.compositor.drawDocument(Canvas(holed), null, useOverrides = true, target = null)
        assertEquals(0, holed.getPixel(90 + 256, 80 + 100))
        land!!.invoke()
        assertNull(c.renderOverride)
        assertNull(layer.vector!!.byId(1))
        assertTrue(c.vectors.selectedIds.isEmpty())
    }

    @Test
    fun theXyAdapterMovesTheLiftedObjects() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(260f, 40f, 360f, 140f)), "Add")
        val box0 = anchorsOf(layer.vector!!.byId(2) as VPath)
        c.vectors.setSelection(layer, setOf(2L))
        val tool = kit.transform(c)
        val p0 = tool.anchorPosition!!
        tool.setAnchorPosition(x = 300.0)
        tool.setAnchorPosition(y = 220.0)
        tool.endNumericEdit()
        assertEquals(Vec2(300f, 220f), tool.anchorPosition)
        tool.commit()
        val d = Vec2(300f, 220f) - p0
        val box1 = anchorsOf(layer.vector!!.byId(2) as VPath)
        for (i in box0.indices) {
            assertEquals(box0[i].x + d.x, box1[i].x, 1e-3f)
            assertEquals(box0[i].y + d.y, box1[i].y, 1e-3f)
        }
        assertEquals(1, c.undoManager.undoCount - 1)
    }

    @Test
    fun aPinchWithOneFingerOnTheBoxScalesTheObjects() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(150f, 150f, 300f, 170f)), "Add")
        c.vectors.setSelection(layer, setOf(1L))
        val tool = kit.transform(c)
        val st = tool.transformState!!
        val box = st.bounds()
        // Both fingers beside the box (the midpoint inside): the view pinches.
        val l = Vec2(box.left - 40f, (box.top + box.bottom) / 2)
        val r = Vec2(box.right + 40f, (box.top + box.bottom) / 2)
        assertFalse(tool.onTwoFingerStart((l + r) / 2f, l, r))
        // One finger on the box: the objects scale.
        val inside = st.center()
        val far = Vec2(box.right + 150f, box.bottom + 120f)
        assertTrue(tool.onTwoFingerStart((inside + far) / 2f, inside, far))
        tool.onTwoFingerGesture(Vec2.ZERO, 1.5f, 0f)
        tool.onTwoFingerEnd(cancelled = false)
        tool.commit()
        val s = layer.vector!!.byId(1) as VStroke
        assertEquals(1.5f, s.sizeScale, 1e-3f)
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
    }

    @Test
    fun deleteRemovesTheLiftedObjectsAsOneStep() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(260f, 40f, 360f, 140f)), "Add")
        val before = layer.vector!!
        c.vectors.setSelection(layer, setOf(2L))
        val tool = kit.transform(c)
        val steps = c.undoManager.undoCount
        assertTrue(tool.deleteContent())
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.DELETE_LABEL, c.undoManager.undoLabel)
        assertEquals(listOf(1L), layer.vector!!.objects.map { it.id })
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertArrayEquals(kit.render(layer.vector!!), kit.pixels(layer.bitmap))
        c.undo()
        assertSame(before, layer.vector)
    }

    @Test
    fun nothingToLiftIsSaidAndNeverRasterizes() {
        val c = kit.controller()
        val layer = c.vec
        // An empty vector layer: the pixel path finds nothing.
        assertSame(RefusingLiftProvider, c.vectors.liftProvider)
        val tool = kit.transform(c)
        assertNull(tool.transformState)
        assertEquals(VectorLiftProvider.NOTHING_TO_TRANSFORM, c.message)
        // ... also with a pixel selection (transparent pixels are never lifted).
        tool.onDeactivate()
        c.message = null
        c.setSelection(kit.rectSelection(10, 10, 100, 100))
        tool.start()
        assertNull(tool.transformState)
        assertEquals(VectorLiftProvider.NOTHING_TO_TRANSFORM, c.message)
        assertEquals(VectorContent.EMPTY, layer.vector)
        // A selection away from every object.
        c.vectors.addObjects(layer, listOf(kit.box(260f, 40f, 360f, 140f)), "Add")
        c.message = null
        tool.start()
        assertNull(tool.transformState)
        assertEquals(VectorLiftProvider.SELECTION_TOUCHES_NOTHING, c.message)
        assertNotNull(layer.vector)
    }

    @Test
    fun liftingOnAHiddenLayerIsRefusedAndAnotherLayersSelectionIsIgnored() {
        val c = kit.controller()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(260f, 40f, 360f, 140f)), "Add")
        // A selection on another layer doesn't count for this one.
        val other = c.addVectorLayer("Vector 2")!!
        c.vectors.addObjects(other, listOf(kit.box(10f, 10f, 50f, 50f)), "Add")
        c.vectors.setSelection(other, setOf(1L))
        c.selectLayer(layer)
        val tool = kit.transform(c)
        assertEquals(setOf(1L, 2L), lifted(c))
        tool.discard()
        layer.visible = false
        c.message = null
        tool.start()
        assertNull(tool.transformState)
        assertNotNull(c.message)
    }
}
