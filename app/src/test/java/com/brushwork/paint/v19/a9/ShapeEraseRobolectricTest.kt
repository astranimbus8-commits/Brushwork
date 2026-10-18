package com.brushwork.paint.v19.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.tools.ErasableTool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.OverlayPainter
import com.brushwork.paint.tools.vector.ShapeBox
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPreview
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.ui.vector.ShapeStripInfo
import com.brushwork.paint.ui.vector.ShapeStripItems
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.9 A9, R11 (design 3.2 A9 steps 6 to 8): the Shape tool erases with its outline. Erase mode
 * on (bottom-bar slot 1 or "Stroke with"'s [EraseLabels19.INSTEAD_OF_PAINT]), the shape drawn
 * erases live as it changes, through the layer's render override (never pixels of a vector or
 * shape layer: I20): a raster layer loses pixels, a vector layer and a legacy shape layer lose
 * objects in the Eraser's mode (the shape layer becoming a vector layer in the same step), a text
 * layer turns into pixels with v1.8's toast. The commit is ONE step [EraseLabels19.STEP_SHAPE],
 * exactly what the preview showed; "Editable (own layer)", "New layer each time" and joining are
 * ignored. The outline shows dashed. The mode is transient, and the mode cell stays in row 1
 * while a vector or shape layer is erased.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeEraseRobolectricTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    private val blue = 0xFF3070C0.toInt()

    private fun setup(vectorLayer: Boolean = false): Pair<EditorController, ShapeTool> {
        val c = rig.controller(vectorLayer)
        c.snapping.enabled = false
        return c to rig.shapeTool(c)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** What the open erase shows its layer as, now. */
    private fun shown(c: EditorController, tool: ShapeTool): IntArray {
        val b = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        tool.eraseSession!!.drawPreview(Canvas(b))
        return pixels(b)
    }

    /** A plain vertical line from (100, 30) to (100, 200), [width] px wide. */
    private fun drawLine(c: EditorController, tool: ShapeTool, width: Float = 12f) {
        tool.update { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.PLAIN, useBrushSize = false, strokeWidth = width) }
        rig.drag(c, 100f to 30f, 100f to 120f, 100f to 200f)
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(blue), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
    )

    @Test
    fun aRasterLayerLosesTheOutlinesPixelsLiveAndInOneStep() {
        val (c, tool) = setup()
        val layer = c.doc.layers[0]
        layer.bitmap.eraseColor(rig.red)
        val w = c.doc.width
        val drawn = pixels(layer.bitmap)
        // "Editable (own layer)" and "New layer each time" are ignored while erasing.
        tool.update { it.copy(editable = true, newLayerEachTime = true) }
        val steps = c.undoManager.undoCount
        val layers = c.doc.layers.size
        tool.setErase(true)
        assertTrue(tool.eraseOn)
        // The 4 px outline of (30, 30) - (110, 90).
        rig.drawRect(c, tool)
        assertNotNull(tool.eraseSession)
        assertTrue("live, through the layer's render override", c.renderOverride is VectorPreview)
        assertSame(layer, c.renderOverride!!.layer)
        assertArrayEquals("the layer itself waits for the commit", drawn, pixels(layer.bitmap))
        assertEquals("on the outline", 0, shown(c, tool)[60 * w + 30] ushr 24)
        assertEquals("inside a stroke style", rig.red, shown(c, tool)[60 * w + 70])
        // An edit of the pending shape moves the erase with it.
        val b = tool.box!!
        tool.place(ShapeBox(b.cx + 40f, b.cy, b.w, b.h, b.rotationDeg))
        val preview = shown(c, tool)
        assertEquals("given back", rig.red, preview[60 * w + 30])
        assertEquals("the moved outline", 0, preview[60 * w + 70] ushr 24)
        assertArrayEquals(drawn, pixels(layer.bitmap))

        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertNull(tool.eraseSession)
        assertNull(tool.eraseOutline)
        assertNull(c.renderOverride)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_SHAPE, c.undoManager.undoLabel)
        assertEquals("no layer added", layers, c.doc.layers.size)
        assertArrayEquals("the commit is what the preview showed", preview, pixels(layer.bitmap))
        assertTrue("still erasing", tool.eraseOn)

        // The next shape dragged out lands the pending one first, a step of its own.
        rig.drawRect(c, tool)
        rig.drag(c, 160f to 120f, 200f to 150f, 240f to 180f)
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertTrue(tool.hasPendingWork)
        assertEquals(0, layer.bitmap.getPixel(30, 60) ushr 24)
        assertEquals("the new one waits for its commit", rig.red, layer.bitmap.getPixel(160, 150))
        tool.discard()
        assertNull(c.renderOverride)
        assertEquals(steps + 2, c.undoManager.undoCount)
        c.undo()
        c.undo()
        assertArrayEquals(drawn, pixels(layer.bitmap))
    }

    @Test
    fun aVectorLayerLosesObjectsInTheErasersModeAndGetsNoShape() {
        val (c, tool) = setup(vectorLayer = true)
        val vec = c.doc.layers[1]
        c.vectors.addObjects(vec, listOf(box(40f, 60f, 160f, 140f), box(200f, 30f, 260f, 90f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        val before = vec.vector!!
        val steps = c.undoManager.undoCount
        tool.setErase(true)
        drawLine(c, tool)
        assertTrue(c.renderOverride is DoomPreview)
        assertSame("nothing changes before the commit", before, vec.vector)
        val partial = shown(c, tool)
        assertEquals("the line's footprint is cut out", 0, partial[100 * c.doc.width + 100] ushr 24)
        // The mode cell picks Object: the erase follows at once (as the strip's onMode does).
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        tool.refreshPreview()
        val preview = shown(c, tool)
        assertFalse(preview.contentEquals(partial))
        assertEquals("the whole box goes", 0, preview[100 * c.doc.width + 60] ushr 24)

        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_SHAPE, c.undoManager.undoLabel)
        assertNull(c.renderOverride)
        val after = vec.vector!!.objects
        assertEquals("the box it touched is gone, no shape object added", listOf(before.objects[1]), after)
        assertTrue(after.none { it is VShape })
        assertArrayEquals("the commit is what the preview showed", preview, pixels(vec.bitmap))
        c.undo()
        assertSame(before, vec.vector)
    }

    @Test
    fun aLegacyShapeLayerBecomesAVectorLayerInTheEraseStep() {
        val (c, tool) = setup()
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 120f, h = 80f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, fillColor = blue)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, c.doc.width, c.doc.height))!!
        c.selectLayer(shape)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val data = shape.dataSnapshot()
        val cache = pixels(shape.bitmap)
        val steps = c.undoManager.undoCount
        val layers = c.doc.layers.size
        tool.setErase(true)
        // A tap on the shape doesn't open it while erasing.
        rig.tap(c, 40f, 100f)
        assertNull(tool.editingLayer)
        assertFalse(tool.hasPendingWork)
        drawLine(c, tool)
        assertTrue(c.renderOverride is DoomPreview)
        assertTrue("nothing changes before the commit (no join, no conversion)", shape.isShapeLayer)
        assertEquals(data, shape.dataSnapshot())

        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_SHAPE, c.undoManager.undoLabel)
        assertNull(shape.shapeData)
        assertTrue(shape.isVectorLayer)
        assertTrue("the shape it touched is gone", shape.vector!!.objects.isEmpty())
        assertEquals(layers, c.doc.layers.size)
        c.undo()
        assertEquals(data, shape.dataSnapshot())
        assertArrayEquals(cache, pixels(shape.bitmap))
    }

    @Test
    fun aTextLayerTurnsIntoPixelsInTheEraseStepWithV18sToast() {
        val (c, tool) = setup()
        val layer = c.doc.layers[0]
        layer.textData = "{}"
        layer.bitmap.eraseColor(rig.red)
        val steps = c.undoManager.undoCount
        tool.setErase(true)
        c.message = null
        drawLine(c, tool)
        assertNotNull(layer.textData)
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(layer.textData)
        assertNotNull("v1.8's rasterize toast", c.message)
        assertEquals(0, layer.bitmap.getPixel(100, 100) ushr 24)
        assertEquals(rig.red, layer.bitmap.getPixel(150, 100))
        c.undo()
        assertEquals("{}", layer.textData)
    }

    @Test
    fun eraseModeIsTransientAndTurningItOffLandsThePendingErase() {
        val (c, tool) = setup(vectorLayer = true)
        val erasable: ErasableTool = tool
        assertEquals(EraseLabels19.WITH_SHAPE, erasable.eraseLabel)
        assertEquals(EraseLabels19.ERASING_SHAPE, erasable.erasingLabel)
        val raster = c.doc.layers[0]
        c.selectLayer(raster)
        raster.bitmap.eraseColor(rig.red)
        val steps = c.undoManager.undoCount
        tool.setErase(true)
        rig.drawRect(c, tool)
        // Off: the pending erase lands (one step), then shapes paint again.
        tool.setErase(false)
        assertFalse(tool.eraseOn)
        assertFalse(tool.hasPendingWork)
        assertNull(c.renderOverride)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_SHAPE, c.undoManager.undoLabel)
        assertEquals(0, raster.bitmap.getPixel(30, 60) ushr 24)

        // A layer change keeps erase mode (the tool is paused, not chosen again) and lands the erase.
        tool.setErase(true)
        rig.drawRect(c, tool)
        c.selectLayer(c.doc.layers[1])
        assertTrue(tool.eraseOn)
        assertEquals(steps + 2, c.undoManager.undoCount)
        // A tool switch lands it too, and choosing the tool again turns erase mode off.
        c.selectLayer(raster)
        rig.drag(c, 160f to 120f, 200f to 150f, 240f to 180f)
        c.selectTool(ToolId.BRUSH)
        assertEquals(steps + 3, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_SHAPE, c.undoManager.undoLabel)
        assertNull(c.renderOverride)
        c.selectTool(ToolId.SHAPE)
        assertFalse(tool.eraseOn)
        // Painting again: a new shape is a shape.
        rig.drawRect(c, tool)
        assertNull(tool.eraseSession)
        assertNull(tool.eraseOutline)
    }

    @Test
    fun aLayerThatCantBeErasedShowsTheOutlineAloneAndLeavesNoTrace() {
        val (c, tool) = setup()
        val layer = c.doc.layers[0]
        layer.bitmap.eraseColor(rig.red)
        layer.locked = true
        val drawn = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount
        tool.setErase(true)
        c.message = null
        rig.drawRect(c, tool)
        assertNull(tool.eraseSession)
        assertNull(c.renderOverride)
        assertNotNull("the outline shows alone", tool.eraseOutline)
        assertTrue("${c.message}", c.message.orEmpty().contains(layer.name))
        // Its commit asks again: refused, the outline stays.
        tool.commit()
        assertTrue(tool.hasPendingWork)
        tool.discard()
        assertNull(tool.eraseOutline)
        assertEquals(steps, c.undoManager.undoCount)
        assertArrayEquals(drawn, pixels(layer.bitmap))
    }

    @Test
    fun theOutlineErasingIsDrawnDashed() {
        val (c, tool) = setup()
        c.doc.layers[0].bitmap.eraseColor(rig.red)
        val w = c.doc.width
        val h = c.doc.height
        /** The overlay holds the accent colour of a dashed line along the ellipse's arc away from its box. */
        fun accentOnArc(): Boolean {
            val b = tool.box!!
            val bmp = BitmapUtils.createLayerBitmap(w, h)
            tool.drawOverlay(Canvas(bmp), c.viewTransform)
            for (deg in 25..65) {
                val a = Math.toRadians(deg.toDouble())
                val x = (b.cx + b.w / 2f * cos(a)).roundToInt()
                val y = (b.cy + b.h / 2f * sin(a)).roundToInt()
                for (dx in -2..2) for (dy in -2..2) {
                    val p = bmp.getPixel((x + dx).coerceIn(0, w - 1), (y + dy).coerceIn(0, h - 1))
                    if (p == OverlayPainter.ACCENT) return true
                }
            }
            return false
        }
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f, keepProportions = false, fromCenter = false) }
        rig.drag(c, 90f to 70f, 150f to 120f, 210f to 170f)
        assertFalse("painting, the outline is the layer's preview", accentOnArc())
        tool.discard()
        tool.setErase(true)
        rig.drag(c, 90f to 70f, 150f to 120f, 210f to 170f)
        assertNotNull(tool.eraseSession)
        assertTrue("erasing, the outline is dashed in the accent", accentOnArc())
    }

    @Test
    fun theModeCellStaysInRowOneWhileAVectorOrShapeLayerIsErased() {
        val (c, tool) = setup(vectorLayer = true)
        val ctx = StripContext(selectionBarShown = false, vectorMode = false)
        fun spec() = StripSpec(
            ShapeStripItems.items(tool, c, ShapeStripInfo.of(tool, c.doc.dpi), {}, {}, {}, {}, barShown = false, modeCell = ShapeStripItems.modeCell(tool, c, ctx) {}),
        )
        val key = StripKeysV19.ERASE_MODE_CELL.key
        assertTrue("not while painting", spec().items.none { it.key == key })
        tool.setErase(true)
        for (pending in listOf(false, true)) {
            if (pending) drawLine(c, tool)
            val s = spec()
            val cell = s.items.single { it.key == key }
            assertEquals(StripRow.ROW1, cell.row)
            assertEquals(0, cell.overflowRank)
            assertEquals(StripKeysV19.ERASE_MODE_CELL.widthDp.toFloat(), cell.width.value)
            assertEquals(EraseLabels19.MODE_CELL.format(VectorEraserModes.mode(c).label), cell.label)
            // In row 1 at every width, with the VECTOR cell or not (the More row closed).
            for (screen in listOf(360f, 392f)) for (chip in listOf(false, true)) {
                val available = StripInventory.content(screen) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
                val row1 = StripLayout.split(s, available).row1.map { it.key }
                assertTrue("$screen dp, chip $chip, pending $pending: $row1", key in row1)
            }
            assertEquals(emptyList<String>(), StripInventory.rules(s))
        }
        // While it shows, "Points" leads the More row.
        assertEquals(ShapeStripItems.POINTS, spec().items.first { it.row == StripRow.MORE }.key)
        // Erasing pixels (a raster layer, a vector layer's mask): no cell.
        c.selectLayer(c.doc.layers[0])
        assertTrue(spec().items.none { it.key == key })
        tool.setErase(false)
        c.selectLayer(c.doc.layers[1])
        assertTrue("painting again", spec().items.none { it.key == key })
        assertEquals(StripRow.ROW1, spec().items.single { it.key == ShapeStripItems.POINTS }.row)
    }
}
