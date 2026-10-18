package com.brushwork.paint.v19.a1

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ErasableTool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPreview
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.vector.CurveStripUi
import com.brushwork.paint.ui.vector.curveFacts
import com.brushwork.paint.ui.vector.curveSpec
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.ToolEraseSession
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 A1 (R11 a, design 3.2 A1 steps 6-8): Curve, Polyline and Path erase with their own outline
 * ([ErasableTool]). The outline erases live as it changes (through the render override, never
 * the overlay) and is drawn dashed; ✓ makes ONE step "Erase line", "Erase fill" or "Erase path";
 * a raster layer loses pixels, a text layer turns into pixels with v1.8's toast, a vector or a
 * legacy shape layer loses objects in the Eraser's mode, which the row-1 mode cell shows. Erase
 * mode is transient: off when the tool is chosen again.
 */
@RunWith(RobolectricTestRunner::class)
class CurveEraseRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 300
    private val h = 220
    private val red = 0xFFD02020.toInt()
    private val blue = 0xFF3070C0.toInt()

    /** One layer, active: "raster" (red), "text" (red pixels of a text), "vector" (no objects yet). */
    private fun setup(kind: String = "raster"): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)).also { l ->
            when (kind) {
                "raster" -> l.bitmap.eraseColor(red)
                "text" -> { l.textData = "{}"; l.bitmap.eraseColor(red) }
                "vector" -> l.vector = VectorContent.EMPTY
            }
        }
        doc.activeLayerIndex = 0
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
            it.color = 0xFF203080.toInt()
            it.snapping.enabled = false
        }
    }

    private val EditorController.layer: Layer get() = doc.activeLayer

    /** A plain line 10 px wide, no fill, open. */
    private val plain: (CurveSettings) -> CurveSettings = { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 10f, useBrushSize = false, fill = false, closed = false) }

    /** Tool [id], erasing, with the points [pts] (none selected). */
    private fun EditorController.eraser(id: ToolId, vararg pts: Vec2, look: (CurveSettings) -> CurveSettings = plain): CurveTool {
        selectTool(id)
        val t = tools.getValue(id) as CurveTool
        t.update(look)
        t.setErase(true)
        for (p in pts) assertTrue("$id adds $p", t.addAnchor(p))
        t.deselect()
        return t
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** What the render override shows the layer as, now. */
    private fun shown(o: LayerRenderOverride): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        assertTrue(o.drawContent(Canvas(b)))
        return pixels(b)
    }

    private fun at(px: IntArray, x: Int, y: Int) = px[y * w + x]

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The observers (main color, layers, the Eraser's mode...) see what changed. */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        idle()
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(blue), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
    )

    // ------------------------------------------------------------------ pixels

    @Test
    fun onARasterLayerTheOutlineErasesLiveAndTheCommitIsOneStep() {
        val c = setup()
        val drawn = pixels(c.layer.bitmap)
        val steps = c.undoManager.undoCount
        val t = c.eraser(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f))
        assertTrue(t.eraseOn)
        // Live, through the layer's render override; the layer itself is untouched until ✓.
        val o = c.renderOverride
        assertTrue("$o", o is VectorPreview)
        assertSame(c.layer, o!!.layer)
        assertArrayEquals(drawn, pixels(c.layer.bitmap))
        var preview = shown(o)
        assertEquals(0, at(preview, 100, 100) ushr 24)
        assertEquals(red, at(preview, 120, 100))
        assertEquals(red, at(preview, 200, 100))
        // The outline changes: the erase follows it.
        assertTrue(t.addAnchor(Vec2(200f, 200f)))
        preview = shown(c.renderOverride!!)
        assertEquals(0, at(preview, 150, 200) ushr 24)
        assertEquals(0, at(preview, 100, 100) ushr 24)
        t.commit()
        assertNull(c.renderOverride)
        assertFalse(t.hasPendingWork)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        assertArrayEquals("the commit is what the preview showed", preview, pixels(c.layer.bitmap))
        assertTrue("erase mode stays on for the next outline", t.eraseOn)
        c.undo()
        assertArrayEquals(drawn, pixels(c.layer.bitmap))
    }

    @Test
    fun aDiscardedOutlineLeavesNoTraceAndTurningEraseOffPaintsAgain() {
        val c = setup()
        val drawn = pixels(c.layer.bitmap)
        val steps = c.undoManager.undoCount
        val t = c.eraser(ToolId.CURVE, Vec2(40f, 60f), Vec2(260f, 160f))
        assertTrue(c.renderOverride is VectorPreview)
        t.discard()
        assertNull(c.renderOverride)
        assertEquals(steps, c.undoManager.undoCount)
        assertArrayEquals(drawn, pixels(c.layer.bitmap))
        // A pending outline turned back to paint is a curve again (its preview, its step).
        for (p in listOf(Vec2(40f, 60f), Vec2(260f, 160f))) assertTrue(t.addAnchor(p))
        t.setErase(false)
        assertFalse(t.eraseOn)
        assertTrue(t.hasPendingWork)
        assertFalse("no erase preview", c.renderOverride is VectorPreview && shown(c.renderOverride!!).let { at(it, 150, 110) ushr 24 == 0 })
        t.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Curve", c.undoManager.undoLabel)
        assertEquals(0xFF203080.toInt(), c.layer.bitmap.getPixel(150, 110))
    }

    @Test
    fun aFillAloneErasesItsRegionAsEraseFill() {
        val c = setup()
        val t = c.eraser(ToolId.POLYLINE, Vec2(60f, 60f), Vec2(240f, 60f), Vec2(240f, 160f), Vec2(60f, 160f)) { plain(it).copy(closed = true) }
        t.setPaintMode(CurvePaint.FILL)
        assertEquals(CurveStroke.NONE, t.settings.stroke)
        val preview = shown(c.renderOverride!!)
        assertEquals(0, at(preview, 150, 110) ushr 24)
        assertEquals(red, at(preview, 30, 110))
        t.commit()
        assertEquals(EraseLabels19.STEP_FILL, c.undoManager.undoLabel)
        assertEquals(0, c.layer.bitmap.getPixel(150, 110) ushr 24)
        assertEquals(red, c.layer.bitmap.getPixel(30, 110))
    }

    @Test
    fun thePathToolsStepIsErasePathAndVaryingThicknessErasesTheLinesOutline() {
        run {
            val c = setup()
            val t = c.eraser(ToolId.PATH, Vec2(40f, 110f), Vec2(150f, 60f), Vec2(260f, 110f))
            t.commit()
            assertEquals(EraseLabels19.STEP_PATH, c.undoManager.undoLabel)
            assertTrue(pixels(c.layer.bitmap).any { it ushr 24 == 0 })
        }
        // Polyline at 300 % at its lower end: its outline widens there, as the line would.
        val c = setup()
        val t = c.eraser(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f))
        var preview = shown(c.renderOverride!!)
        assertEquals("10 px wide at 100 %", red, at(preview, 112, 190))
        t.setWidth(1, 3f)
        preview = shown(c.renderOverride!!)
        assertEquals(red, at(preview, 112, 40))
        assertEquals(0, at(preview, 112, 190) ushr 24)
        t.commit()
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        assertEquals(0, c.layer.bitmap.getPixel(112, 190) ushr 24)
    }

    @Test
    fun aTextLayerTurnsIntoPixelsInTheEraseStepWithItsToast() {
        val c = setup("text")
        val steps = c.undoManager.undoCount
        c.message = null
        val t = c.eraser(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f))
        assertEquals("{}", c.layer.textData)
        t.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(c.layer.textData)
        assertNotNull("v1.8's rasterize toast", c.message)
        assertEquals(0, c.layer.bitmap.getPixel(100, 100) ushr 24)
        c.undo()
        assertEquals("{}", c.layer.textData)
        assertEquals(red, c.layer.bitmap.getPixel(100, 100))
    }

    @Test
    fun aRefusedEraseShowsTheOutlineOnlySaysWhyOnceAndKeepsThePath() {
        val c = setup()
        c.layer.alphaLocked = true
        val steps = c.undoManager.undoCount
        c.message = null
        val t = c.eraser(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f))
        assertEquals(ToolEraseSession.alphaLockedMessage(c.layer), c.message)
        assertNull("the outline only", c.renderOverride)
        // (Said once: not again with every point.)
        c.message = null
        assertTrue(t.addAnchor(Vec2(200f, 200f)))
        assertNull(c.message)
        // ✓ says it again and keeps the path, as a refused paint commit does.
        t.commit()
        assertEquals(ToolEraseSession.alphaLockedMessage(c.layer), c.message)
        assertTrue(t.hasPendingWork)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(red, c.layer.bitmap.getPixel(100, 100))
        // A layer locked while the outline is pending refuses the commit: nothing changes.
        c.layer.alphaLocked = false
        t.discard()
        for (p in listOf(Vec2(100f, 30f), Vec2(100f, 200f))) assertTrue(t.addAnchor(p))
        assertTrue(c.renderOverride is VectorPreview)
        c.layer.locked = true
        t.commit()
        assertTrue(t.hasPendingWork)
        assertNull(c.renderOverride)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(red, c.layer.bitmap.getPixel(100, 100))
    }

    // ------------------------------------------------------------------ objects

    @Test
    fun onAVectorLayerTheOutlineErasesObjectsInTheErasersModeLive() {
        val c = setup("vector")
        c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f), box(200f, 30f, 260f, 90f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val before = c.layer.vector!!
        val steps = c.undoManager.undoCount
        val t = c.eraser(ToolId.CURVE, Vec2(100f, 20f), Vec2(100f, 200f))
        assertTrue("${c.renderOverride}", c.renderOverride is DoomPreview)
        assertSame("nothing changes before ✓", before, c.layer.vector)
        // Object: the touched box goes whole in the preview.
        assertEquals(0, at(shown(c.renderOverride!!), 60f.toInt(), 100) ushr 24)
        // The mode chosen meanwhile (the mode cell) applies at once: Partial keeps the rest of the box.
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        settle()
        val partial = shown(c.renderOverride!!)
        assertEquals(blue, at(partial, 60, 100))
        assertEquals(0, at(partial, 100, 100) ushr 24)
        t.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        assertNull(c.renderOverride)
        val after = c.layer.vector!!.objects
        assertEquals("the box cut in two, the other kept", 3, after.size)
        assertSame(before.objects[1], after.first { it.id == before.objects[1].id })
        c.undo()
        assertSame(before, c.layer.vector)
        // Object mode: one step takes the touched box away.
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val t2 = c.eraser(ToolId.CURVE, Vec2(100f, 20f), Vec2(100f, 200f))
        t2.commit()
        assertEquals(listOf(before.objects[1]), c.layer.vector!!.objects)
    }

    @Test
    fun aLegacyShapeLayerBecomesObjectsAndIsErasedInOneStep() {
        val c = setup()
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 120f, h = 80f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, fillColor = blue)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, w, h))!!
        c.selectLayer(shape)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        val data = shape.dataSnapshot()
        val steps = c.undoManager.undoCount
        val t = c.eraser(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f))
        assertTrue(c.renderOverride is DoomPreview)
        assertTrue("nothing changes before ✓", shape.isShapeLayer)
        t.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        assertNull(shape.shapeData)
        assertTrue(shape.isVectorLayer)
        assertEquals(2, shape.vector!!.objects.size)
        c.undo()
        assertEquals(data, shape.dataSnapshot())
    }

    @Test
    fun theModeCellStaysInRowOneWhileErasingAVectorOrShapeLayer() {
        val c = setup("vector")
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        for (id in A1Tools.CURVES) {
            val t = c.eraser(id)
            for (open in listOf(false, true)) {
                if (open) for (p in listOf(Vec2(40f, 60f), Vec2(150f, 120f), Vec2(260f, 60f))) assertTrue(t.addAnchor(p))
                val spec = curveSpec(t, curveFacts(t), CurveStripUi())
                val cell = spec.items.single { it.key == StripKeysV19.ERASE_MODE_CELL.key }
                assertEquals(StripRow.ROW1, cell.row)
                assertEquals(0, cell.overflowRank)
                assertEquals(EraseLabels19.MODE_CELL.format(VectorEraseMode.OBJECT.label), cell.label)
                // Every width, the VECTOR cell or not, the More row closed: it is in row 1.
                for (screen in listOf(360f, 392f)) for (chip in listOf(0f, 44f)) {
                    val split = StripLayout.split(spec, screen - 28f - chip)
                    assertTrue("$id open=$open at $screen dp", split.row1.any { it.key == cell.key })
                }
            }
            // The mode chosen: the cell says it.
            VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
            assertEquals(EraseLabels19.MODE_CELL.format(VectorEraseMode.PARTIAL.label), curveSpec(t, curveFacts(t), CurveStripUi()).items.single { it.key == StripKeysV19.ERASE_MODE_CELL.key }.label)
            VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
            // Painting: no cell.
            t.setErase(false)
            assertTrue(curveSpec(t, curveFacts(t), CurveStripUi()).items.none { it.key == StripKeysV19.ERASE_MODE_CELL.key })
            t.discard()
        }
        // Erasing pixels (a raster layer): no cell.
        val r = setup()
        val t = r.eraser(ToolId.CURVE)
        assertTrue(t.eraseOn)
        assertTrue(curveSpec(t, curveFacts(t), CurveStripUi()).items.none { it.key == StripKeysV19.ERASE_MODE_CELL.key })
    }

    // ------------------------------------------------------------------ the tool

    @Test
    fun theOutlineIsDashedWhileErasing() {
        fun accent(c: EditorController, t: CurveTool): Int {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            t.drawOverlay(Canvas(b), c.viewTransform)
            // Away from the points (their handles), on the line: accent blue, or the plain line's white.
            var n = 0
            for (x in 60..240) for (y in 105..115) {
                val p = b.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val bl = p and 0xFF
                if (bl > 200 && r < 140) n++
            }
            return n
        }
        val c = setup()
        val t = c.eraser(ToolId.POLYLINE, Vec2(30f, 110f), Vec2(270f, 110f))
        assertTrue("dashed in the accent", accent(c, t) > 20)
        t.setErase(false)
        assertEquals("the plain guide", 0, accent(c, t))
    }

    @Test
    fun eraseModeIsTransientAndSlotOneNamesTheTool() {
        val c = setup()
        val labels = mapOf(
            ToolId.CURVE to (EraseLabels19.WITH_CURVE to EraseLabels19.ERASING_CURVE),
            ToolId.POLYLINE to (EraseLabels19.WITH_POLYLINE to EraseLabels19.ERASING_POLYLINE),
            ToolId.PATH to (EraseLabels19.WITH_PATH to EraseLabels19.ERASING_PATH),
        )
        for ((id, l) in labels) {
            c.selectTool(id)
            val t = c.tools.getValue(id) as ErasableTool
            assertEquals(l.first, t.eraseLabel)
            assertEquals(l.second, t.erasingLabel)
            assertFalse(t.eraseOn)
            t.setErase(true)
            assertTrue(t.eraseOn)
            // Another layer chosen: still on. Another tool and back: off.
            c.selectLayer(c.layer)
            assertTrue(t.eraseOn)
            c.selectTool(ToolId.BRUSH)
            c.selectTool(id)
            assertFalse("$id: off when chosen again", t.eraseOn)
        }
        // A tool switch commits the pending outline as its erase step.
        val steps = c.undoManager.undoCount
        c.eraser(ToolId.POLYLINE, Vec2(100f, 30f), Vec2(100f, 200f))
        c.selectTool(ToolId.BRUSH)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        assertEquals(0, c.layer.bitmap.getPixel(100, 100) ushr 24)
    }


    @Test
    fun whileErasingATapOnAPathObjectStartsAnOutlineThere() {
        val c = setup("vector")
        // A curve painted on the vector layer.
        c.selectTool(ToolId.CURVE)
        val t = c.tools.getValue(ToolId.CURVE) as CurveTool
        t.update(plain)
        for (p in listOf(Vec2(40f, 110f), Vec2(260f, 110f))) assertTrue(t.addAnchor(p))
        t.commit()
        val made = c.layer.vector!!.objects.single() as VPath
        // Erasing: a tap on its line is the outline's first point, not a reopening.
        t.setErase(true)
        c.tap(Vec2(150f, 110f))
        idle()
        assertFalse(t.isReopened)
        assertEquals(1, t.pointCount)
        t.discard()
        // Painting: the same tap reopens it; erase turned on applies the edit first.
        t.setErase(false)
        c.tap(Vec2(150f, 110f))
        idle()
        assertTrue(t.isReopened)
        t.setErase(true)
        assertFalse(t.isReopened)
        assertTrue(t.eraseOn)
        assertEquals(listOf(made), c.layer.vector!!.objects)
    }

    private fun EditorController.tap(p: Vec2) {
        pointerDown(ToolPoint(p.x, p.y))
        pointerUp(ToolPoint(p.x, p.y))
    }
}
