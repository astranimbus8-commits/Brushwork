package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * v1.5 A1 review: a brush's tip turns and mirrors with its object (VectorOps.transformed), so a
 * rotated or flipped calligraphy stroke keeps its thick and thin parts where the turned pixels
 * have them (Transform ✓ shows what its preview showed; a rotated canvas looks the same), while
 * moves and scales leave the brush untouched.
 */
@RunWith(RobolectricTestRunner::class)
class VectorTipTurnRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val calligraphy: BrushPreset get() = BrushLibrary.byId("calligraphy")!!

    private fun stroke(preset: BrushPreset, w: Int, h: Int, seed: Long = 11L): VStroke {
        val n = 40
        // A wavy line through the canvas: every direction of the nib shows.
        val xs = FloatArray(n) { 30f + (w - 60f) * it / (n - 1) }
        val ys = FloatArray(n) { h / 2f + (h / 3f) * kotlin.math.sin(it / 5.0).toFloat() }
        return VStroke(1, preset = preset, color = 0xFF202020.toInt(), seed = seed, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun render(o: VObject, w: Int, h: Int): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), VectorContent(objects = listOf(o), nextId = 2), Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** [p] (w x h) turned a quarter clockwise: pixel (x, y) -> (h - 1 - y, x) of an h x w image. */
    private fun turnedCw(p: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = p[y * w + x]
        return out
    }

    /** [p] (w x h) mirrored left-right. */
    private fun mirrored(p: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + (w - 1 - x)] = p[y * w + x]
        return out
    }

    /** Share of the pixels painted in either image whose alpha differs by at most [tol]. */
    private fun agreement(a: IntArray, b: IntArray, tol: Int): Double {
        var painted = 0
        var close = 0
        for (i in a.indices) {
            val aa = a[i] ushr 24
            val bb = b[i] ushr 24
            if (aa == 0 && bb == 0) continue
            painted++
            if (abs(aa - bb) <= tol) close++
        }
        return if (painted == 0) 1.0 else close.toDouble() / painted
    }

    @Test
    fun movesAndScalesLeaveTheBrushUntouched() {
        val s = stroke(calligraphy, 200, 160)
        val moved = VectorOps.transformed(s, floatArrayOf(1f, 0f, 13f, 0f, 1f, -7f, 0f, 0f, 1f)) as VStroke
        assertSame(s.preset, moved.preset)
        assertEquals(s.copy(points = s.points.mapped(floatArrayOf(1f, 0f, 13f, 0f, 1f, -7f, 0f, 0f, 1f))), moved)
        val scaled = VectorOps.transformed(s, floatArrayOf(2f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 1f)) as VStroke
        assertSame(s.preset, scaled.preset)
        // A brush outline's brush and a shape's brush likewise.
        val path = VPath(2, subpaths = listOf(VSubpath(listOf(VAnchor(10f, 10f), VAnchor(90f, 40f)))),
            stroke = VStrokeStyle(VStrokeKind.BRUSH, color = -16777216, width = 24f, brushTool = ToolId.BRUSH, brush = calligraphy, seed = 3))
        val movedPath = VectorOps.transformed(path, floatArrayOf(1f, 0f, 5f, 0f, 1f, 5f, 0f, 0f, 1f)) as VPath
        assertSame(calligraphy, movedPath.stroke!!.brush)
    }

    @Test
    fun turnsAndMirrorsTurnTheTip() {
        val s = stroke(calligraphy, 200, 160)
        assertEquals(45f, s.preset.angle, 0f)
        val cw = VectorOps.transformed(s, floatArrayOf(0f, -1f, 160f, 1f, 0f, 0f, 0f, 0f, 1f)) as VStroke
        assertEquals(135f, cw.preset.angle, 1e-3f)
        assertEquals("only the angle changes", calligraphy.copy(angle = cw.preset.angle), cw.preset)
        val flipH = VectorOps.transformed(s, floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)) as VStroke
        assertEquals(135f, flipH.preset.angle, 1e-3f)
        val flipV = VectorOps.transformed(s, floatArrayOf(1f, 0f, 0f, 0f, -1f, 160f, 0f, 0f, 1f)) as VStroke
        assertEquals(315f, flipV.preset.angle, 1e-3f)
        // A shape's brush outline turns with the shape (a similarity keeps the shape).
        val shape = VShape(3, shape = ShapeObject(ShapeType.RECTANGLE, cx = 80f, cy = 60f, w = 90f, h = 50f, style = ShapeStyle.STROKE, strokeWidth = 24f,
            strokeWith = ShapeStroke.BRUSH, strokeColor = -16777216, brushTool = ToolId.BRUSH.name, brushPreset = calligraphy))
        assertTrue(shape.shape.paintsWithBrush)
        val turnedShape = VectorOps.transformed(shape, floatArrayOf(0f, -1f, 160f, 1f, 0f, 0f, 0f, 0f, 1f)) as VShape
        assertEquals(135f, turnedShape.shape.brushPreset!!.angle, 1e-3f)
        // Mirrored (a rectangle mirrors into a shape): the nib mirrors too.
        val mirroredShape = VectorOps.transformed(shape, floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)) as VShape
        assertEquals(135f, mirroredShape.shape.brushPreset!!.angle, 1e-3f)
        // Sheared (v1.7, design §4.5: an affine map keeps the shape, a rectangle under a skew becomes a point shape): its brush outline turns along.
        val sheared = VectorOps.transformed(shape, floatArrayOf(0f, -1f, 160f, 1f, 0.5f, 0f, 0f, 0f, 1f)) as VShape
        assertTrue(ShapeOutlines.isCustom(sheared.shape))
        assertTrue(abs(sheared.shape.brushPreset!!.angle - 45f) > 1f)
    }

    @Test
    fun aQuarterTurnedCalligraphyStrokeLooksLikeTheTurnedPixels() {
        val w = 220; val h = 170
        val s = stroke(calligraphy, w, h)
        val original = render(s, w, h)
        val m = floatArrayOf(0f, -1f, h.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
        val turned = VectorOps.transformed(s, m) as VStroke
        val expected = turnedCw(original, w, h)
        val got = render(turned, h, w)
        val withTip = agreement(expected, got, 24)
        // What the stroke looked like before the tip turned with it: thick and thin parts swap.
        val fixedNib = render(turned.copy(preset = s.preset), h, w)
        val withoutTip = agreement(expected, fixedNib, 24)
        assertTrue("turned tip $withTip vs fixed nib $withoutTip", withTip >= 0.97 && withTip > withoutTip + 0.1)
    }

    @Test
    fun aMirroredCalligraphyStrokeLooksLikeTheMirroredPixels() {
        val w = 220; val h = 170
        val s = stroke(calligraphy, w, h)
        val original = render(s, w, h)
        val flipped = VectorOps.transformed(s, floatArrayOf(-1f, 0f, w.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f)) as VStroke
        val expected = mirrored(original, w, h)
        val withTip = agreement(expected, render(flipped, w, h), 24)
        val withoutTip = agreement(expected, render(flipped.copy(preset = s.preset), w, h), 24)
        assertTrue("mirrored tip $withTip vs fixed nib $withoutTip", withTip >= 0.97 && withTip > withoutTip + 0.1)
    }

    @Test
    fun aRotatedCanvasKeepsTheLookOfACalligraphyLayer() {
        val w = 220; val h = 170
        val settings = AppSettings(RuntimeEnvironment.getApplication())
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        val c = EditorController(RuntimeEnvironment.getApplication(), doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val layer = doc.layers[0]
        c.vectors.addObjects(layer, listOf(stroke(calligraphy, w, h)), "Add")
        val before = px(layer.bitmap)
        val snap = CanvasSnapshot.of(doc)
        CanvasOps.commit(c, "Rotate 90° clockwise", snap, CanvasOps.rotate(snap, CanvasRotation.CW_90))
        assertEquals(h, doc.width)
        val now = px(layer.bitmap)
        // Drawn again from the turned object (exactly its rendering) ...
        assertArrayEquals(render(layer.vector!!.objects.single(), h, w), now)
        // ... which looks like the turned pixels.
        val a = agreement(turnedCw(before, w, h), now, 24)
        assertTrue("agreement $a", a >= 0.97)
    }
}
