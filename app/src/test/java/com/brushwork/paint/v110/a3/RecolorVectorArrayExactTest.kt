package com.brushwork.paint.v110.a3

import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayLookKit.BLUE
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.CopyLook
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (R5 (c), A3 step 5): a vector array's Recolored copies are exact: every color slot of
 * every copy (a stroke's color, a path's solid fill, gradient stops and outline, a shape's stroke
 * and fill) is `ColorUtils.recolor(slot, g(t_k), s)` through `ObjectColorSlots.mapObject`, bit
 * for bit, the copy's opacity multiplied as for a tint. The shown copies, the objects Apply makes
 * and the SVG export carry the same colors; a Tinted look's copies are v1.9's.
 */
@RunWith(RobolectricTestRunner::class)
class RecolorVectorArrayExactTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun scene(c: EditorController): ExportScene =
        runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }

    private fun ExportScene.itemsOf(layer: Layer): List<SceneItem.Shape> = layers.first { it.name == layer.name }.items.map { it as SceneItem.Shape }

    @Test
    fun restyledRecolorsEverySlotExactly() {
        val look = CopyLook(opacity = 0.5f, tintColor = 0xFF00FF00.toInt(), tintStrength = 0.6f, colorMode = ArrayColorMode.RECOLOR)
        fun r(argb: Int) = ColorUtils.recolor(argb, look.tintColor, look.tintStrength)
        val stroke = VStroke(
            7, opacity = 0.8f, preset = BrushLibrary.defaultBrush, color = 0xFF336699.toInt(), seed = 1L, stylus = false,
            points = PackedPoints(floatArrayOf(0f, 10f), floatArrayOf(0f, 10f), floatArrayOf(1f, 1f)),
        )
        val s = ArrayDraw.restyled(stroke, look) as VStroke
        assertEquals(r(0xFF336699.toInt()), s.color)
        assertEquals(0.4f, s.opacity, 1e-6f)
        assertEquals(stroke.copy(color = s.color, opacity = s.opacity), s)

        val stops = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0x80FFFFFF.toInt()))
        val linear = ArrayLookKit.box(0f, 0f, 10f, 10f).copy(fill = VPaint.Linear(0f, 0f, 10f, 0f, stops), stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 2f))
        val l = ArrayDraw.restyled(linear, look) as VPath
        assertEquals(VPaint.Linear(0f, 0f, 10f, 0f, stops.map { it.copy(color = r(it.color)) }), l.fill)
        assertEquals("a stop's alpha is kept", 0x80, (l.fill as VPaint.Linear).stops[1].color ushr 24)
        assertEquals(r(0xFF000000.toInt()), l.stroke!!.color)
        assertEquals(linear.copy(fill = l.fill, stroke = l.stroke, opacity = 0.5f), l)
        val radial = linear.copy(fill = VPaint.Radial(5f, 5f, 5f, stops))
        assertEquals(VPaint.Radial(5f, 5f, 5f, stops.map { it.copy(color = r(it.color)) }), (ArrayDraw.restyled(radial, look) as VPath).fill)
        val solid = ArrayLookKit.box(0f, 0f, 10f, 10f, fill = GRAY)
        assertEquals(VPaint.Solid(r(GRAY)), (ArrayDraw.restyled(solid, look) as VPath).fill)
        val bare = linear.copy(fill = null, stroke = null)
        assertEquals(bare.copy(opacity = 0.5f), ArrayDraw.restyled(bare, look))

        // A shape: stroke and fill; its fill follows the stroke after when they come out equal (C4).
        val shape = VShape(3, opacity = 1f, shape = ShapeObject(ShapeType.ELLIPSE, strokeColor = 0xFF102030.toInt(), fillColor = 0xFFF0E0D0.toInt()))
        val sh = ArrayDraw.restyled(shape, look) as VShape
        assertEquals(r(0xFF102030.toInt()), sh.shape.strokeColor)
        assertEquals(r(0xFFF0E0D0.toInt()), sh.shape.fillColor)
        assertEquals(0.5f, sh.opacity)
        assertEquals(shape.shape.copy(strokeColor = sh.shape.strokeColor, fillColor = sh.shape.fillColor, fillFollowsColor = false), sh.shape)
        val full = ArrayDraw.restyled(shape, look.copy(tintStrength = 1f)) as VShape
        assertEquals("full strength: both the target", 0xFF00FF00.toInt(), full.shape.strokeColor)
        assertEquals(0xFF00FF00.toInt(), full.shape.fillColor)
        assertTrue(full.shape.fillFollowsColor)

        // Opacity alone: colors kept. A look that changes nothing: the same instance.
        val faded = ArrayDraw.restyled(shape, look.copy(opacity = 0.25f, tintStrength = 0f)) as VShape
        assertEquals(shape.shape, faded.shape)
        assertEquals(0.25f, faded.opacity)
        assertSame(shape, ArrayDraw.restyled(shape, look.copy(opacity = 1f, tintStrength = 0f)))

        // Tinted: v1.9's tint, unchanged.
        val tint = look.copy(colorMode = ArrayColorMode.TINT)
        assertEquals(ArrayTint.apply(0xFF336699.toInt(), tint.tintColor, tint.tintStrength), (ArrayDraw.restyled(stroke, tint) as VStroke).color)
        val tintedShape = ArrayDraw.restyled(shape, tint) as VShape
        assertEquals(ArrayTint.apply(0xFFF0E0D0.toInt(), tint.tintColor, tint.tintStrength), tintedShape.shape.fillColor)
        assertTrue("Tinted keeps the shape's own flag", tintedShape.shape.fillFollowsColor)
    }

    @Test
    fun theShownCopiesAndTheAppliedObjectsCarryTheRecoloredColors() {
        val c = ArrayLookKit.controller(app)
        val outline = 0xFF202020.toInt()
        val gradient = listOf(VStop(0f, 0xFFFFCC00.toInt()), VStop(1f, 0xFF00AA88.toInt()))
        val box = ArrayLookKit.box(20f, 30f, 60f, 70f, stroke = outline)
        val layer = ArrayLookKit.vectorArray(c, listOf(box, ArrayLookKit.box(30f, 40f, 50f, 60f).copy(fill = VPaint.Linear(30f, 0f, 50f, 0f, gradient), id = 1)))
        val look = ArrayLook(opacityEnd = 0.5f, tint = listOf(VStop(0f, RED), VStop(1f, BLUE)), tintStrength = 0.7f, colorMode = ArrayColorMode.RECOLOR)
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(count = 3, look = look))
        val source = layer.vector!!.objects
        val shown = ArrayDraw.effectiveVector(layer.dataSnapshot())!!.objects
        assertEquals("2 objects x 3 instances", 6, shown.size)
        // Copy 2 (End: blue, half) at the bottom, then copy 1 (Start: red), then the source as it is.
        for ((at, k) in listOf(0 to 2, 2 to 1)) {
            val target = if (k == 2) BLUE else RED
            val outer = shown[at] as VPath
            val inner = shown[at + 1] as VPath
            assertEquals("copy $k", source[0].id + (k.toLong() shl ArrayDraw.COPY_ID_SHIFT), outer.id)
            assertEquals(VPaint.Solid(ColorUtils.recolor(GRAY, target, 0.7f)), outer.fill)
            assertEquals(ColorUtils.recolor(outline, target, 0.7f), outer.stroke!!.color)
            assertEquals(gradient.map { it.copy(color = ColorUtils.recolor(it.color, target, 0.7f)) }, (inner.fill as VPaint.Linear).stops)
            assertEquals(if (k == 2) 0.5f else 1f, outer.opacity, 1e-6f)
        }
        assertEquals(source, shown.subList(4, 6))

        // Apply: real objects with the same colors; the source's own unchanged.
        assertTrue(ArrayOps.apply(c, layer))
        ArrayLookKit.settle(c)
        assertNull(layer.array)
        val objects = layer.vector!!.objects
        assertEquals(shown.size, objects.size)
        for (i in shown.indices) assertEquals(shown[i].withId(objects[i].id), objects[i])
    }

    @Test
    fun theSvgExportWritesTheRecoloredColors() {
        val c = ArrayLookKit.controller(app)
        val look = ArrayLook(opacityEnd = 0.5f, tint = listOf(VStop(0f, RED), VStop(1f, BLUE)), tintStrength = 0.6f, colorMode = ArrayColorMode.RECOLOR)
        val vector = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 60f, 70f)))
        ArrayLookKit.edit(c, vector, vector.array!!.spec.copy(look = look))
        val color = 0xFF3060C0.toInt()
        val text = ArrayLookKit.textArray(c, TextItem("Hi", spec = TextSpec(sizePx = 40f, color = color), cx = 70f, cy = 200f))
        ArrayLookKit.edit(c, text, text.array!!.spec.copy(look = look))
        val s = scene(c)
        for ((layer, own) in listOf(vector to GRAY, text to color)) {
            val items = s.itemsOf(layer)
            assertEquals("one item per copy: ${layer.name}", 3, items.size)
            // Copy 2 (End) at the bottom: toward blue, half; copy 1 (Start): toward red; the source as it is.
            assertEquals(VPaint.Solid(ColorUtils.recolor(own, BLUE, 0.6f)), items[0].fill)
            assertEquals(0.5f, items[0].opacity, 1e-6f)
            assertEquals(VPaint.Solid(ColorUtils.recolor(own, RED, 0.6f)), items[1].fill)
            assertEquals(1f, items[1].opacity, 1e-6f)
            assertEquals(VPaint.Solid(own), items[2].fill)
        }
    }
}
