package com.brushwork.paint.tools.text

import com.brushwork.paint.tools.text.LetterScaleFixtures.alpha
import com.brushwork.paint.tools.text.LetterScaleFixtures.render
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.5(a) "LetterScaleRtlFallbackTest": right-to-left text and scripts whose letters are
 * shaped together (Arabic, Hebrew, Devanagari, Thai...) are drawn UNSCALED with letter scaling
 * on — drawing them one cluster at a time would break their joining — in every form (straight,
 * boxed, wrapped, vertical, on a path, a frame), and left-to-right text in the same item does
 * not change that. The editor tells the user (see the letter scaling section).
 */
@RunWith(RobolectricTestRunner::class)
class LetterScaleRtlFallbackTest {

    private val on = LetterScaleSpec(smallestPercent = 30f, align = LetterScaleAlign.TOP)

    private fun same(item: TextItem) {
        val scaled = item.copy(spec = item.spec.copy(letterScale = on))
        assertArrayEquals("\"${item.text}\" draws unscaled", alpha(render(item, 500, 400)), alpha(render(scaled, 500, 400)))
    }

    @Test
    fun shapingScriptsDrawUnscaledEverywhere() {
        val texts = listOf("مرحبا بالعالم", "שלום עולם", "नमस्ते दुनिया", "สวัสดีชาวโลก", "Hello مرحبا", "ABC שלום DEF")
        for (t in texts) {
            same(TextItem(t, TextSpec(sizePx = 36f), 250f, 200f))
            same(TextItem(t, TextSpec(sizePx = 30f, align = TextAlign.CENTER, box = TextBoxSpec(width = 220f)), 250f, 200f))
            same(TextItem(t, TextSpec(sizePx = 30f, vertical = true), 250f, 200f))
            same(TextItem(t, TextSpec(sizePx = 26f), path = TextPathSpec(type = TextPathType.CIRCLE, cx = 250f, cy = 200f, radius = 120f)))
            same(
                TextItem(
                    t, TextSpec(sizePx = 24f, box = TextBoxSpec(width = 300f)), 250f, 200f,
                    wrap = TextWrapSpec(sourceLayerId = 2, polygons = listOf(WrapPolygon(listOf(200f, 260f, 260f, 200f), listOf(150f, 150f, 260f, 260f)))),
                ),
            )
            assertTrue(!TextRenderer.scalesLetters(TextSpec(letterScale = on), t))
        }
    }

    @Test
    fun aStoryWithRightToLeftTextFlowsUnscaled() {
        val story = "Linked frames with some עברית inside them flow unscaled across every frame of the story."
        val spec = TextSpec(sizePx = 22f, box = TextBoxSpec(width = 220f, minHeight = 60f))
        val plain = TextItem(spec = spec, thread = TextThreadSpec(storyId = 8, story = story))
        val scaled = plain.copy(spec = spec.copy(letterScale = on))
        val a = TextRenderer.frameLayout(plain)
        val b = TextRenderer.frameLayout(scaled)
        assertTrue(a.end == b.end && a.lines == b.lines)
        assertFalse(b.ramp != null)
    }

    @Test
    fun latinTextIsScaled() {
        val item = TextItem("Hello there", TextSpec(sizePx = 36f), 250f, 200f)
        assertNotNull(TextRenderer.prepare(item.copy(spec = item.spec.copy(letterScale = on))).block!!.wrapLines)
        assertFalse(alpha(render(item, 500, 400)).contentEquals(alpha(render(item.copy(spec = item.spec.copy(letterScale = on)), 500, 400))))
    }
}
