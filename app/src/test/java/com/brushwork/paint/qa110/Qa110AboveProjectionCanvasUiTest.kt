package com.brushwork.paint.qa110

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.live.ProjectionRules
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.ProjectionOracle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.v110.a9.A9Docs
import com.brushwork.paint.v110.a9.WaitingTimingKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Random

/**
 * v1.10 QA (A9 step 7, design 2.5 X5, I33): a finger paints with the brush on a layer under 10
 * Normal layers at TILES, with the check's above projection on (`EngineConfig.aboveProjection`),
 * on the canvas view itself (MotionEvents through the window, a frame drawn after every sample).
 *
 * While the finger is down the canvas draws through the above projection (its parts counted and
 * known as approximate), within the measured bound of `AboveProjectionErrorTest` (4 LSB per
 * premultiplied channel) of the full draw of the document with the stroke's preview. The first
 * frame after the lift leaves nothing approximate, and the display then equals the full draw bit
 * for bit. Undo (by its label) gives back the canvas before the stroke, bit for bit, and Redo the
 * painted one. A second stroke draws through the same projection without building it again (the
 * key holds the layers above; edits of the active layer never drop it), and lifts exact too. At
 * 392 and 360 dp; step 7 adds no control or text, so the font scales are the other A9 UI test's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392)
class Qa110AboveProjectionCanvasUiTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    @After
    fun tearDown() {
        A9Docs.disposeTracked()
    }

    /** Draws [s]'s canvas as frames do until the stroke is committed and no display tile is dirty, waiting or approximate. */
    private fun finish(s: ChromeScreen, where: String) {
        val view = s.canvas
        assertTrue("$where: the canvas is laid out", view.width > 0 && view.height > 0)
        val scratch = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val frame = Canvas(scratch)
        try {
            val done = Smoke.pumpUntil(60_000) {
                view.draw(frame)
                s.c.renderOverride == null && !s.c.tiles.hasDirty && !s.c.tiles.hasWaiting && !s.c.tiles.hasApprox
            }
            assertTrue("$where: the canvas finished rendering", done)
        } finally {
            scratch.recycle()
        }
    }

    /** The display tiles of [s] equal the full draw of its document (no projection, no preview), bit for bit. */
    private fun assertExact(s: ChromeScreen, where: String) {
        assertEquals("$where: no tool preview left", null, s.c.renderOverride)
        val expected = ProjectionOracle.frame(s.c.doc, display = true)
        val actual = WaitingTimingKit.shown(s.c.tiles)
        try {
            A9Docs.assertBitEqual("$where: the display against the full draw", expected, actual)
        } finally {
            expected.recycle()
            actual.recycle()
        }
    }

    /** The largest premultiplied byte difference of the display tiles of [s] from the full draw with the stroke's preview, now. */
    private fun errorNow(s: ChromeScreen): Int {
        val expected = ProjectionOracle.frame(s.c.doc, override = s.c.renderOverride, display = true)
        val actual = WaitingTimingKit.shown(s.c.tiles)
        try {
            val h = A9Docs.premultipliedErrorHistogram(expected, actual)
            return h.indices.lastOrNull { h[it] > 0L } ?: 0
        } finally {
            expected.recycle()
            actual.recycle()
        }
    }

    /**
     * One finger along [docPoints] (document px) on [s]'s canvas, a frame drawn after the down and
     * after every move, as the display would; returns the largest difference from the full draw
     * seen while the finger was down. The finger stays down at the end ([lift] lifts it).
     */
    private fun strokeDown(s: ChromeScreen, frame: Canvas, docPoints: List<Pair<Float, Float>>): Int {
        val view = s.canvas
        val pts = docPoints.map { s.screen(it.first, it.second) }
        s.touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, pts[0].first, pts[0].second))
        view.draw(frame)
        var worst = 0
        for (p in pts.drop(1)) {
            s.touch.idle(16)
            s.touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, p.first, p.second))
            view.draw(frame)
            worst = maxOf(worst, errorNow(s))
        }
        return worst
    }

    /** Lifts the finger at document point [last] and draws the first frame after the lift. */
    private fun lift(s: ChromeScreen, frame: Canvas, last: Pair<Float, Float>) {
        val p = s.screen(last.first, last.second)
        s.touch.idle(16)
        s.touch.send(MotionEvent.ACTION_UP, Smoke.P(0, p.first, p.second))
        s.canvas.draw(frame)
    }

    @Test
    fun aFingerPaintsUnderTenLayersThroughTheAboveProjectionAndLiftsExact() = Qa19Ui.uiTest { h ->
        for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) {
            val where = "at ${width.substringBefore('-')}"
            h.section(where) {
                RuntimeEnvironment.setQualifiers(width)
                A9Docs.setAboveProjection(true)
                val made = mutableListOf<Bitmap>()
                try {
                    val d = stack()
                    val active = d.layers[ACTIVE]
                    val u = ProjectionRules.aboveStart(d, active)
                    assertTrue("$where: the stack is eligible", ProjectionRules.aboveEligible(d, active, u))
                    val s = h.editor(d)
                    val c = s.c
                    assertEquals("$where: the brush is the tool", ToolId.BRUSH, c.activeToolId)
                    assertEquals(active, c.doc.layers[c.doc.activeLayerIndex])
                    val above = c.liveAdjust.aboveProjection
                    finish(s, "$where, opened")
                    assertEquals("$where: nothing is built with the finger up", 0L, above.builds)
                    assertExact(s, "$where, opened")
                    val before = WaitingTimingKit.shown(c.tiles).also { made += it }
                    val view = s.canvas
                    val scratch = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { made += it }
                    val frame = Canvas(scratch)

                    // The finger down: the frames draw through the above projection, within the bound.
                    val path = List(13) { i -> (70f + 20f * i) to (80f + 12f * i) }
                    val worst = strokeDown(s, frame, path)
                    assertTrue("$where: the frames with the finger down drew through the above projection", above.hits > 0)
                    assertTrue("$where: one display tile built", above.builds >= 1L)
                    assertTrue("$where: the parts it drew are known as approximate", c.tiles.hasApprox)
                    assertTrue("$where: at most $MAX_LSB LSB from the full draw while the finger is down (max $worst)", worst <= MAX_LSB)
                    println("Qa110AboveProjectionCanvasUiTest $where: ${above.hits} parts drawn through the projection, ${above.builds} built, max $worst LSB")

                    // The lift: the first frame after it is exact.
                    val hits = above.hits
                    lift(s, frame, path.last())
                    assertFalse("$where: nothing approximate after the first frame after the lift", c.tiles.hasApprox)
                    finish(s, "$where, lifted")
                    assertEquals("$where: never used with the finger up", hits, above.hits)
                    assertExact(s, "$where, after the lift")
                    val painted = WaitingTimingKit.shown(c.tiles).also { made += it }
                    assertTrue("$where: the stroke shows", A9Docs.differing(before, painted) > 0)

                    // Undo and Redo, by their labels.
                    SmokeUi.click("Undo", exact = true)
                    finish(s, "$where, undone")
                    A9Docs.assertBitEqual("$where: Undo gives back the canvas before the stroke", before, WaitingTimingKit.shown(c.tiles).also { made += it })
                    assertExact(s, "$where, undone")
                    SmokeUi.click("Redo", exact = true)
                    finish(s, "$where, redone")
                    A9Docs.assertBitEqual("$where: Redo gives back the painted canvas", painted, WaitingTimingKit.shown(c.tiles).also { made += it })

                    // A second stroke: the same projection, not built again.
                    val builds = above.builds
                    val drops = above.drops
                    val hits2 = above.hits
                    val path2 = List(10) { i -> (300f - 18f * i) to (60f + 16f * i) }
                    val worst2 = strokeDown(s, frame, path2)
                    assertTrue("$where: the second stroke drew through the projection", above.hits > hits2)
                    assertEquals("$where: kept across strokes, undo and redo (no build)", builds, above.builds)
                    assertEquals("$where: never dropped by edits of the active layer", drops, above.drops)
                    assertTrue("$where: the second stroke within $MAX_LSB LSB (max $worst2)", worst2 <= MAX_LSB)
                    lift(s, frame, path2.last())
                    assertFalse("$where: nothing approximate after the second lift", c.tiles.hasApprox)
                    finish(s, "$where, lifted again")
                    assertExact(s, "$where, after the second lift")
                    Smoke.assertQuiet(c, where)
                } finally {
                    made.forEach { if (!it.isRecycled) it.recycle() }
                }
            }
        }
    }

    /**
     * [W] x [H], built the same each time: an opaque backdrop (its right third translucent), the
     * empty layer painted on ([ACTIVE], active), then [ABOVE] Normal layers of translucent shapes.
     */
    private fun stack(): Document {
        val d = A9Docs.track(Document("above-ui", "Above UI", W, H))
        val r = Random(SEED)
        val p = Paint().apply { isAntiAlias = true }
        d.layers += Layer(d.newLayerId(), "Backdrop", A9Docs.backdrop(SEED, W, H))
        d.layers += Layer(d.newLayerId(), "Painted", A9Docs.empty(W, H))
        repeat(ABOVE) { i ->
            val b = BitmapUtils.createLayerBitmap(W, H)
            val cv = Canvas(b)
            repeat(3) {
                p.color = Color.argb(60 + r.nextInt(150), r.nextInt(256), r.nextInt(256), r.nextInt(256))
                cv.drawCircle(r.nextInt(W).toFloat(), r.nextInt(H).toFloat(), 20f + r.nextInt(70), p)
            }
            d.layers += Layer(d.newLayerId(), "Above $i", b).apply { opacity = 0.7f + r.nextInt(31) / 100f }
        }
        d.activeLayerIndex = ACTIVE
        return d
    }

    private companion object {
        const val W = 400
        const val H = 300
        const val SEED = 117L
        const val ACTIVE = 1
        const val ABOVE = 10

        /** `AboveProjectionErrorTest.MAX_LSB`: the bound measured over its stacks. */
        const val MAX_LSB = 4
    }
}
