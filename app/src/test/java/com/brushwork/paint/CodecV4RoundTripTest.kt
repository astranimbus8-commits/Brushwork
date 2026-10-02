package com.brushwork.paint

import com.brushwork.paint.tools.text.LetterScaleAlign
import com.brushwork.paint.tools.text.LetterScaleCurve
import com.brushwork.paint.tools.text.LetterScaleDirection
import com.brushwork.paint.tools.text.LetterScaleScope
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.TextWrapSpec
import com.brushwork.paint.tools.text.VerticalStyle
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6 foundation (§4.2, §4.9, I4): text format 4 (letter scaling, linked frames) and the Path
 * tool's spline in `.vec` files. Version 3 text reads with the new fields off; version 4 is
 * written; a spline survives a `.vec` round trip; v1.5-shaped readers (frozen copies of the v1.5
 * classes below) read v1.6 data, ignoring what they don't know (JVM).
 */
class CodecV4RoundTripTest {

    @Test
    fun version3TextReadsWithTheNewFieldsOff() {
        val v3 = """{"version":3,"item":{"text":"Old text","spec":{"sizePx":30,"box":{"width":120,"minHeight":40}},"cx":5,"cy":6,
            "wrap":{"sourceLayerId":0}}}"""
        val item = TextCodec.decode(v3)!!
        assertEquals("Old text", item.text)
        assertEquals(LetterScaleSpec(), item.spec.letterScale)
        assertFalse(item.spec.letterScale.isOn)
        assertEquals(TextThreadSpec(), item.thread)
        assertFalse(item.threaded)
        val json = TextCodec.encode(item)
        assertTrue(json, json.contains("\"version\":4"))
        assertTrue(json.contains("\"letterScale\""))
        assertTrue(json.contains("\"thread\""))
        assertEquals(item, TextCodec.decode(json))
    }

    @Test
    fun scaledAndThreadedTextRoundTrips() {
        val story = "ELTON JOHN\nSecond paragraph of the story."
        val item = TextItem(
            text = "ignored: the slice wins",
            spec = TextSpec(
                sizePx = 42f,
                box = TextBoxSpec(width = 300f, minHeight = 120f),
                letterScale = LetterScaleSpec(62f, LetterScaleDirection.END_TO_START, LetterScaleAlign.TOP, LetterScaleCurve.RATIO, LetterScaleScope.EACH_PARAGRAPH),
            ),
            cx = 200f, cy = 150f,
            thread = TextThreadSpec(storyId = 0x1234_5678_9ABCL, index = 1, story = story, start = 11, end = 17, overset = true, rev = 9),
        )
        val back = TextCodec.decode(TextCodec.encode(item))!!
        assertEquals("I9: the text is the story slice", story.substring(11, 17), back.text)
        assertEquals(item.sanitized(), back)
        assertEquals(62f, back.spec.letterScale.smallestPercent, 0f)
        assertTrue(back.threaded)
        assertEquals(9L, back.thread.rev)
    }

    @Test
    fun damagedThreadAndScaleDataIsRepaired() {
        val story = "abcdefghij"
        val base = TextItem(spec = TextSpec(box = TextBoxSpec(width = 100f, minHeight = 50f)))
        // Indices out of range are clamped; a vertical or on-path frame is made straight.
        val bad = base.copy(
            spec = base.spec.copy(vertical = true, letterScale = LetterScaleSpec(smallestPercent = Float.NaN)),
            path = TextPathSpec(type = TextPathType.CIRCLE),
            thread = TextThreadSpec(storyId = 5, index = -3, story = story, start = 7, end = 99, rev = -1),
        ).sanitized()
        assertEquals("hij", bad.text)
        assertEquals(7, bad.thread.start)
        assertEquals(10, bad.thread.end)
        assertEquals(0, bad.thread.index)
        assertEquals(0L, bad.thread.rev)
        assertFalse(bad.spec.vertical)
        assertFalse(bad.path.isActive)
        assertEquals(100f, bad.spec.letterScale.smallestPercent, 0f)
        // No fixed box: no frame (the text is kept).
        val loose = TextItem("keep me", thread = TextThreadSpec(storyId = 5, story = story, start = 0, end = 3)).sanitized()
        assertFalse(loose.threaded)
        assertEquals("keep me", loose.text)
        // Not threaded: no stray story copy.
        val off = TextItem("x", thread = TextThreadSpec(storyId = 0, story = "junk", end = 4)).sanitized()
        assertEquals(TextThreadSpec(), off.thread)
        assertEquals(TextThreadSpec(), base.copy(thread = TextThreadSpec(storyId = -9, story = story)).sanitized().thread)
        // A story beyond the cap is cut (never inside a surrogate pair).
        val huge = "a".repeat(TextThreadSpec.MAX_STORY - 1) + "😀" + "tail"
        val cut = base.copy(thread = TextThreadSpec(storyId = 1, story = huge, start = 0, end = huge.length)).sanitized()
        assertEquals(TextThreadSpec.MAX_STORY - 1, cut.thread.story.length)
        assertEquals(cut.thread.story, cut.text)
        // Letter scaling: out of range percentages are held to 5..100.
        assertEquals(5f, LetterScaleSpec(smallestPercent = -40f).sanitized().smallestPercent, 0f)
        assertEquals(100f, LetterScaleSpec(smallestPercent = 400f).sanitized().smallestPercent, 0f)
    }

