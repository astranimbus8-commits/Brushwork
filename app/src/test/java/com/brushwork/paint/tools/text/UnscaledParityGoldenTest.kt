package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.engine.BitmapUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * v1.6 §3.5(c), I1 / I8: text whose letters are NOT scaled renders exactly as in v1.5, bit for
 * bit, so the committed pixels of every v1.5 text layer stay equal to a fresh rendering of its
 * stored item (the layer caches stay valid). The goldens are CRC32s of the pixels each kind of
 * text drew with the v1.5 renderer (taken at the v1.6 foundation commit e8a7e2a, before area C
 * changed `TextRenderer`): horizontal, boxed, centred and turned, wrapped around a picture,
 * outlined, vertical (both styles), on a path (bent and rotated), a frame of a linked story.
 *
 * Right-to-left and shaping scripts are drawn unscaled even with letter scaling on (§3.5a: per
 * cluster drawing would break joining), so their scaled items must hash like the unscaled ones.
 *
 * The hashes depend on Robolectric's native graphics and the fonts it ships (machine specific):
 * regenerate them only from the v1.5 renderer, never from the code under test.
 */
@RunWith(RobolectricTestRunner::class)
class UnscaledParityGoldenTest {

    private val w = 500
    private val h = 400

    private fun crc(item: TextItem): Long {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        TextRenderer.drawItem(Canvas(bmp), item, TextRenderer.prepare(item), null)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val buf = ByteBuffer.allocate(px.size * 4)
        buf.asIntBuffer().put(px)
        return CRC32().apply { update(buf.array()) }.value
    }

    private val story = "Linked frames flow a story from one box into the next, just like InDesign. " +
        "When the first frame is full, the words that do not fit continue in the second frame."

    /** The second frame of [story] (laid out like area D's flow). */
    private fun secondFrame(): TextItem {
        val spec = TextSpec(sizePx = 22f, box = TextBoxSpec(width = 240f, minHeight = 80f))
        val first = TextItem(spec = spec, cx = 250f, cy = 120f, thread = TextThreadSpec(storyId = 7, story = story))
        val end0 = TextRenderer.frameEnd(first)
        val second = TextItem(spec = spec, cx = 250f, cy = 260f, thread = TextThreadSpec(storyId = 7, index = 1, story = story, start = end0, end = end0))
        val end1 = TextRenderer.frameEnd(second)
        return second.copy(thread = second.thread.copy(end = end1, overset = end1 < story.length)).sanitized()
    }

