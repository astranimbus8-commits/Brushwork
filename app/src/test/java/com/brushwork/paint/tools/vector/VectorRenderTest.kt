package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real-Skia checks of committing and previewing vector items (selection, alpha lock, masks). */
@RunWith(RobolectricTestRunner::class)
class VectorRenderTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun controller(w: Int = 100, h: Int = 100): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx))
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        VectorPath.polygon(listOf(Vec2(l, t), Vec2(r, t), Vec2(r, b), Vec2(l, b)))

    private fun fillSpec(l: Float, t: Float, r: Float, b: Float, color: Int) =
        VectorPaintSpec.build(rect(l, t, r, b), color, null, 0, 0f)!!

    @Test
    fun commitPaintsWithUndo() {
        val c = controller()
        val layer = c.doc.activeLayer
        assertTrue(VectorCommit.commit(c, layer, listOf(fillSpec(10f, 10f, 50f, 50f, red)), "Shape"))
        assertEquals(red, layer.bitmap.getPixel(30, 30))
        assertEquals(0, layer.bitmap.getPixel(70, 70))
        assertTrue(c.canUndo)
        c.undo()
        assertEquals(0, layer.bitmap.getPixel(30, 30))
        // Entirely outside the canvas: nothing to record.
        assertFalse(VectorCommit.commit(c, layer, listOf(fillSpec(200f, 200f, 300f, 300f, red)), "Shape"))
    }

    @Test
    fun strokeAndArrowHeadsPaint() {
        val c = controller()
        val layer = c.doc.activeLayer
        val arrow = ShapeGeometry.arrow(Vec2(10f, 50f), Vec2(90f, 50f), 4f, ArrowHeads.END, ArrowHeadStyle.FILLED, 5f)
        val spec = VectorPaintSpec.build(null, 0, arrow.stroke, blue, 4f, LineCapStyle.BUTT, JoinStyle.ROUND, arrow.fill)!!
        assertTrue(VectorCommit.commit(c, layer, listOf(spec), "Shape"))
        assertEquals(blue, layer.bitmap.getPixel(40, 50))     // shaft
        assertEquals(blue, layer.bitmap.getPixel(80, 53))     // inside the head, wider than the shaft
        assertEquals(0, layer.bitmap.getPixel(40, 56))
    }

    /** L-shaped selection (left column + bottom row): its bounds cover the whole canvas. */
    private fun lSelection() = Selection.fromPath(Path().apply {
        addRect(0f, 0f, 30f, 100f, Path.Direction.CW)
        addRect(0f, 70f, 100f, 100f, Path.Direction.CW)
    }, 100, 100, antiAlias = false)

    @Test
    fun selectionClipsTheShape() {
        val c = controller()
        val layer = c.doc.activeLayer
        c.setSelection(lSelection())
        assertTrue(VectorCommit.commit(c, layer, listOf(fillSpec(10f, 10f, 90f, 90f, red)), "Shape"))
        assertEquals(red, layer.bitmap.getPixel(20, 20))
        assertEquals(red, layer.bitmap.getPixel(80, 80))
        assertEquals(0, layer.bitmap.getPixel(60, 20)) // inside the bounds, outside the selection
    }

    @Test
    fun alphaLockOnlyPaintsExistingPixels() {
        val c = controller()
        val layer = c.doc.activeLayer
        Canvas(layer.bitmap).drawRect(0f, 0f, 50f, 100f, android.graphics.Paint().apply { color = blue })
        layer.alphaLocked = true
        assertTrue(VectorCommit.commit(c, layer, listOf(fillSpec(0f, 0f, 100f, 100f, red)), "Shape"))
        assertEquals(red, layer.bitmap.getPixel(20, 50))
        assertEquals(0, layer.bitmap.getPixel(80, 50))
    }

    @Test
    fun maskEditingPaintsLuminance() {
        val c = controller()
        val layer = c.doc.activeLayer
        layer.bitmap.eraseColor(blue)
        layer.mask = BitmapUtils.createMaskBitmap(100, 100, 0xFF000000.toInt())
        layer.editingMask = true
        assertTrue(VectorCommit.commit(c, layer, listOf(fillSpec(10f, 10f, 50f, 50f, red)), "Shape"))
        assertEquals(0xFF4C4C4C.toInt(), layer.mask!!.getPixel(30, 30)) // luminance of red = 76
        assertEquals(0xFF000000.toInt(), layer.mask!!.getPixel(70, 70))
        assertEquals(blue, layer.bitmap.getPixel(30, 30))
    }

    @Test
    fun grayscaleDocumentConstrainsColor() {
        val c = controller()
        c.doc.colorMode = ColorMode.GRAYSCALE
        val layer = c.doc.activeLayer
        assertTrue(VectorCommit.commit(c, layer, listOf(fillSpec(10f, 10f, 50f, 50f, red)), "Shape"))
        assertEquals(0xFF4C4C4C.toInt(), layer.bitmap.getPixel(30, 30))
    }

    private fun composite(c: EditorController): Bitmap {
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        c.compositor.drawDocument(Canvas(out), null)
        return out
    }

    @Test
    fun previewGoesThroughTheCompositor() {
        val c = controller()
        val layer = c.doc.activeLayer
        layer.opacity = 0.5f
        val preview = VectorPreview(c, layer).also { it.specs = listOf(fillSpec(10f, 10f, 50f, 50f, red)) }
        c.renderOverride = preview
        val out = composite(c)
        val px = out.getPixel(30, 30)
        assertEquals(0xFF0000, px and 0xFFFFFF)
        assertEquals(128f, (px ushr 24).toFloat(), 2f) // layer opacity applies to the preview
        assertEquals(0, layer.bitmap.getPixel(30, 30))   // nothing baked yet
        assertEquals(Rect4(8, 8, 52, 52), preview.dirtyRect().let { Rect4(it.left, it.top, it.right, it.bottom) })
    }

    @Test
    fun previewOfMaskEditing() {
        val c = controller()
        val layer = c.doc.activeLayer
        layer.bitmap.eraseColor(blue)
        layer.mask = BitmapUtils.createMaskBitmap(100, 100, 0xFF000000.toInt())
        layer.editingMask = true
        c.renderOverride = VectorPreview(c, layer).also { it.specs = listOf(fillSpec(10f, 10f, 50f, 50f, 0xFFFFFFFF.toInt())) }
        val out = composite(c)
        assertEquals(blue, out.getPixel(30, 30)) // revealed by the previewed white mask area
        assertEquals(0, out.getPixel(70, 70))
    }

    @Test
    fun previewRespectsSelectionAndAlphaLock() {
        val c = controller()
        val layer = c.doc.activeLayer
        Canvas(layer.bitmap).drawRect(0f, 0f, 100f, 50f, android.graphics.Paint().apply { color = blue })
        layer.alphaLocked = true
        c.setSelection(lSelection())
        c.renderOverride = VectorPreview(c, layer).also { it.specs = listOf(fillSpec(0f, 0f, 100f, 100f, red)) }
        val out = composite(c)
        assertEquals(red, out.getPixel(10, 10))  // selected + opaque
        assertEquals(blue, out.getPixel(60, 10)) // outside the selection
        assertEquals(0, out.getPixel(10, 80))    // transparent + alpha locked
        // Alpha lock + selection also holds when committing.
        c.renderOverride = null
        assertTrue(VectorCommit.commit(c, layer, listOf(fillSpec(0f, 0f, 100f, 100f, red)), "Shape"))
        assertEquals(red, layer.bitmap.getPixel(10, 10))
        assertEquals(blue, layer.bitmap.getPixel(60, 10))
        assertEquals(0, layer.bitmap.getPixel(10, 80))
    }

    @Test
    fun hollowShapeRegionsSkipTheInterior() {
        val outline = ShapeGeometry.outline(ShapeType.RECTANGLE, ShapeBox(2000f, 2500f, 3800f, 4800f, 0f), OutlineParams())
        val spec = VectorPaintSpec.build(null, 0, outline, red, 12f, LineCapStyle.ROUND, JoinStyle.MITER)!!
        assertTrue(spec.regions.size > 4)
        assertFalse(spec.regions.any { it.contains(2000, 2500) })
        assertFalse(spec.regions.any { it.contains(1000, 1000) })
        // Every corner and edge midpoint of the outline is covered.
        for ((x, y) in listOf(100 to 100, 3900 to 100, 3900 to 4900, 100 to 4900, 2000 to 100, 3900 to 2500)) {
            assertTrue("($x, $y)", spec.regions.any { it.contains(x, y) })
        }
        // Filled shapes cover their whole bounds.
        val filled = VectorPaintSpec.build(outline, red, outline, red, 12f, LineCapStyle.ROUND, JoinStyle.MITER)!!
        assertTrue(filled.regions.any { it.contains(2000, 2500) })
        // A huge off-canvas shape falls back to its bounds instead of thousands of boxes.
        val huge = ShapeGeometry.outline(ShapeType.ELLIPSE, ShapeBox(0f, 0f, 100_000f, 100_000f, 0f), OutlineParams())
        val hugeSpec = VectorPaintSpec.build(null, 0, huge, red, 4f)!!
        assertEquals(1, hugeSpec.regions.size)
    }

    /** Thick miters, square caps, star tips and arrowheads all stay inside the regions, so undo restores everything. */
    @Test
    fun everyPaintedPixelLiesInsideTheRegions() {
        val specs = listOf(
            VectorPaintSpec.build(null, 0, ShapeGeometry.outline(ShapeType.RECTANGLE, ShapeBox(150f, 150f, 180f, 120f, 30f), OutlineParams()), red, 24f, LineCapStyle.ROUND, JoinStyle.MITER)!!,
            VectorPaintSpec.build(null, 0, ShapeGeometry.outline(ShapeType.STAR, ShapeBox(150f, 150f, 220f, 220f, 17f), OutlineParams(starPoints = 7, innerRatio = 0.3f)), red, 8f, LineCapStyle.ROUND, JoinStyle.MITER)!!,
            VectorPaintSpec.build(null, 0, ShapeGeometry.outline(ShapeType.ELLIPSE, ShapeBox(150f, 150f, 260f, 90f, -40f), OutlineParams()), red, 30f, LineCapStyle.ROUND, JoinStyle.ROUND)!!,
            VectorPaintSpec.build(
                null, 0,
                CurveGeometry.toPath(listOf(CurveAnchor(20f, 280f), CurveAnchor(120f, 20f, sharp = true), CurveAnchor(280f, 260f)), false, 0f, false),
                red, 16f, LineCapStyle.SQUARE, JoinStyle.MITER,
            )!!,
            ShapeGeometry.arrow(Vec2(30f, 40f), Vec2(270f, 200f), 10f, ArrowHeads.BOTH, ArrowHeadStyle.FILLED, 4f).let {
                VectorPaintSpec.build(null, 0, it.stroke, red, 10f, LineCapStyle.SQUARE, JoinStyle.ROUND, it.fill)!!
            },
        )
        for ((i, spec) in specs.withIndex()) {
            for (withSelection in listOf(false, true)) {
                val c = controller(300, 300)
                val layer = c.doc.activeLayer
                if (withSelection) c.setSelection(Selection.all(300, 300), recordUndo = false) // tiled path
                assertTrue(VectorCommit.commit(c, layer, listOf(spec), "Shape"))
                val px = IntArray(300 * 300)
                layer.bitmap.getPixels(px, 0, 300, 0, 0, 300, 300)
                var painted = 0
                for (y in 0 until 300) for (x in 0 until 300) {
                    if (px[y * 300 + x] == 0) continue
                    painted++
                    assertTrue("spec $i sel=$withSelection pixel ($x, $y)", spec.regions.any { it.contains(x, y) })
                }
                assertTrue(painted > 100)
                c.undo()
                layer.bitmap.getPixels(px, 0, 300, 0, 0, 300, 300)
                assertTrue("spec $i sel=$withSelection undo", px.all { it == 0 })
            }
        }
    }

    private data class Rect4(val l: Int, val t: Int, val r: Int, val b: Int)
}
