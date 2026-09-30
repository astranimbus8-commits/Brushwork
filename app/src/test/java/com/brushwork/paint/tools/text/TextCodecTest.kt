package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON of text layers ([TextCodec], pure JVM). */
class TextCodecTest {

    private val full = TextItem(
        text = "Hello\nworld 漢字",
        spec = TextSpec(
            font = TextFont.SERIF, bold = true, italic = true, sizePx = 37.5f, color = 0x80FF2040.toInt(),
            align = TextAlign.END, letterSpacing = 0.25f, lineSpacing = 1.7f, vertical = true,
            strokeWidthPx = 3.5f, strokeColor = 0xFF00FF00.toInt(), antiAlias = false,
            verticalStyle = VerticalStyle.MIXED, columnsLeftToRight = true,
            box = TextBoxSpec(width = 120f, height = 300f, padding = 8f, fill = true, fillColor = 0x7F102030, borderWidth = 2f, borderColor = 0xFF0000FF.toInt(), roundness = 0.6f),
        ),
        cx = 123.25f, cy = -40f, rotationDeg = -33f,
        path = TextPathSpec(
            type = TextPathType.CURVE, mode = TextPathMode.ROTATE, x1 = 1f, y1 = 2f, x2 = 3f, y2 = 4f,
            cx1 = 5f, cy1 = 6f, cx2 = 7f, cy2 = 8f, cx = 9f, cy = 10f, radius = 11f, startAngleDeg = 12f,
            width = 13f, height = 14f, cornerRadius = 15f, rotationDeg = 16f, keepSquare = true, clockwise = false,
            side = TextPathSide.INSIDE, align = TextPathAlign.END, offset = 17f, baselineShift = -18f,
        ),
    )

    @Test
    fun roundTripKeepsEveryField() {
        val json = TextCodec.encode(full)
        assertTrue("versioned: $json", json.contains("\"version\":${TextCodec.VERSION}"))
        assertEquals(full, TextCodec.decode(json))
        // Defaults are written too (a later version can rely on every field being present).
        val plain = TextCodec.encode(TextItem("x"))
        assertTrue(plain.contains("\"verticalStyle\":\"UPRIGHT\""))
        assertTrue(plain.contains("\"box\""))
        assertTrue(plain.contains("\"path\""))
        assertEquals(TextItem("x"), TextCodec.decode(plain))
    }

    @Test
    fun missingFieldsTakeDefaultsAndUnknownOnesAreIgnored() {
        val item = TextCodec.decode("""{"item":{"text":"Hi","cx":5,"cy":6,"spec":{"sizePx":20,"futureField":[1,2]},"extra":{"a":1}},"version":7,"other":true}""")!!
        assertEquals("Hi", item.text)
        assertEquals(5f, item.cx, 0f)
        assertEquals(6f, item.cy, 0f)
        assertEquals(20f, item.spec.sizePx, 0f)
        assertEquals(TextSpec().copy(sizePx = 20f), item.spec)
        assertEquals(TextBoxSpec(), item.spec.box)
        assertEquals(VerticalStyle.UPRIGHT, item.spec.verticalStyle)
        assertEquals(TextPathSpec(), item.path)
        assertEquals(0f, item.rotationDeg, 0f)
        // No spec at all.
        assertEquals(TextSpec(), TextCodec.decode("""{"item":{"text":"a"}}""")!!.spec)
    }

    @Test
    fun unknownEnumValuesFallBackAndGarbageIsRejected() {
        val item = TextCodec.decode("""{"version":1,"item":{"text":"a","spec":{"font":"COMIC_NEUE","verticalStyle":"SPIRAL","align":"CENTER"},"path":{"type":"HEXAGON","radius":50}}}""")!!
        assertEquals(TextFont.SANS, item.spec.font)
        assertEquals(VerticalStyle.UPRIGHT, item.spec.verticalStyle)
        assertEquals(TextAlign.CENTER, item.spec.align)
        assertEquals(TextPathType.NONE, item.path.type)
        assertEquals(50f, item.path.radius, 0f)
        assertNull(TextCodec.decode(null))
        assertNull(TextCodec.decode(""))
        assertNull(TextCodec.decode("not json"))
        assertNull(TextCodec.decode("""{"item":{"text":5}"""))
    }

