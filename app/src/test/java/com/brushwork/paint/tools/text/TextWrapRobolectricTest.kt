package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.WrapFixtures.LOREM
import com.brushwork.paint.tools.text.WrapFixtures.closestInk
import com.brushwork.paint.tools.text.WrapFixtures.disc
import com.brushwork.paint.tools.text.WrapFixtures.inked
import com.brushwork.paint.tools.text.WrapFixtures.itemOf
import com.brushwork.paint.tools.text.WrapFixtures.pixels
import com.brushwork.paint.tools.text.WrapFixtures.render
import com.brushwork.paint.tools.text.WrapFixtures.setup
import com.brushwork.paint.tools.text.WrapFixtures.wrappedText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.abs

/**
 * Text wrapped around a picture on real Skia (v1.5 §4.1e, Robolectric): the traced outline, the
 * wrapped pixels, parity with StaticLayout, and the Text tool's wrap settings.
 */
@RunWith(RobolectricTestRunner::class)
class TextWrapRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------ the outline

    @Test
    fun theOutlineOfADrawnDiscHasItsArea() {
        val s = setup(context)
        disc(s.picture, 200f, 150f, 80f)
        val outline = s.c.textWrap.contours.outline(s.picture)!!
        val expected = (PI * 80 * 80).toFloat()
        val area = WrapContourBuilder.area(outline.shape)
        assertEquals("one outline", 1, outline.shape.size)
        assertTrue("area $area vs $expected", abs(area - expected) / expected < 0.03f)
        assertTrue(outline.shape.sumOf { it.size } <= TextWrapSpec.MAX_POINTS)
        val b = outline.bounds!!
        assertTrue("bounds $b", abs(b.left - 120) <= 1 && abs(b.top - 70) <= 1 && abs(b.right - 280) <= 1 && abs(b.bottom - 230) <= 1)
        // The box contour is the content bounds.
        val box = outline.box.single()
        assertEquals(b.left.toFloat(), box.xs.min(), 0f)
        assertEquals(b.bottom.toFloat(), box.ys.max(), 0f)
        // Traced once: the second ask is served from the cache.
        val traces = s.c.textWrap.contours.traces
        s.c.textWrap.contours.outline(s.picture)
        assertEquals(traces, s.c.textWrap.contours.traces)
    }

    @Test
    fun holesAreDroppedAndFaintPixelsIgnored() {
        val s = setup(context)
        // A ring: the hole in the middle is not an outline of its own.
        Canvas(s.picture.bitmap).drawCircle(200f, 150f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 30f; color = WrapFixtures.BLACK })
        // A faint haze (alpha 20) far away blocks nothing.
        Canvas(s.picture.bitmap).drawRect(10f, 10f, 60f, 60f, Paint().apply { color = 0x14000000 })
        s.picture.markChanged()
        val outline = s.c.textWrap.contours.outline(s.picture)!!
        assertEquals(1, outline.shape.size)
        val area = WrapContourBuilder.area(outline.shape)
        val outer = (PI * 85 * 85).toFloat()
        assertTrue("the outer disc's area, not the ring's: $area", abs(area - outer) / outer < 0.03f)
    }

    @Test
    fun aMaskHidesPartOfThePicture() {
        val s = setup(context)
        s.picture.bitmap.eraseColor(0xFF00AA00.toInt())
        // Only the left half of the mask is visible.
        val mask = BitmapUtils.createLayerBitmap(400, 300).also { it.eraseColor(WrapFixtures.BLACK) }
        Canvas(mask).drawRect(0f, 0f, 200f, 300f, Paint().apply { color = WrapFixtures.WHITE })
        s.picture.mask = mask
        s.picture.markChanged()
        val b = s.c.textWrap.contours.outline(s.picture)!!.bounds!!
        assertEquals(Rect(0, 0, 200, 300), b)
        // A disabled mask doesn't hide anything.
        s.picture.maskEnabled = false
        assertEquals(Rect(0, 0, 400, 300), s.c.textWrap.contours.outline(s.picture)!!.bounds)
    }

    @Test
    fun aBigCanvasIsTracedOnAGridOfAtMost1024Cells() {
        val g = WrapContourBuilder.factorFor(4000, 5000)
        assertEquals(5, g)
        assertEquals(1, WrapContourBuilder.factorFor(1024, 800))
        // A 1-px line survives the downsampling (max filter).
        val s = setup(context, 2100, 300)
        Canvas(s.picture.bitmap).drawRect(1000f, 0f, 1001f, 300f, Paint().apply { color = WrapFixtures.BLACK })
        s.picture.markChanged()
        val o = s.c.textWrap.contours.outline(s.picture)!!
        assertNotNull(o.bounds)
        assertTrue(o.bounds!!.left in 996..1000 && o.bounds!!.right in 1001..1005)
        assertTrue(o.shape.isNotEmpty())
    }

    // ------------------------------------------------------------------ wrapped pixels

    @Test
    fun wrappedTextKeepsItsDistanceFromThePicture() {
        val s = setup(context)
        disc(s.picture, 120f, 150f, 55f)
        val gap = 8f
        val text = wrappedText(s, gap = gap)
        val item = itemOf(text)
        assertTrue(item.wrapActive)
        assertEquals(s.picture.id, item.wrap.sourceLayerId)
        // No glyph pixel inside the picture grown by the distance (less 2 px for anti-aliasing).
        val closest = closestInk(text.bitmap, 120f, 150f)
        assertTrue("closest ink $closest", closest >= 55f + gap - 2f)
        // Lines run beside the picture (right of it), not only above and below.
        assertTrue("text beside the picture", inked(text.bitmap).any { (x, y) -> y in 120..180 && x > 120 + 55 + gap })
        // The same text without wrap runs over the picture.
        val plain = render(item.copy(wrap = TextWrapSpec()), 400, 300)
        assertTrue(closestInk(plain, 120f, 150f) < 30f)
        // The layer's pixels are exactly the rendering of its stored item.
        assertArrayEquals(pixels(render(item, 400, 300)), pixels(text.bitmap))
    }

    @Test
    fun bothSidesAndRotatedText() {
        val s = setup(context)
        disc(s.picture, 200f, 150f, 40f)
        val both = wrappedText(s, sides = WrapSides.BOTH, gap = 6f)
        val ink = inked(both.bitmap)
        assertTrue("left of the picture", ink.any { (x, y) -> y in 130..170 && x < 150 })
        assertTrue("right of the picture", ink.any { (x, y) -> y in 130..170 && x > 250 })
        assertTrue(closestInk(both.bitmap, 200f, 150f) >= 40f + 6f - 2f)

        // A text turned 30°: lines flow around the picture in the text's own frame.
        val t2 = setup(context)
        disc(t2.picture, 200f, 150f, 40f)
        val turned = wrappedText(t2, rotation = 30f, gap = 6f)
        assertEquals(30f, itemOf(turned).rotationDeg, 1e-3f)
        assertTrue(closestInk(turned.bitmap, 200f, 150f) >= 40f + 6f - 2f)
    }

    @Test
    fun withThePictureElsewhereLinesMatchStaticLayout() {
        val far = WrapPolygon(listOf(-900f, -880f, -880f, -900f), listOf(-900f, -900f, -880f, -880f))
        val texts = listOf(LOREM, "First paragraph here.\nSecond one, a little longer than the first.\n\nAfter an empty line\n", "Short")
        for (spacing in listOf(1f, 1.2f, 0.8f, 1.7f)) for (align in TextAlign.entries) for (size in listOf(19f, 33f)) for (t in texts) {
            val spec = TextSpec(sizePx = size, lineSpacing = spacing, align = align, box = TextBoxSpec(width = 300f))
            val item = TextItem(t, spec, 200f, 150f, wrap = TextWrapSpec(sourceLayerId = 5, polygons = listOf(far)))
            val wrapped = TextRenderer.prepare(item).block!!
            val lines = wrapped.wrapLines!!
            val plain = TextRenderer.layout(t, spec).staticLayout!!
            val where = "spacing $spacing $align $size \"${t.take(12)}\""
            assertEquals("line count, $where", plain.lineCount, lines.size)
            for (i in lines.indices) {
                assertEquals("line $i start, $where", plain.getLineStart(i), lines[i].start)
                assertEquals("line $i baseline, $where", plain.getLineBaseline(i).toFloat(), lines[i].baseline, 0.5f)
            }
            assertEquals("height, $where", plain.height.toFloat(), wrapped.contentHeight, 0.5f)
            assertEquals(plain.width.toFloat(), wrapped.contentWidth, 0f)
        }
    }

    @Test
    fun aDragReflowsWithoutMeasuringAgain() {
        val s = setup(context)
        disc(s.picture, 120f, 150f, 50f)
        s.tool.startTextAt(200f, 150f)
        s.tool.setText(LOREM)
        s.tool.updateSpec { it.copy(sizePx = 16f, box = it.box.copy(width = 360f)) }
        s.tool.confirmEditor()
        s.tool.setWrapSource(s.picture)
        val first = s.tool.preparedFor(s.tool.item!!)
        s.tool.nudge(0f, 40f)
        val moved = s.tool.preparedFor(s.tool.item!!)
        assertTrue("lines re-break after a move", first !== moved)
        assertSame("the measured characters are reused", first.wrapText, moved.wrapText)
        assertTrue(first.block!!.wrapLines != moved.block!!.wrapLines)
        // Unchanged: the same prepared text.
        assertSame(moved, s.tool.preparedFor(s.tool.item!!))
    }

    // ------------------------------------------------------------------ the tool's wrap settings

    @Test
    fun turningWrapOnPicksThePictureAndFixesTheBox() {
        val s = setup(context)
        disc(s.picture, 150f, 150f, 40f)
        // An auto-width text (no fixed box) over the picture.
        s.tool.startTextAt(200f, 150f)
        s.tool.setText("Wrap me around")
        s.tool.updateSpec { it.copy(sizePx = 20f) }
        s.tool.confirmEditor()
        val before = s.tool.item!!
        val naturalLeft = before.cx - TextRenderer.layout(before.text, before.spec).width / 2f
        assertEquals("the background (covering the whole box) is never the default", s.picture, s.tool.defaultWrapSource())
        s.tool.openWrapSheet()
        assertTrue(s.tool.wrapSheetOpen)
        val on = s.tool.item!!
        assertEquals(s.picture.id, on.wrap.sourceLayerId)
        assertTrue(on.wrap.polygons.isNotEmpty())
        assertEquals("distance 0.3 em the first time", 6f, on.wrap.gapPx, 1e-3f)
        assertEquals("box width max(width, 8 em)", 160f, on.spec.box.width, 0.5f)
        val block = TextRenderer.layout(on.text, on.spec.copy(box = on.spec.box))
        assertEquals("the left edge stays", naturalLeft, on.cx - block.width / 2f, 1f)
        // Off keeps the settings for later; on again keeps the user's distance.
        s.tool.setWrapGap(25f)
        s.tool.setWrapSource(null)
        assertFalse(s.tool.item!!.wrap.isOn)
        assertTrue(s.tool.item!!.wrap.polygons.isEmpty())
        s.tool.setWrapSource(s.picture)
        assertEquals(25f, s.tool.item!!.wrap.gapPx, 0f)
        // Box contour: the content bounds.
        s.tool.setWrapContour(WrapContour.BOX)
        assertEquals(4, s.tool.item!!.wrap.polygons.single().size)
        // Distance is clamped.
        s.tool.setWrapGap(5000f)
        assertEquals(TextWrapSpec.MAX_GAP_PX, s.tool.item!!.wrap.gapPx, 0f)
        // Nothing of this is history: it is all part of the pending text.
        assertEquals(0, s.c.undoManager.undoCount)
    }

    @Test
    fun theListedPicturesAndTheDefault() {
        val s = setup(context)
        val adjustment = s.c.addAdjustmentLayer(com.brushwork.paint.masks.AdjustmentSpec(), null)
        assertNotNull(adjustment)
        val text = wrappedText(s, text = "Some text")
        s.c.selectLayer(s.picture)
        s.tool.startTextAt(100f, 100f)
        s.tool.setText("Another")
        s.tool.confirmEditor()
        val sources = s.tool.wrapSources()
        assertEquals("top first; no text or adjustment layer", listOf(s.picture, s.background), sources)
        assertFalse(text in sources)
        // The picture layer is empty: no default.
        assertNull(s.tool.defaultWrapSource())
        s.tool.openWrapSheet()
        assertTrue(s.tool.wrapSheetOpen)
        assertFalse("nothing to wrap around: off", s.tool.item!!.wrap.isOn)
    }

    @Test
    fun verticalTextAndTextOnAPathDontWrap() {
        val s = setup(context)
        disc(s.picture, 150f, 150f, 40f)
        s.tool.startTextAt(200f, 150f)
        s.tool.setText("縦書き")
        s.tool.toggleVertical()
        s.tool.confirmEditor()
        assertFalse(s.tool.canWrap)
        s.tool.openWrapSheet()
        assertFalse(s.tool.wrapSheetOpen)
        assertEquals(TextTool.WRAP_HORIZONTAL_ONLY, s.c.message)
        // Wrap set, then the text turned vertical: kept, but not applied.
        s.tool.toggleVertical()
        s.tool.setWrapSource(s.picture)
        assertTrue(s.tool.item!!.wrapActive)
        s.tool.toggleVertical()
        assertTrue(s.tool.item!!.wrap.isOn)
        assertFalse(s.tool.item!!.wrapActive)
        assertNull(TextRenderer.prepare(s.tool.item!!).block!!.wrapLines)
    }

    @Test
    fun theOutlineIsShownWhilePending() {
        val s = setup(context)
        disc(s.picture, 150f, 150f, 40f)
        s.tool.startTextAt(200f, 150f)
        s.tool.setText("Shown")
        s.tool.confirmEditor()
        s.tool.setWrapSource(s.picture)
        fun overlay(): IntArray {
            val b = BitmapUtils.createLayerBitmap(400, 300)
            s.tool.drawOverlay(Canvas(b), s.c.viewTransform)
            return pixels(b)
        }
        // Magenta on the outline (left edge of the disc, x = 110).
        fun magentaNearOutline(px: IntArray) = (140 until 160).any { y ->
            (106..114).any { x -> val p = px[y * 400 + x]; (p ushr 24) > 0 && (p shr 16 and 0xFF) > 200 && (p and 0xFF) > 150 && (p shr 8 and 0xFF) < 120 }
        }
        assertTrue(magentaNearOutline(overlay()))
        s.tool.showWrapOutline = false
        assertFalse(magentaNearOutline(overlay()))
    }
}
