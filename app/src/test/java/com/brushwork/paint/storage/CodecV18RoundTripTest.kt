package com.brushwork.paint.storage

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.FringePreset
import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.FilterValuesCodec
import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 F1 (§4.3): v1.8 → v1.8. Each new piece of data (copies on a path and on a shape, a curve
 * generator, a cog with and without a hole on a shape layer AND on a vector layer, an array look,
 * fringe colors) encodes, decodes and equals the original. JVM.
 */
class CodecV18RoundTripTest {
    private val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val quarter = floatArrayOf(0f, -1f, 150f, 1f, 0f, -50f, 0f, 0f, 1f)
    private val half = floatArrayOf(-1f, 0f, 200f, 0f, -1f, 100f, 0f, 0f, 1f)

    private val path = VPath(
        1,
        subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(50f, 10f, inX = -5f, inY = 0f, outX = 5f, outY = 1f), VAnchor(20f, 40f)))),
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
    )

    private val cog = ShapeObject(
        type = ShapeType.COG, cx = 100f, cy = 80f, w = 120f, h = 110f, rotation = -20f, style = ShapeStyle.STROKE_FILL,
        cogTeeth = 18, cogDepth = 0.35f, cogToothWidth = 0.4f, cogTaper = 0.2f,
    )
    private val holed = cog.copy(cogHole = 0.5f)

    @Test
    fun vectorLayerDataRoundTrips() {
        val objects = listOf(
            path.copy(id = 1, copies = CopyMaps.of(listOf(mirror, quarter, half))),
            path.copy(id = 2, generator = CurveGenerator(kind = CurveGenKind.ROSE, cx = 60f, cy = 70f, size = 50f, petalsN = 5, petalsD = 3, density = 16)),
            path.copy(id = 3, copies = CopyMaps.of(listOf(mirror)), generator = CurveGenerator(kind = CurveGenKind.SINE, amplitude = 25f, waves = 4f, angleDeg = 45f)),
            VShape(4, 0.9f, cog, seed = 3L),
            VShape(5, shape = holed),
            VShape(6, shape = holed.copy(copies = CopyMaps.of(listOf(half)))),
            VShape(7, shape = ShapeObject(type = ShapeType.STAR, w = 40f, h = 40f, copies = CopyMaps.of(listOf(quarter)))),
        )
        val content = VectorContent(objects = objects, nextId = 8)
        val back = VectorCodec.decode(VectorCodec.encode(content))
        assertEquals(content, back)
        for (o in back.objects.filterIsInstance<VShape>()) {
            if (o.shape.type != ShapeType.COG) continue
            assertNull("a cog reads without its fallback points", o.shape.points)
        }
        // Again: stable.
        assertArrayEquals(VectorCodec.encode(content), VectorCodec.encode(back))
    }

    @Test
    fun shapeLayerDataRoundTrips() {
        for (s in listOf(cog, holed, holed.copy(copies = CopyMaps.of(listOf(mirror, half))), ShapeObject(type = ShapeType.ELLIPSE, w = 30f, h = 20f, copies = CopyMaps.of(listOf(quarter))))) {
            val text = ShapeCodec.encode(s)
            assertEquals(s, ShapeCodec.decode(text))
            assertEquals("stable", text, ShapeCodec.encode(ShapeCodec.decode(text)!!))
        }
    }

    @Test
    fun anArrayLookRoundTrips() {
        val look = ArrayLook(
            opacityStart = 0.9f, opacityEnd = 0.2f, sizeStart = 1.5f, sizeEnd = 0.5f, turnStart = 10f, turnEnd = 90f,
            easing = ArrayEasing.EASE_IN_OUT, tint = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(0.5f, 0xFF00FF00.toInt()), VStop(1f, 0xFF0000FF.toInt())), tintStrength = 0.6f,
        )
        for (spec in listOf(
            ArraySpec(count = 5, look = look),
            ArraySpec(mode = ArrayMode.CIRCLE, count = 8, look = look.copy(easing = ArrayEasing.LINEAR, tint = emptyList(), tintStrength = 0f)),
            ArraySpec(mode = ArrayMode.TRANSFORM, count = 12, look = ArrayLook(opacityEnd = 0f)),
        )) {
            val text = ArrayCodec.encodeSpec(spec)
            assertEquals(spec, ArrayCodec.decodeSpec(text))
            assertEquals(text, ArrayCodec.encodeSpec(ArrayCodec.decodeSpec(text)!!))
        }
    }

    private class FringeFilter : Filter("test.roundtrip.fringes", "Fringe round trip", FilterCategory.ART) {
        override val params: List<FilterParam> = listOf(
            FilterParam.Fringes(
                "fringes", "Fringe colors",
                listOf(FringePreset("RGB", intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))),
                intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()), legacyOrderKey = "order",
            ),
        )

        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer = src
    }

    @Test
    fun fringeColorsRoundTrip() {
        val f = FringeFilter()
        for (colors in listOf(intArrayOf(0xFFFF0000.toInt(), 0, 0xFF00FFFF.toInt()), intArrayOf(0x80123456.toInt(), -1, 0x7F000000))) {
            val v = f.defaultValues().set("fringes", colors)
            val back = FilterValuesCodec.fromJson(f, FilterValuesCodec.toJson(f, v))
            assertArrayEquals(colors, back.colors("fringes"))
            val stored = AdjustmentCodec.encode(AdjustmentEffects.spec(f, v))
            val decoded = AdjustmentCodec.decode(stored)!!
            assertArrayEquals(colors, AdjustmentEffects.valuesOf(decoded, f).colors("fringes"))
            assertEquals("stable", stored, AdjustmentCodec.encode(decoded))
            assertTrue(stored, !stored.contains("order"))
        }
    }
}