    @Test
    fun nonFiniteNumbersAreSanitized() {
        val bad = TextItem(
            "x",
            TextSpec(sizePx = Float.NaN, letterSpacing = Float.POSITIVE_INFINITY, strokeWidthPx = -3f, box = TextBoxSpec(width = Float.NaN, padding = -1f, roundness = 7f)),
            cx = Float.NaN, cy = 1f, rotationDeg = 540f,
        )
        val back = TextCodec.decode(TextCodec.encode(bad))!!
        assertTrue(back.spec.sizePx >= TextSpec.MIN_SIZE_PX && back.spec.sizePx.isFinite())
        assertEquals(0f, back.spec.letterSpacing, 0f)
        assertEquals(0f, back.spec.strokeWidthPx, 0f)
        assertEquals(0f, back.spec.box.width, 0f)
        assertEquals(0f, back.spec.box.padding, 0f)
        assertEquals(1f, back.spec.box.roundness, 0f)
        assertEquals(0f, back.cx, 0f)
        assertEquals(180f, back.rotationDeg, 0f)

        // Absurd sizes (damaged data) are capped; the path's numbers are cleaned up too.
        val huge = TextItem(
            "x", TextSpec(sizePx = 1e30f, strokeWidthPx = 1e30f, box = TextBoxSpec(width = 1e30f, height = Float.MAX_VALUE)),
            path = TextPathSpec(type = TextPathType.CIRCLE, radius = Float.NaN, cx = Float.POSITIVE_INFINITY, x1 = 5f),
        )
        val h = TextCodec.decode(TextCodec.encode(huge))!!
        assertEquals(TextSpec.MAX_SIZE_PX, h.spec.sizePx, 0f)
        assertEquals(TextSpec.MAX_LENGTH_PX, h.spec.strokeWidthPx, 0f)
        assertEquals(TextSpec.MAX_LENGTH_PX, h.spec.box.width, 0f)
        assertEquals(TextSpec.MAX_LENGTH_PX, h.spec.box.height, 0f)
        assertEquals(TextPathSpec().radius, h.path.radius, 0f)
        assertEquals(0f, h.path.cx, 0f)
        assertEquals(5f, h.path.x1, 0f)
        assertEquals(TextPathType.CIRCLE, h.path.type)
    }

    @Test
    fun scalingAndPresets() {
        val spec = TextSpec(sizePx = 40f, strokeWidthPx = 2f, box = TextBoxSpec(width = 100f, height = 50f, padding = 4f, borderWidth = 1f))
        val s = spec.scaled(2f)
        assertEquals(80f, s.sizePx, 0f)
        assertEquals(4f, s.strokeWidthPx, 0f)
        assertEquals(TextBoxSpec(width = 200f, height = 100f, padding = 8f, borderWidth = 2f), s.box)
        // Pinching scales the box with the text.
        val p = TextItem("Hi", spec, 0f, 0f).pinched(Vec2.ZERO, Vec2.ZERO, 1.5f, 0f, 1000f)
        assertEquals(150f, p.spec.box.width, 1e-3f)

        val caption = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(width = 90f), 40f)
        assertEquals(90f, caption.width, 0f) // the wrap width is kept
        assertTrue(caption.fill && caption.borderWidth > 0f && caption.roundness == 0f)
        assertTrue(TextBoxPreset.CAPTION.matches(caption, 40f))
        // Still recognised after resizing the text (rounding of the scaled lengths).
        val resized = TextSpec(sizePx = 40f, box = caption).scaled(1.37f)
        assertTrue(TextBoxPreset.CAPTION.matches(resized.box, resized.sizePx))
        assertTrue(!TextBoxPreset.BUBBLE.matches(resized.box, resized.sizePx))
        val bubble = TextBoxPreset.BUBBLE.applyTo(caption, 40f)
        assertEquals(1f, bubble.roundness, 0f)
        assertEquals(TextBoxSpec(width = 90f), TextBoxPreset.PLAIN.applyTo(bubble, 40f).copy(fillColor = TextBoxSpec().fillColor))
        assertEquals(bubble.padding + bubble.borderWidth, bubble.inset, 0f)
    }
}
