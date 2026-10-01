package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.layers.LayerOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/**
 * v1.4 editable shapes: a new shape goes into its own shape layer (one undo step), a tap opens it
 * again, edits are re-rendered as one undo step "Edit shape" (nothing when unchanged, ✕ restores),
 * shapes survive save / load, brush-stroked shapes repaint with their own brush, and painting on a
 * shape layer makes it a regular layer. Real Skia (Robolectric NATIVE graphics).
 */
@RunWith(RobolectricTestRunner::class)
class ShapeLayerEditTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun controller(doc: Document? = null, w: Int = 200, h: Int = 200): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = doc ?: Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        return EditorController(ctx, d, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = red
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.tap(x: Float, y: Float) = drag(x to y)

    private fun EditorController.composite(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        compositor.drawDocument(Canvas(out), null)
        return out
    }

    private fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    private fun copyOf(b: Bitmap): Bitmap = b.copy(Bitmap.Config.ARGB_8888, false)

    /** Number of pixels whose channels differ by more than [tol]. */
    private fun diff(a: Bitmap, b: Bitmap, tol: Int = 0): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if (p == q) continue
            var worst = 0
            for (s in 0..24 step 8) worst = maxOf(worst, abs(((p ushr s) and 0xFF) - ((q ushr s) and 0xFF)))
            if (worst > tol) n++
        }
        return n
    }

    /** A rectangle drawn with the tool, committed into its own layer. */
    private fun placeRect(c: EditorController, tool: ShapeTool, style: ShapeStyle = ShapeStyle.STROKE): Layer {
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = style, useBrushSize = false, strokeWidth = 4f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        return c.activeLayer
    }

    // ------------------------------------------------------------------ new shapes

    @Test
    fun newShapeGoesIntoItsOwnLayerAsOneStep() {
        val c = controller()
        val tool = shapeTool(c)
        assertTrue("editable is the default", tool.settings.editable)
        val base = c.activeLayer
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL) }
        c.drag(20f to 20f, 60f to 50f, 100f to 80f)
        assertEquals(1, c.doc.layers.size)
        assertEquals(red, c.composite().getPixel(60, 50))  // previewed before it is placed
        val undos = c.undoManager.undoCount
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertEquals(2, c.doc.layers.size)
        val layer = c.activeLayer
        assertTrue(layer !== base)
        assertEquals("Rectangle", layer.name)
        assertTrue(layer.isShapeLayer)
        val o = ShapeCodec.decode(layer.shapeData)!!
        assertEquals(ShapeType.RECTANGLE, o.type)
        assertEquals(ShapeBox(60f, 50f, 80f, 60f, 0f), o.box)
        assertEquals(red, o.fillColor)
        assertEquals(red, layer.bitmap.getPixel(60, 50))
        assertEquals("the active layer is untouched", 0, base.bitmap.getPixel(60, 50))
        assertEquals(undos + 1, c.undoManager.undoCount)
        assertEquals("Shape", c.undoManager.undoLabel)
        assertNull(c.renderOverride)
        // Names are made unique.
        c.drag(120f to 120f, 180f to 180f)
        tool.commit()
        assertEquals("Rectangle 2", c.activeLayer.name)
        c.undo(); c.undo()
        assertEquals(1, c.doc.layers.size)
        c.redo()
        assertEquals(2, c.doc.layers.size)
        assertTrue(c.doc.layers[1].isShapeLayer)
    }

    @Test
    fun optionOffDrawsIntoTheActiveLayer() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(editable = false, style = ShapeStyle.FILL) }
        c.drag(20f to 20f, 100f to 80f)
        tool.commit()
        assertEquals(1, c.doc.layers.size)
        assertEquals(red, c.activeLayer.bitmap.getPixel(60, 50))
        assertNull(c.activeLayer.shapeData)
    }

    private fun EditorController.overlay(): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        drawOverlays(Canvas(out), 0f)
        return out
    }

    @Test
    fun newShapesGoIntoTheirLayerAlsoOverALockedHiddenOrFadedLayer() {
        // Locked: the shape doesn't paint the active layer, so it can be placed.
        run {
            val c = controller()
            val tool = shapeTool(c)
            val base = c.activeLayer
            base.locked = true
            tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL) }
            c.drag(30f to 30f, 110f to 90f)
            assertTrue(tool.hasPendingWork)
            assertEquals(red, c.composite().getPixel(70, 60))
            tool.commit()
            assertEquals(2, c.doc.layers.size)
            assertTrue(c.activeLayer.isShapeLayer)
            assertEquals(red, c.activeLayer.bitmap.getPixel(70, 60))
        }
        // Hidden: the preview is drawn over the canvas (inside the hidden layer it would not show).
        run {
            val c = controller()
            val tool = shapeTool(c)
            c.activeLayer.visible = false
            tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL) }
            c.drag(30f to 30f, 110f to 90f)
            assertEquals(red, c.overlay().getPixel(70, 60))
            tool.commit()
            assertEquals(2, c.doc.layers.size)
            assertTrue(c.activeLayer.visible)
            assertEquals(red, c.composite().getPixel(70, 60))
        }
        // Half transparent: the preview shows the shape as its own (opaque) layer will.
        run {
            val c = controller()
            val tool = shapeTool(c)
            c.setLayerProps(c.activeLayer, c.activeLayer.props().copy(opacity = 0.5f))
            tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL) }
            c.drag(30f to 30f, 110f to 90f)
            assertEquals("not dimmed inside the faded layer", 0, c.composite().getPixel(70, 60))
            assertEquals(red, c.overlay().getPixel(70, 60))
            tool.commit()
            assertEquals(red, c.composite().getPixel(70, 60))
            assertNull(c.renderOverride)
        }
        // A brush outline over a locked layer: shown as a guide, painted into the new layer.
        run {
            val c = controller()
            val tool = shapeTool(c)
            c.activeLayer.locked = true
            tool.update { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.BRUSH) }
            c.drag(20f to 50f, 180f to 50f)
            tool.flushPreview()
            assertNull("no live stroke on the locked layer", c.message)
            tool.commit()
            assertEquals(2, c.doc.layers.size)
            val t = thickness(c.activeLayer.bitmap, 100, 30, 70)
            assertTrue("painted: $t", t in 4..9)
        }
    }

    @Test
    fun smudgeOutlinesArePaintedIntoTheActiveLayer() {
        val c = controller()
        // Smudge moves the pixels that are there: a new empty layer would get nothing.
        Canvas(c.activeLayer.bitmap).drawColor(blue)
        c.activeLayer.markChanged()
        c.selectTool(ToolId.SMUDGE)
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.BRUSH) }
        c.drag(20f to 50f, 180f to 50f)
        tool.flushPreview()
        tool.commit()
        assertEquals(1, c.doc.layers.size)
        assertNull(c.activeLayer.shapeData)
    }

    @Test
    fun theUserColorComesBackUnlessTheShapeWasRecolored() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool)
        c.color = blue
        c.tap(30f, 60f)
        assertSame(layer, tool.editingLayer)
        assertEquals("the shape's color", red, c.color)
        c.drag(70f to 60f, 80f to 60f, 90f to 60f)
        tool.commit()
        assertEquals("the user's color is back", blue, c.color)
        assertEquals(red, ShapeCodec.decode(layer.shapeData)!!.strokeColor)
        // Recolored: the new color stays the main color.
        val green = 0xFF00FF00.toInt()
        c.tap(50f, 60f)
        assertEquals(red, c.color)
        c.color = green
        tool.commit()
        assertEquals(green, c.color)
        assertEquals(green, ShapeCodec.decode(layer.shapeData)!!.strokeColor)
        // ✕ brings the user's color back too.
        c.color = blue
        c.tap(50f, 60f)
        tool.discard()
        assertEquals(blue, c.color)
    }

    @Test
    fun aNewShapeDrawnWhileAnotherIsOpenUsesTheUserOptions() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool, ShapeStyle.FILL)
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE) }
        c.color = blue
        c.tap(70f, 60f)
        assertSame(layer, tool.editingLayer)
        assertEquals(ShapeType.RECTANGLE, tool.settings.type)
        // A drag elsewhere: the opened rectangle is placed and the new shape is the user's own.
        c.drag(130f to 130f, 160f to 160f, 190f to 190f)
        assertNull(tool.editingLayer)
        assertEquals(ShapeType.ELLIPSE, tool.settings.type)
        assertEquals(blue, c.color)
        tool.commit()
        val o = ShapeCodec.decode(c.activeLayer.shapeData)!!
        assertEquals(ShapeType.ELLIPSE, o.type)
        assertEquals(blue, o.strokeColor)
        assertEquals(3, c.doc.layers.size)
    }

    @Test
    fun handlesGrabbedOffCenterDoNotJump() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f) }
        c.drag(30f to 30f, 110f to 90f)
        // The bottom-right handle (110, 90) grabbed 7 px off: the corner follows the finger's motion.
        c.drag(117f to 97f, 122f to 102f, 127f to 107f)
        assertEquals(ShapeBox(75f, 65f, 90f, 70f, 0f), tool.box)
        // Line ends too.
        tool.discard()
        tool.update { it.copy(type = ShapeType.LINE) }
        c.drag(30f to 150f, 130f to 150f)
        c.drag(136f to 154f, 140f to 154f, 146f to 154f)
        assertEquals(140f, tool.box!!.end.x, 1e-3f)
        assertEquals(150f, tool.box!!.end.y, 1e-3f)
    }

    // ------------------------------------------------------------------ editing again

    @Test
    fun tapOpensTheShapeAndEditsAreOneStep() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool)
        val before = copyOf(layer.bitmap)
        c.selectLayer(c.doc.layers[0])
        // A tap on its outline opens it: the layer becomes active, its options show.
        tool.update { it.copy(strokeWidth = 11f, useBrushSize = true) }
        c.tap(30f, 60f)
        assertSame(layer, tool.editingLayer)
        assertSame(layer, c.activeLayer)
        assertEquals(ShapeBox(70f, 60f, 80f, 60f, 0f), tool.box)
        assertEquals(4f, tool.strokeWidth, 0f)
        assertFalse("nothing changed yet", tool.hasUserChanges)
        // While it is edited the layer shows the edited shape in place (same pixels).
        assertEquals(0, diff(c.composite(), before))
        // Move it, resize it, recolor it.
        c.drag(70f to 60f, 90f to 70f, 110f to 80f)         // move by (40, 20)
        assertEquals(ShapeBox(110f, 80f, 80f, 60f, 0f), tool.box)
        assertEquals("old place hidden", 0, c.composite().getPixel(30, 60))
        assertEquals(red, c.composite().getPixel(70, 80))
        c.drag(150f to 110f, 160f to 120f)                  // bottom-right handle
        assertEquals(ShapeBox(115f, 85f, 90f, 70f, 0f), tool.box)
        c.color = blue
        tool.refreshPreview()
        tool.update { it.copy(corner = CornerStyle.ROUND, cornerRadius = 8f) }
        assertTrue(tool.hasUserChanges)
        val undos = c.undoManager.undoCount
        tool.commit()
        assertFalse(tool.hasPendingWork)
        assertNull(tool.editingLayer)
        assertNull(c.renderOverride)
        assertEquals(undos + 1, c.undoManager.undoCount)
        assertEquals("Edit shape", c.undoManager.undoLabel)
        assertTrue(layer.isShapeLayer)
        val o = ShapeCodec.decode(layer.shapeData)!!
        assertEquals(ShapeBox(115f, 85f, 90f, 70f, 0f), o.box)
        assertEquals(blue, o.strokeColor)
        assertEquals(CornerStyle.ROUND, o.corner)
        // The user's own options are back (the edit didn't change them).
        assertEquals(11f, tool.settings.strokeWidth, 0f)
        assertTrue(tool.settings.useBrushSize)
        // The pixels are exactly a shape freshly drawn at the new place.
        val fresh = controller()
        val ft = shapeTool(fresh)
        fresh.color = blue
        ft.update { it.copy(editable = false, type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f, corner = CornerStyle.ROUND, cornerRadius = 8f) }
        assertTrue(ft.ensurePending())
        ft.place(ShapeBox(115f, 85f, 90f, 70f, 0f))
        ft.commit()
        assertEquals(0, diff(layer.bitmap, fresh.activeLayer.bitmap))
        // Undo brings back the old shape (pixels and data), redo the new one.
        c.undo()
        assertEquals(0, diff(layer.bitmap, before))
        assertEquals(ShapeBox(70f, 60f, 80f, 60f, 0f), ShapeCodec.decode(layer.shapeData)!!.box)
        c.redo()
        assertEquals(0, diff(layer.bitmap, fresh.activeLayer.bitmap))
    }

    @Test
    fun anOpenedShapeIsDrawnInTheOverlayWhileDragged() {
        val c = controller()
        val tool = shapeTool(c)
        placeRect(c, tool, ShapeStyle.FILL)
        c.tap(70f, 60f)
        c.pointerDown(ToolPoint(70f, 60f))
        c.pointerMove(ToolPoint(90f, 60f))
        c.pointerMove(ToolPoint(110f, 60f))
        // The layer's own pixels stay hidden and the canvas tiles untouched: the moving shape is
        // drawn over them in the overlay.
        assertEquals(0, c.composite().getPixel(140, 60))
        assertEquals(0, c.composite().getPixel(50, 60))
        val overlay = BitmapUtils.createLayerBitmap(200, 200)
        c.drawOverlays(Canvas(overlay), 0f)
        assertEquals(red, overlay.getPixel(140, 60))
        c.pointerUp(ToolPoint(110f, 60f))
        assertEquals(red, c.composite().getPixel(140, 60))
        assertEquals(0, c.composite().getPixel(50, 60))
    }

    @Test
    fun committingAnUnchangedShapeRecordsNothing() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool)
        val undos = c.undoManager.undoCount
        val version = layer.contentVersion
        c.tap(30f, 60f)
        assertSame(layer, tool.editingLayer)
        // A tap on the shape changes nothing either.
        c.tap(70f, 60f)
        tool.commit()
        assertEquals(undos, c.undoManager.undoCount)
        assertEquals(version, layer.contentVersion)
        assertNull(c.renderOverride)
        // Opened and untouched: undo doesn't stop at it, it undoes the shape itself.
        c.tap(30f, 60f)
        assertEquals("Undo: Shape", HistoryLabels.undo(c))
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertEquals(1, c.doc.layers.size)
    }

    @Test
    fun discardLeavesTheLayerAsItWas() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool, ShapeStyle.STROKE_FILL)
        val before = copyOf(layer.bitmap)
        val data = layer.shapeData
        val shown = c.composite()
        c.tap(70f, 60f)                                     // filled inside opens it
        assertSame(layer, tool.editingLayer)
        c.drag(70f to 60f, 140f to 140f)
        tool.update { it.copy(type = ShapeType.ELLIPSE) }
        assertTrue(diff(c.composite(), shown) > 0)
        tool.discard()
        assertNull(tool.editingLayer)
        assertNull(c.renderOverride)
        assertEquals(0, diff(layer.bitmap, before))
        assertEquals(data, layer.shapeData)
        assertEquals(0, diff(c.composite(), shown))
        assertEquals(ShapeType.RECTANGLE, tool.settings.type)
        // Undo of an opened and changed shape throws the changes away (like ✕).
        c.tap(70f, 60f)
        c.drag(70f to 60f, 90f to 60f)
        c.undo()
        assertFalse(tool.hasPendingWork)
        assertEquals(0, diff(layer.bitmap, before))
        assertEquals(2, c.doc.layers.size)
    }

    @Test
    fun switchingToolsOrLayersCommitsTheEdit() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool)
        c.tap(30f, 60f)
        c.drag(70f to 60f, 80f to 60f, 90f to 60f)
        c.selectLayer(c.doc.layers[0])
        assertFalse(tool.hasPendingWork)
        assertEquals(ShapeBox(90f, 60f, 80f, 60f, 0f), ShapeCodec.decode(layer.shapeData)!!.box)
        assertEquals("Edit shape", c.undoManager.undoLabel)
        c.selectLayer(layer)
        c.tap(50f, 60f)
        c.drag(90f to 60f, 100f to 60f, 110f to 60f)
        c.selectTool(ToolId.BRUSH)
        assertEquals(ShapeBox(110f, 60f, 80f, 60f, 0f), ShapeCodec.decode(layer.shapeData)!!.box)
        assertNull(c.renderOverride)
    }

    @Test
    fun aDragOverAShapeLayerDrawsANewShape() {
        val c = controller()
        val tool = shapeTool(c)
        placeRect(c, tool, ShapeStyle.FILL)
        // Not open: a drag that starts on it draws another shape.
        c.drag(60f to 50f, 120f to 120f, 150f to 150f)
        assertNull(tool.editingLayer)
        assertEquals(ShapeBox(105f, 100f, 90f, 100f, 0f), tool.box)
        tool.commit()
        assertEquals(3, c.doc.layers.size)
    }

    @Test
    fun tapOutsideCommitsThenOpensTheShapeTapped() {
        val c = controller()
        val tool = shapeTool(c)
        val first = placeRect(c, tool, ShapeStyle.FILL)
        // A second shape pending somewhere else; a tap on the first one places it and opens the first.
        c.drag(130f to 130f, 190f to 190f)
        assertTrue(tool.hasPendingWork)
        c.tap(60f, 50f)
        assertEquals(3, c.doc.layers.size)
        assertSame(first, tool.editingLayer)
        assertTrue(c.doc.layers[2].isShapeLayer)
    }

    @Test
    fun aTapJustOutsideAnOpenedShapeClosesIt() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool)
        c.tap(30f, 60f)
        assertSame(layer, tool.editingLayer)
        c.drag(70f to 60f, 75f to 60f, 80f to 60f)
        // 14 px below the bottom edge, between two handles: outside the open shape (it is placed)
        // but close enough to its outline to open it; it must not open again right away.
        c.tap(60f, 104f)
        assertNull(tool.editingLayer)
        assertFalse(tool.hasPendingWork)
        assertEquals("Edit shape", c.undoManager.undoLabel)
        // A second tap there opens it.
        c.tap(60f, 104f)
        assertSame(layer, tool.editingLayer)
    }

    @Test
    fun editShapeFromTheLayersWindow() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool)
        c.selectTool(ToolId.BRUSH)
        c.selectLayer(c.doc.layers[0])
        assertTrue(LayerOps.editShape(c, layer))
        assertEquals(ToolId.SHAPE, c.activeToolId)
        assertSame(layer, tool.editingLayer)
        assertSame(layer, c.activeLayer)
        // Not a shape layer: refused with a message.
        tool.discard()
        assertFalse(LayerOps.editShape(c, c.doc.layers[0]))
        assertNotNull(c.message)
    }

    // ------------------------------------------------------------------ save / load

    @Test
    fun shapesSurviveSaveAndLoadAndCanBeEditedAgain() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "projects").deleteRecursively()
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.STAR, style = ShapeStyle.FILL, starPoints = 6) }
        c.drag(40f to 40f, 160f to 160f)
        tool.setPointEditing(true)
        tool.commit()
        val layer = c.activeLayer
        val repo = ProjectRepository(ctx)
        repo.save(c.doc, null)
        val loaded = repo.load(c.doc.id)
        val again = loaded.layers.first { it.id == layer.id }
        assertEquals(layer.shapeData, again.shapeData)
        assertNotNull(ShapeCodec.decode(again.shapeData)!!.points)
        val c2 = controller(loaded)
        val t2 = shapeTool(c2)
        assertTrue(t2.editLayer(again))
        assertEquals(12, t2.points!!.size)
        c2.drag(100f to 100f, 110f to 105f)
        t2.commit()
        assertEquals("Edit shape", c2.undoManager.undoLabel)
        assertTrue(again.isShapeLayer)
        assertEquals(110f, ShapeCodec.decode(again.shapeData)!!.cx, 1e-3f)
    }

    // ------------------------------------------------------------------ brush shapes and rasterizing

    /** Width (px) of the painted stroke crossing column [x] (alpha > 0). */
    private fun thickness(b: Bitmap, x: Int, y0: Int, y1: Int): Int = (y0..y1).count { (b.getPixel(x, it) ushr 24) > 0 }

    @Test
    fun brushStrokedShapeKeepsItsBrushAndStaysAShapeLayer() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.BRUSH) }
        c.drag(20f to 50f, 180f to 50f)
        tool.flushPreview()
        tool.commit()
        val layer = c.activeLayer
        assertTrue(layer.isShapeLayer)
        assertEquals("Shape", c.undoManager.undoLabel)
        assertEquals(2, c.doc.layers.size)
        val thick = thickness(layer.bitmap, 100, 30, 70)
        assertTrue("painted: $thick", thick in 4..9)
        val o = ShapeCodec.decode(layer.shapeData)!!
        assertEquals(ToolId.BRUSH, o.brushToolId)
        assertEquals(6f, o.brushPreset!!.size, 0f)
        // The user's brush changes meanwhile: the shape keeps its own.
        c.brush = c.brush.copy(size = 30f)
        c.tap(100f, 50f)
        assertSame(layer, tool.editingLayer)
        assertEquals(6f, tool.editedShapeBrush!!.size, 0f)
        c.drag(100f to 50f, 100f to 90f, 100f to 120f)
        tool.flushPreview()
        tool.commit()
        assertEquals("Edit shape", c.undoManager.undoLabel)
        assertNull("the live stroke's preview is gone", c.renderOverride)
        assertNull(tool.editingLayer)
        assertTrue("still a shape layer", layer.isShapeLayer)
        assertEquals(0, thickness(layer.bitmap, 100, 30, 70))
        val t2 = thickness(layer.bitmap, 100, 100, 140)
        assertTrue("repainted with its own brush: $t2", t2 in 4..9)
        assertEquals("the user's brush is unchanged", 30f, c.brush.size, 0f)
        c.undo()
        assertTrue(thickness(layer.bitmap, 100, 30, 70) in 4..9)
        assertEquals(0, thickness(layer.bitmap, 100, 100, 140))
        assertTrue(layer.isShapeLayer)
    }

    @Test
    fun anAlphaLockedBrushShapeKeepsItsOutlineWhenEdited() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.BRUSH) }
        c.drag(20f to 50f, 180f to 50f)
        tool.flushPreview()
        tool.commit()
        val layer = c.activeLayer
        c.toggleAlphaLock(layer)
        assertTrue(layer.alphaLocked)
        c.tap(100f, 50f)
        assertSame(layer, tool.editingLayer)
        c.drag(100f to 50f, 100f to 90f, 100f to 120f)
        tool.flushPreview()
        tool.commit()
        assertEquals("Edit shape", c.undoManager.undoLabel)
        assertEquals(0, thickness(layer.bitmap, 100, 30, 70))
        val t = thickness(layer.bitmap, 100, 100, 140)
        assertTrue("repainted where it moved: $t", t in 4..9)
        assertTrue("still alpha locked", layer.alphaLocked)
        assertTrue(layer.isShapeLayer)
    }

    @Test
    fun anEditedBrushShapeIsPaintedWholeDespiteASelection() {
        val c = controller()
        val tool = shapeTool(c)
        tool.update { it.copy(type = ShapeType.LINE, strokeWith = ShapeStroke.BRUSH) }
        c.drag(20f to 50f, 180f to 50f)
        tool.flushPreview()
        tool.commit()
        val layer = c.activeLayer
        // A selection of the left part only (made for something else).
        val sel = com.brushwork.paint.model.Selection.fromBytes(ByteArray(200 * 200) { i -> if (i % 200 < 70) -1 else 0 }, 200, 200)
        c.setSelection(sel)
        c.tap(100f, 50f)
        assertSame(layer, tool.editingLayer)
        c.drag(100f to 50f, 100f to 90f, 100f to 120f)
        tool.flushPreview()
        val undos = c.undoManager.undoCount
        tool.commit()
        assertEquals(undos + 1, c.undoManager.undoCount)
        assertEquals("Edit shape", c.undoManager.undoLabel)
        // Like its fill, the outline is drawn whole (the selection would cut it).
        val t = thickness(layer.bitmap, 150, 100, 140)
        assertTrue("painted outside the selection too: $t", t in 4..9)
        assertTrue(thickness(layer.bitmap, 40, 100, 140) in 4..9)
        assertSame("the selection is back", sel, c.selection)
        assertTrue(layer.isShapeLayer)
    }

    @Test
    fun paintingOnAShapeLayerMakesItARegularLayer() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool)
        c.selectTool(ToolId.BRUSH)
        c.drag(10f to 150f, 60f to 150f, 120f to 150f)
        assertNull(layer.shapeData)
        assertTrue(c.message!!.contains("regular layer"))
        // The shape tool now treats it as a normal layer.
        c.selectTool(ToolId.SHAPE)
        c.tap(30f, 60f)
        assertNull(tool.editingLayer)
        c.undo()
        assertNotNull(layer.shapeData)
        c.tap(30f, 60f)
        assertSame(layer, tool.editingLayer)
    }

    @Test
    fun editableShapesInEveryLayerState() {
        val states = listOf<Pair<String, (EditorController) -> Unit>>(
            "selection" to { c ->
                c.setSelection(com.brushwork.paint.model.Selection.fromBytes(ByteArray(200 * 200) { i -> if (i % 200 < 70) -1 else 0 }, 200, 200))
            },
            "mask" to { c -> c.addMask(c.activeLayer, fromSelection = false) },
            "alpha locked" to { c -> c.toggleAlphaLock(c.activeLayer) },
            "grayscale" to { c -> c.doc.colorMode = com.brushwork.paint.model.ColorMode.GRAYSCALE; c.onDocumentGeometryChanged() },
        )
        for ((state, setUp) in states) {
            val c = controller()
            val tool = shapeTool(c)
            setUp(c)
            c.undoManager.clear()
            val base = c.activeLayer
            val before = copyOf(base.bitmap)
            tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL) }
            c.drag(30f to 30f, 110f to 90f)
            tool.commit()
            assertEquals("$state: own layer", 2, c.doc.layers.size)
            val layer = c.activeLayer
            assertTrue("$state: shape layer", layer.isShapeLayer)
            assertEquals("$state: base untouched", 0, diff(base.bitmap, before))
            assertTrue("$state: painted", (layer.bitmap.getPixel(50, 60) ushr 24) > 0)
            if (state == "selection") assertEquals("clipped to the selection", 0, layer.bitmap.getPixel(90, 60))
            // Edited again: re-rendered whole (a re-edit ignores the selection, like text).
            c.tap(50f, 60f)
            assertTrue("$state: opened", tool.editingLayer === layer)
            c.drag(50f to 60f, 60f to 70f, 70f to 80f)
            tool.commit()
            assertEquals("$state: Edit shape", "Edit shape", c.undoManager.undoLabel)
            assertTrue("$state: moved", (layer.bitmap.getPixel(120, 100) ushr 24) > 0)
            assertNull("$state: preview gone", c.renderOverride)
            c.undo(); c.undo()
            assertEquals("$state: all undone", 1, c.doc.layers.size)
            assertEquals(0, diff(base.bitmap, before))
        }
    }

    @Test
    fun lockedOrHiddenShapeLayersDoNotOpen() {
        val c = controller()
        val tool = shapeTool(c)
        val layer = placeRect(c, tool)
        layer.locked = true
        c.tap(30f, 60f)
        assertNull(tool.editingLayer)
        layer.locked = false
        layer.visible = false
        c.tap(30f, 60f)
        assertNull(tool.editingLayer)
        layer.visible = true
        c.tap(30f, 60f)
        assertSame(layer, tool.editingLayer)
        tool.discard()
        // Points of the outline: a tap away from it does not open a hollow shape.
        c.tap(70f, 60f)
        assertNull(tool.editingLayer)
    }
}
