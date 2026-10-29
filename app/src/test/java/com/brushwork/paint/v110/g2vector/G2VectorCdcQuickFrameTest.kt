package com.brushwork.paint.v110.g2vector

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.10 G2 (V7, A4 gap, lead's ruling): Change Drawing Color on some objects of a vector layer
 * (rule 1, the selected objects) shows a quick frame before the exact render: the layer's cached
 * render recolored where the targets paint. Where the targets alone paint it is within 2 per
 * premultiplied channel of the exact render, where only the other objects paint (or nothing) it is
 * the layer as it was, and the exact render replaces it. Both ways of finding the targets' ink are
 * driven: the targets rendered alone (the fewer) and the other objects rendered alone (the fewer).
 * The apply stays the exact re-render, one step, undone and redone. Every object a target, or an
 * array on the layer, keeps the paths it had (no quick frame). Quick frames come once an exact
 * render was slow; these small layers render fast, so the session's threshold is set to 0.
 */
@RunWith(RobolectricTestRunner::class)
class G2VectorCdcQuickFrameTest {
    private val kit = ObjectTestKit(512, 384)

    @After
    fun tearDown() = kit.close()

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    /**
     * Twelve objects in a 4 x 3 grid, brush strokes and boxes in turn, none touching, and a
     * thirteenth stroke across the first two cells (where it meets them a target and another
     * object overlap).
     */
    private fun layer(): EditorController = kit.controller().also { c ->
        val objects = ArrayList<VObject>()
        for (k in 0 until 12) {
            val x = 20f + (k % 4) * 124f
            val y = 20f + (k / 4) * 120f
            objects += if (k % 2 == 0) kit.stroke(x, y, x + 70f, y + 80f, seed = k.toLong(), size = 14f) else kit.box(x, y, x + 70f, y + 80f)
        }
        objects += kit.stroke(40f, 60f, 230f, 80f, seed = 99L, size = 12f)
        c.vectors.addObjects(c.vec, objects, "Add")
        kit.idle()
    }

    private fun start(c: EditorController): FilterSession {
        c.startFilter(cdc)
        val s = c.filterSession!!
        s.debounceMs = 0L
        assertTrue(s.objectMode)
        assertTrue("preview ready", kit.idleUntil { s.hasPreview && !s.isRendering })
        return s
    }

    /** What the canvas shows for the filtered layer. */
    private fun shown(c: EditorController): IntArray {
        val b = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        assertTrue(c.renderOverride!!.drawContent(Canvas(b)))
        return kit.pixels(b).also { b.recycle() }
    }

    /** The largest difference of [e] and [a] per channel, compared premultiplied as the layer stores them. */
    private fun premultipliedDiff(e: Int, a: Int): Int {
        if (e == a) return 0
        fun pm(c: Int, shift: Int) = (((c shr shift) and 0xFF) * (c ushr 24) + 127) / 255
        var worst = abs((e ushr 24) - (a ushr 24))
        for (shift in intArrayOf(16, 8, 0)) worst = max(worst, abs(pm(e, shift) - pm(a, shift)))
        return worst
    }

    private fun only(content: VectorContent, keep: (Long) -> Boolean) = kit.render(content.copy(objects = content.objects.filter { keep(it.id) }))

    /**
     * The quick frame [q] of the targets [sel]: within 2 of [expected] where the targets alone
     * paint, [original] (the layer as it was) where no target paints (where a target and another
     * object overlap it is an estimate).
     */
    private fun assertQuick(what: String, content: VectorContent, sel: Set<Long>, original: IntArray, expected: IntArray, q: IntArray) {
        val targetInk = only(content) { it in sel }
        val otherInk = only(content) { it !in sel }
        var targetPixels = 0
        var recolored = 0
        var overlap = 0
        for (i in q.indices) {
            val t = targetInk[i] ushr 24
            val o = otherInk[i] ushr 24
            when {
                t != 0 && o != 0 -> overlap++
                t == 0 -> if (q[i] != original[i]) fail("$what: changed where no target paints, pixel (${i % kit.w}, ${i / kit.w}): %08X for %08X".format(q[i], original[i]))
                else -> {
                    targetPixels++
                    if (premultipliedDiff(expected[i], q[i]) > 2) fail("$what: quick pixel (${i % kit.w}, ${i / kit.w}): %08X, exact %08X".format(q[i], expected[i]))
                    if (q[i] != original[i]) recolored++
                }
            }
        }
        assertTrue("$what: target ink ($targetPixels)", targetPixels > 2000)
        assertTrue("$what: recolored ($recolored of $targetPixels)", recolored > targetPixels / 2)
        assertTrue("$what: the fixture overlaps ($overlap)", overlap > 0)
    }

    /** Selects [targets] of the layer, then previews two colors and applies the second. */
    private fun quickThenExact(what: String, targets: (List<Long>) -> Set<Long>) {
        val c = layer()
        val content = c.vec.vector!!
        val sel = targets(content.objects.map { it.id })
        c.vectors.setSelection(c.vec, sel)
        kit.idle()
        val original = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        val s = start(c)
        assertFalse("$what: no pixel preview", s.holdsPixelCopy)
        assertFalse("$what: the first frame is exact alone", s.holdsQuickSource)
        // This small layer renders fast: quick frames as if its exact render were slow.
        s.quickAfterMs = 0L
        var frames = 0
        var quick: IntArray? = null
        s.onQuickFrame = { frames++; quick = shown(c) }

        s.update("color", 0xFF20A060.toInt())
        assertTrue(kit.idleUntil { !s.isRendering && !s.previewStale })
        assertEquals("$what: one quick frame, before the exact one", 1, frames)
        assertTrue("$what: a quick source", s.holdsQuickSource)
        val mapper = cdc.colorMapper(s.values)!!
        val expected = kit.render(ObjectColorSlots.map(content, sel, mapper))
        val exact = shown(c)
        for (i in expected.indices) if (premultipliedDiff(expected[i], exact[i]) > 2) fail("$what: the exact render replaced it, pixel (${i % kit.w}, ${i / kit.w})")

        assertQuick(what, content, sel, original, expected, quick!!)

        // A second color: its quick frame reuses the targets' ink.
        s.update("color", 0xFFC03080.toInt())
        assertTrue(kit.idleUntil { !s.isRendering && !s.previewStale })
        assertEquals(2, frames)
        assertTrue(s.holdsQuickSource)

        // The apply: the exact re-render as one step (I1), undone and redone.
        val f2 = cdc.colorMapper(s.values)!!
        val after = ObjectColorSlots.map(content, sel, f2)
        s.apply()
        assertTrue(kit.idleUntil { s.isClosed })
        assertEquals("$what: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(after, c.vec.vector)
        assertArrayEquals("$what: I1", kit.render(after), kit.pixels(c.vec.bitmap))
        c.undo()
        kit.idle()
        assertSame(content, c.vec.vector)
        assertArrayEquals(original, kit.pixels(c.vec.bitmap))
        c.redo()
        kit.idle()
        assertEquals(after, c.vec.vector)
        assertArrayEquals(kit.render(after), kit.pixels(c.vec.bitmap))
    }

    @Test
    fun aQuickFrameRecolorsTheCachedRenderWhereTheTargetsPaint() {
        // Four of thirteen selected (the targets are rendered alone), then ten of thirteen (the
        // three others are).
        quickThenExact("4 of 13") { ids -> ids.take(4).toHashSet() }
        quickThenExact("10 of 13") { ids -> ids.drop(3).toHashSet() }
    }

    /**
     * V7 review: once renders are slow every value change stops the computing preview (a drag
     * sends one per frame, `FilterSession.requestPreview`). The first quick frame's weights belong
     * to the quick source, not to that frame: stopped mid-render they finish, and the next frame
     * awaits and reuses them (100 of 200 strokes take about 170 ms on the PC, about a second on the
     * T606, so a drag that stopped them on every change never showed a quick frame).
     */
    @Test
    fun aValueChangeThatStopsTheFirstQuickFrameLetsItsWeightsFinish() {
        val c = layer()
        val content = c.vec.vector!!
        val sel = content.objects.map { it.id }.take(4).toHashSet()
        c.vectors.setSelection(c.vec, sel)
        kit.idle()
        val original = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        val s = start(c)
        s.quickAfterMs = 0L
        // Every value change stops a computing preview, as once a render took SLOW_RENDER_MS.
        s.slowRenderMs = 0L
        val renders = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        s.onQuickWeights = {
            renders.incrementAndGet()
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        var frames = 0
        var quick: IntArray? = null
        s.onQuickFrame = { frames++; quick = shown(c) }

        s.update("color", 0xFF20A060.toInt())
        assertTrue("the weights are rendering", kit.idleUntil { started.count == 0L })
        // The next value while they render: that preview stops, a new one starts.
        s.update("color", 0xFFC03080.toInt())
        release.countDown()
        assertTrue(kit.idleUntil { !s.isRendering && !s.previewStale })
        assertEquals("the weights were rendered once", 1, renders.get())
        assertEquals("one quick frame, of the second value", 1, frames)
        assertTrue(s.holdsQuickSource)
        val expected = kit.render(ObjectColorSlots.map(content, sel, cdc.colorMapper(s.values)!!))
        assertQuick("after a stop", content, sel, original, expected, quick!!)
        val exact = shown(c)
        for (i in expected.indices) if (premultipliedDiff(expected[i], exact[i]) > 2) fail("the exact render replaced it, pixel (${i % kit.w}, ${i / kit.w})")

        s.cancel()
        assertTrue(kit.idleUntil { s.isClosed })
        assertEquals("nothing recorded", steps, c.undoManager.undoCount)
        assertSame(content, c.vec.vector)
        assertArrayEquals(original, kit.pixels(c.vec.bitmap))
    }

    @Test
    fun everyObjectATargetOrAnArrayKeepsItsPreview() {
        // Every object (no selection): the exact pixel preview, no quick frame.
        val c = layer()
        val s = start(c)
        assertTrue(s.holdsPixelCopy)
        s.quickAfterMs = 0L
        var pixelFrames = 0
        s.onQuickFrame = { pixelFrames++ }
        s.update("color", 0xFF20A060.toInt())
        assertTrue(kit.idleUntil { !s.isRendering && !s.previewStale })
        assertEquals("no quick frame", 0, pixelFrames)
        assertFalse(s.holdsQuickSource)
        s.cancel()
        kit.idle()

        // Some objects of a layer with an array: the exact render alone.
        c.vectors.updateArray(c.vec, LayerArray(ArraySpec(mode = ArrayMode.LINE, count = 3, relativeX = 0f, constantX = 6f, constantY = 4f)), "Array")
        kit.idle()
        c.vectors.setSelection(c.vec, c.vec.vector!!.objects.take(3).mapTo(HashSet()) { it.id })
        kit.idle()
        val t = start(c)
        assertFalse(t.holdsPixelCopy)
        t.quickAfterMs = 0L
        var frames = 0
        t.onQuickFrame = { frames++ }
        t.update("color", 0xFF20A060.toInt())
        assertTrue(kit.idleUntil { !t.isRendering && !t.previewStale })
        assertEquals("no quick frame", 0, frames)
        assertFalse(t.holdsQuickSource)
        t.cancel()
    }
}