    /** Every kind of text, letter scaling off. */
    private fun cases(): Map<String, TextItem> {
        val square = WrapPolygon(listOf(180f, 300f, 300f, 180f), listOf(140f, 140f, 260f, 260f))
        return linkedMapOf(
            "plain" to TextItem("Hello, World! gjpqy", TextSpec(sizePx = 40f), 250f, 200f),
            "boxed" to TextItem(
                "Boxed words that wrap\nin a centred caption",
                TextSpec(
                    sizePx = 28f, align = TextAlign.CENTER, lineSpacing = 1.3f, letterSpacing = 0.05f,
                    box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(width = 300f), 28f),
                ),
                250f, 200f, 12f,
            ),
            "outlined" to TextItem("Outlined", TextSpec(sizePx = 64f, bold = true, strokeWidthPx = 4f, strokeColor = 0xFFFF0000.toInt()), 250f, 200f),
            "wrapped" to TextItem(
                WrapFixtures.LOREM, TextSpec(sizePx = 16f, box = TextBoxSpec(width = 360f)), 250f, 200f,
                wrap = TextWrapSpec(sourceLayerId = 3, polygons = listOf(square), gapPx = 6f, sides = WrapSides.BOTH),
            ),
            "verticalUpright" to TextItem("縦書き ABC\n12!? テキスト", TextSpec(sizePx = 30f, vertical = true), 250f, 200f),
            "verticalMixed" to TextItem("縦書き ABC\n12!? テキスト", TextSpec(sizePx = 30f, vertical = true, verticalStyle = VerticalStyle.MIXED, box = TextBoxSpec(height = 200f)), 250f, 200f),
            "pathBend" to TextItem("Around the circle", TextSpec(sizePx = 30f), path = TextPathSpec(type = TextPathType.CIRCLE, cx = 250f, cy = 200f, radius = 120f)),
            "pathRotate" to TextItem(
                "Rotated letters on a curve", TextSpec(sizePx = 26f, strokeWidthPx = 2f),
                path = TextPathSpec(type = TextPathType.CURVE, mode = TextPathMode.ROTATE, x1 = 40f, y1 = 300f, cx1 = 150f, cy1 = 60f, cx2 = 350f, cy2 = 340f, x2 = 460f, y2 = 100f),
            ),
            "frame" to secondFrame(),
            "arabic" to TextItem("مرحبا بالعالم", TextSpec(sizePx = 40f), 250f, 200f),
            "hebrew" to TextItem("שלום עולם", TextSpec(sizePx = 40f, align = TextAlign.END, box = TextBoxSpec(width = 300f)), 250f, 200f),
            "devanagari" to TextItem("नमस्ते दुनिया", TextSpec(sizePx = 40f), 250f, 200f),
            "thai" to TextItem("สวัสดีชาวโลก", TextSpec(sizePx = 40f), 250f, 200f),
            "mixedRtl" to TextItem("Hello مرحبا world", TextSpec(sizePx = 36f), 250f, 200f),
            "arabicOnPath" to TextItem("مرحبا بالعالم", TextSpec(sizePx = 30f), path = TextPathSpec(type = TextPathType.CIRCLE, cx = 250f, cy = 200f, radius = 120f)),
        )
    }

    /** CRC32s of the v1.5 renderer's pixels (foundation commit e8a7e2a). */
    private val golden: Map<String, Long> = mapOf(
        "plain" to 2439784897L,
        "boxed" to 843261284L,
        "outlined" to 598162754L,
        "wrapped" to 2821301940L,
        "verticalUpright" to 1961319744L,
        "verticalMixed" to 2831066102L,
        "pathBend" to 4247151954L,
        "pathRotate" to 177877585L,
        "frame" to 2994618494L,
        "arabic" to 740402255L,
        "hebrew" to 2039789031L,
        "devanagari" to 3704570336L,
        "thai" to 4114704152L,
        "mixedRtl" to 1074596670L,
        "arabicOnPath" to 3080471478L,
    )

    @Test
    fun unscaledTextDrawsTheV15Pixels() {
        val got = cases().mapValues { (_, item) -> crc(item) }
        if (golden.isEmpty()) {
            println("GOLDENS:\n" + got.entries.joinToString(",\n") { "\"${it.key}\" to ${it.value}L" })
        }
        for ((name, value) in got) assertEquals("\"$name\" is not v1.5's pixels", golden[name], value)
    }

    @Test
    fun rightToLeftAndShapingScriptsDrawUnscaledEvenWithScalingOn() {
        val on = LetterScaleSpec(smallestPercent = 40f)
        for (name in listOf("arabic", "hebrew", "devanagari", "thai", "mixedRtl", "arabicOnPath")) {
            val item = cases().getValue(name)
            val scaled = item.copy(spec = item.spec.copy(letterScale = on))
            assertEquals("\"$name\" with letter scaling on draws unscaled", golden[name], crc(scaled))
        }
    }

    @Test
    fun scaledLatinTextDiffersFromTheGolden() {
        val on = LetterScaleSpec(smallestPercent = 40f)
        for (name in listOf("plain", "boxed", "wrapped", "frame")) {
            val item = cases().getValue(name)
            assertNotEquals("\"$name\" with letter scaling on is scaled", golden[name], crc(item.copy(spec = item.spec.copy(letterScale = on))))
        }
    }
}