    private fun splinePath() = VPath(
        id = 3, opacity = 0.8f,
        subpaths = listOf(VSubpath(listOf(VAnchor(10f, 20f, outX = 5f, outY = 0f), VAnchor(60f, 25f, inX = -5f, inY = 0f)), closed = false)),
        fillRule = VFillRule.EVENODD,
        fill = VPaint.Solid(0xFF112233.toInt()),
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
        spline = VSpline(listOf(VSplinePoint(10f, 20f), VSplinePoint(35.5f, 60.25f, weight = 2.5f, width = 1.5f), VSplinePoint(60f, 25f)), order = 3, endpoint = true, cyclic = false),
    )

    @Test
    fun splineSurvivesAVecRoundTrip() {
        val content = VectorContent.EMPTY.plus(listOf(splinePath(), splinePath().copy(spline = null))).first
        val back = VectorCodec.decode(VectorCodec.encode(content))
        assertEquals(content, back)
        assertEquals(splinePath().spline, (back.objects[0] as VPath).spline)
        assertNull((back.objects[1] as VPath).spline)
        assertEquals("the codec stays at version 1", 1, VectorCodec.VERSION)
    }

    @Test
    fun damagedSplinesAreSanitizedOnRead() {
        val bad = splinePath().copy(spline = VSpline(listOf(VSplinePoint(0f, 0f, weight = 99f), VSplinePoint(Float.NaN, 1f), VSplinePoint(5f, 5f, width = -2f)), order = 40))
        val back = VectorCodec.decode(VectorCodec.encode(VectorContent.EMPTY.plus(listOf(bad)).first))
        val s = (back.objects[0] as VPath).spline!!
        assertEquals(VSpline.MAX_ORDER, s.order)
        assertEquals(2, s.points.size)
        assertEquals(VSpline.MAX_WEIGHT, s.points[0].weight, 0f)
        assertEquals(0f, s.points[1].width, 0f)
    }

    // ------------------------------------------------------------------ frozen v1.5 shapes (test-only copies)

    @Serializable
    @SerialName("path")
    private data class VPathV15(
        val id: Long,
        val opacity: Float = 1f,
        val subpaths: List<VSubpath>,
        val tension: Float = 0f,
        val polyline: Boolean = false,
        val fillRule: VFillRule = VFillRule.NONZERO,
        val fill: VPaint? = null,
        val stroke: VStrokeStyle? = null,
    )

    @Serializable
    private data class TextSpecV15(
        val font: TextFont = TextFont.SANS,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val sizePx: Float = 48f,
        val color: Int = 0xFF000000.toInt(),
        val align: TextAlign = TextAlign.START,
        val letterSpacing: Float = 0f,
        val lineSpacing: Float = 1.2f,
        val vertical: Boolean = false,
        val strokeWidthPx: Float = 0f,
        val strokeColor: Int = 0xFFFFFFFF.toInt(),
        val antiAlias: Boolean = true,
        val verticalStyle: VerticalStyle = VerticalStyle.UPRIGHT,
        val columnsLeftToRight: Boolean = false,
        val box: TextBoxSpec = TextBoxSpec(),
        val fontId: String? = null,
        val fontName: String? = null,
    )

    @Serializable
    private data class TextItemV15(
        val text: String = "",
        val spec: TextSpecV15 = TextSpecV15(),
        val cx: Float = 0f,
        val cy: Float = 0f,
        val rotationDeg: Float = 0f,
        val path: TextPathSpec = TextPathSpec(),
        val wrap: TextWrapSpec = TextWrapSpec(),
    )

    @Serializable
    private data class TextLayerDataV15(val version: Int = 3, val item: TextItemV15 = TextItemV15())

    /** The readers' settings of v1.5 (TextCodec / VectorCodec). */
    private val v15Json = Json { ignoreUnknownKeys = true; coerceInputValues = true; allowSpecialFloatingPointValues = true }

    @Test
    fun v15ShapedReadersReadV16Data() {
        val p = splinePath()
        val pathJson = Json { encodeDefaults = true }.encodeToString(VObject.serializer(), p)
        assertTrue(pathJson.contains("\"spline\""))
        val old = v15Json.decodeFromString(VPathV15.serializer(), pathJson)
        assertEquals("v1.5 reads the Bézier form", p.subpaths, old.subpaths)
        assertEquals(p.fill, old.fill)
        assertEquals(p.stroke, old.stroke)

        val item = TextItem(
            "frame text", TextSpec(sizePx = 30f, box = TextBoxSpec(width = 200f, minHeight = 90f), letterScale = LetterScaleSpec(60f)), 50f, 60f,
            thread = TextThreadSpec(storyId = 77, story = "frame text and more", start = 0, end = 10),
        )
        val textJson = TextCodec.encode(item)
        val oldText = v15Json.decodeFromString(TextLayerDataV15.serializer(), textJson)
        assertEquals(4, oldText.version)
        assertEquals("v1.5 sees an ordinary text: the frame's slice", "frame text", oldText.item.text)
        assertEquals(30f, oldText.item.spec.sizePx, 0f)
        assertEquals(200f, oldText.item.spec.box.width, 0f)
    }
}
