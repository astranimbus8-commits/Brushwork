package com.brushwork.paint.v110.g2vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa.VectorQaRig
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 G2 (V3, Q10): a stroke color previewed through the pill's Object colors editor on an open
 * object outlined with the brush is taken by the live brush stroke without replaying it (no dab is
 * drawn again: `BrushTool.recolorPath`), and the object is placed exactly: its pixels are the
 * rendering of its data in the last color (the brush's texture is chalk, so its random values
 * matter). A Path with 8 kaleidoscope copies, which the Path tool also redraws only once per
 * previewed color (not again when its observer of the main color hears of it), and a shape
 * without copies (a vector layer keeps a shape's live outline only without copies). Each is ONE
 * undo step; undo, redo and save and reopen keep data and pixels together (I1, I2).
 */
@RunWith(RobolectricTestRunner::class)
class G2VectorStrokeRecolorTest {

    /** A rig on a document whose active layer is an empty vector layer. */
    private fun rig(): VectorQaRig {
        val rig = VectorQaRig(W, H)
        val doc = runBlocking { rig.repo.load(rig.projectId) }
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = doc.layers.lastIndex
        rig.open(doc)
        rig.c.brush = BrushLibrary.byId("chalk")!!.copy(size = 9f)
        rig.c.color = RED
        return rig
    }

    /** What the canvas shows of the active layer now (its live stroke included). */
    private fun shown(c: EditorController): Bitmap {
        val b = BitmapUtils.createLayerBitmap(W, H)
        val ov = c.renderOverride
        if (ov == null || !ov.drawContent(Canvas(b))) Canvas(b).drawBitmap(c.activeLayer.bitmap, 0f, 0f, null)
        return b
    }

    /** Painted pixels of [b] whose color is clearly [argb]'s primary (red, green or blue). */
    private fun count(b: Bitmap, argb: Int): Int {
        val px = IntArray(W * H).also { b.getPixels(it, 0, W, 0, 0, W, H) }
        val ch: (Int) -> Int = when (argb) {
            RED -> { p -> Color.red(p) }
            GREEN -> { p -> Color.green(p) }
            else -> { p -> Color.blue(p) }
        }
        return px.count { p -> Color.alpha(p) > 32 && ch(p) > 3 * (Color.red(p) + Color.green(p) + Color.blue(p) - ch(p)) / 2 + 16 }
    }

    /** Previews each of [colors] as the open object's stroke color: no dab drawn, shown in that color. */
    private fun previewEach(c: EditorController, ed: ObjectColorEditor, flush: () -> Unit, colors: List<Int>, eachFrame: () -> Unit = {}) {
        val brush = c.tools.getValue(ToolId.BRUSH) as BrushTool
        var before = RED
        for (argb in colors) {
            val work = brush.dabWork
            ed.preview(ObjectColorTarget.STROKE, argb)
            Snapshot.sendApplyNotifications()
            Smoke.pump(40)
            flush()
            eachFrame()
            assertEquals("${hex(argb)}: no dab drawn again for a new color", work, brush.dabWork, 0.0)
            val b = shown(c)
            assertTrue("${hex(argb)}: the stroke shows it", count(b, argb) > 100)
            assertEquals("${hex(argb)}: nothing left of ${hex(before)}", 0, count(b, before))
            before = argb
        }
    }

    @Test
    fun aBrushPathWithCopiesTakesThePreviewedColorWithoutAReplayAndIsPlacedExactly() {
        val rig = rig()
        try {
            val c = rig.c
            val layer = c.activeLayer
            c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 4))
            rig.tool(ToolId.CURVE)
            val t = c.tools.getValue(ToolId.CURVE) as CurveTool
            t.update { it.copy(stroke = CurveStroke.BRUSH, fill = false, closed = false) }
            for (p in listOf(Vec2(250f, 40f), Vec2(320f, 70f), Vec2(350f, 120f))) assertTrue(t.addAnchor(p))
            Smoke.pump(300)
            t.flushPreview()
            assertTrue("the brush stroke shows live", t.brushLive)
            assertEquals("8 copies", 8, t.copyMapsNow.size)
            assertTrue("in red", count(shown(c), RED) > 100)

            val ed = requireNotNull(t.objectColors) { "an open path has colors" }
            ed.begin()
            var builds = t.previewBuilds
            previewEach(c, ed, t::flushPreview, listOf(BLUE, GREEN)) {
                assertEquals("the path is drawn once for a new color", builds + 1, t.previewBuilds)
                builds = t.previewBuilds
            }
            ed.commit()
            t.commit()
            rig.checkpoint("the path placed in its previewed color")
            val p = layer.vector!!.objects.single() as VPath
            assertEquals(hex(GREEN), hex(p.stroke!!.color))
            assertEquals(VStrokeKind.BRUSH, p.stroke.kind)
            assertFalse("with its copies", p.copies.isEmpty)
            assertEquals("the user's red is back", hex(RED), hex(c.color))

            rig.undoAndCheck("undo the path")
            rig.redoAndCheck("redo the path")
            rig.saveAndReopen()
            val back = rig.c.activeLayer
            assertEquals(p, back.vector!!.objects.single())
            rig.assertCacheFresh("reopened", back)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aBrushOutlinedShapeTakesThePreviewedColorWithoutAReplayAndIsPlacedExactly() {
        val rig = rig()
        try {
            val c = rig.c
            val layer = c.activeLayer
            rig.tool(ToolId.SHAPE)
            val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
            tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.BRUSH) }
            c.pointerDown(ToolPoint(80f, 60f))
            c.pointerMove(ToolPoint(200f, 150f))
            c.pointerUp(ToolPoint(320f, 240f))
            Smoke.pump(300)
            tool.flushPreview()
            assertTrue("in red", count(shown(c), RED) > 100)

            val ed = requireNotNull(tool.objectColors) { "an open shape has colors" }
            ed.begin()
            previewEach(c, ed, tool::flushPreview, listOf(BLUE, GREEN))
            ed.commit()
            tool.commit()
            rig.checkpoint("the shape placed in its previewed color")
            val s = layer.vector!!.objects.single() as VShape
            assertEquals(hex(GREEN), hex(s.shape.strokeColor))
            assertTrue("outlined with the brush", s.shape.paintsWithBrush)
            assertEquals("the user's red is back", hex(RED), hex(c.color))

            rig.undoAndCheck("undo the shape")
            rig.redoAndCheck("redo the shape")
            rig.saveAndReopen()
            val back = rig.c.activeLayer
            assertEquals(s, back.vector!!.objects.single())
            rig.assertCacheFresh("reopened", back)
        } finally {
            rig.close()
        }
    }

    private fun hex(c: Int) = "%08X".format(c)

    private companion object {
        const val W = 400
        const val H = 300
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
