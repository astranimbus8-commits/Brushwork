package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.draw.VectorStrokeCapture
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v1.7 final QA, layers cluster (item 18, design §3.18; device checklist §6.4 row 12): symmetry
 * on a vector layer, by fingers on the user's phone size. The Vector button turns the clear layer
 * into a vector layer; the Symmetry tool's "Rotation ruler" (6 divisions) is set; one finger
 * stroke is ONE `VStroke` holding its 6 copies (one undo step), the copies painted where the
 * rotations worked out here put them. Saved and reopened, the stroke, its copies, the ruler and
 * the picture are the same. Then the vector eraser, in each of its three modes (the chips at the
 * start of its strip), passes over one copy only: the whole stroke goes in one step, and one
 * two-finger tap brings it back; the eraser's own hint shows on its first pass and "Symmetry
 * doesn't apply to erasing vector objects" on the next, once.
 */
internal class Qa17LayersSymmetryVector(private val h: ChromeHarness) {
    private lateinit var s: ChromeScreen
    private var u: Qa17LayersUi? = null
    private val l: Qa17LayersUi get() = u!!
    private val c: EditorController get() = s.c

    /** What the first section leaves for the second: the reopened document and what it must show. */
    private var loaded: Document? = null
    private var drawn: VectorContent? = null
    private var picture: IntArray? = null

    fun release() {
        u?.release()
        u = null
    }

    // ================================================================== the editor

    /** White Layer 1 under a clear Layer 2 (active). */
    private fun document(): Document = Smoke.document(N, N, layers = 2, whiteBottom = true)

    private fun attach(screen: ChromeScreen) {
        u?.release()
        s = screen
        // The tools are made by now (they would bring their own presets back).
        c.color = INK
        c.brush = c.brush.copy(size = 6f, opacity = 1f, pressureSize = false)
        c.eraser = c.eraser.copy(size = 10f, opacity = 1f, pressureSize = false)
        u = Qa17LayersUi(s)
        settle()
        assertEquals(392f, s.widthDp, 1f)
    }

    /** [label] brought wholly into view in its strip, the strip at rest, then tapped by finger. */
    private fun press(label: String) {
        l.ui.reach(label, 32f)
        Smoke.pump(600)
        settle()
        Finger.tap(s, label)
    }

    private val layer: Layer get() = c.doc.layers[1]

    private fun pixels(b: Bitmap): IntArray = IntArray(N * N).also { b.getPixels(it, 0, N, 0, 0, N, N) }

    /** [content] drawn from its data alone (what a replay of the objects paints). */
    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(N, N)
        try {
            VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, N, N), tips = TipCache(), document = Rect(0, 0, N, N))
            return pixels(b)
        } finally {
            b.recycle()
        }
    }

    // ================================================================== the rotations worked out here

    /** The point ([x], [y]) turned [deg] degrees about the canvas centre (y down: clockwise on screen). */
    private fun turn(x: Float, y: Float, deg: Double): Pair<Float, Float> {
        val a = Math.toRadians(deg)
        val co = cos(a).toFloat(); val si = sin(a).toFloat()
        val dx = x - C; val dy = y - C
        return C + co * dx - si * dy to C + si * dx + co * dy
    }

    /** The 3×3 map of that turn, as `VStroke.copies` holds it. */
    private fun turnMap(deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        val co = cos(a).toFloat(); val si = sin(a).toFloat()
        return floatArrayOf(co, -si, C - co * C + si * C, si, co, C - si * C - co * C, 0f, 0f, 1f)
    }

    /** The finger stroke: straight up from the centre, [R0]..[R1] px out. */
    private val stroke = listOf(C to C - R0, C to C - (R0 + R1) / 2f, C to C - R1)

    /** Every pixel along the stroke, turned [deg] degrees. */
    private fun line(deg: Double): List<Pair<Float, Float>> =
        (0..(R1 - R0).toInt()).map { k -> turn(C, C - R0 - k, deg) }

    /**
     * The ink of [px] is the stroke turned by each of the 6 rotations: ink within 3 px of the
     * 35 %, 50 % and 65 % points of every copy, and no ink farther than 6 px from all of them.
     */
    private fun checkInk(what: String, px: IntArray) {
        fun ink(x: Int, y: Int) = x in 0 until N && y in 0 until N && px[y * N + x] ushr 24 >= 200
        val lines = (0 until 6).map { line(60.0 * it) }
        for ((k, ln) in lines.withIndex()) for (t in listOf(0.35f, 0.5f, 0.65f)) {
            val (x, y) = ln[(t * ln.lastIndex).toInt()]
            val hit = (-3..3).any { dy -> (-3..3).any { dx -> ink(x.toInt() + dx, y.toInt() + dy) } }
            assertTrue("$what: the copy turned ${60 * k}° is painted at (${x.toInt()}, ${y.toInt()})", hit)
        }
        val all = lines.flatten()
        var stray = 0
        var first = ""
        for (y in 0 until N) for (x in 0 until N) if (ink(x, y) && all.none { (ax, ay) -> hypot(ax - x, ay - y) <= 6f }) {
            if (stray++ == 0) first = "($x, $y)"
        }
        assertEquals("$what: ink away from every copy (first $first)", 0, stray)
    }

    // ================================================================== 1. draw, save

    fun drawAndSave() {
        attach(h.editor(document()) { it.snapping.enabled = false })
        val clear = layer
        assertSame("Layer 2 is active", clear, c.activeLayer)
        l.captions()

        // The Vector button: the clear layer becomes the vector layer, in place.
        press("Vector")
        settle()
        assertTrue("vector mode is on", c.isVectorMode)
        assertSame("the clear layer converted in place", clear, c.activeLayer)
        assertTrue(clear.isVectorLayer)
        assertTrue("the strip says so", has("Vector mode is on"))
        assertEquals(
            "the vector mode hint",
            listOf("Vector mode: what you draw stays editable. Tap Vector again to go back."),
            l.captions(),
        )

        // The Symmetry tool's "Rotation ruler", 6 divisions as it comes; no step, no pixel.
        l.noStep("the rotation ruler") {
            l.ui.tool(SymmetryLabels.TOOL)
            assertEquals("picking the tool turns the mirror on", SymmetryType.MIRROR, c.symmetry.type)
            press(SymmetryType.ROTATION.label)
            assertEquals(SymmetryType.ROTATION, c.symmetry.type)
            assertTrue("\"Divisions 6\"", has("Divisions 6", exact = true))
            assertEquals(6, c.symmetry.divisions)
            press("Done")
            assertEquals("Done: back to the brush", ToolId.BRUSH, c.activeToolId)
        }
        assertEquals(SymmetrySettings(type = SymmetryType.ROTATION), c.symmetry)
        assertTrue("still in vector mode", c.isVectorMode && has("Vector mode is on"))

        // One finger stroke: ONE object holding the 6 copies, one step.
        assertEquals("the brush's size", 6f, c.brush.size, 0f)
        l.oneStep("the stroke", "Brush") { l.ui.stroke(*stroke.toTypedArray()) }
        val content = clear.vector!!
        val v = content.objects.single() as VStroke
        assertEquals("6 copies in the one stroke", 6, v.copies.size)
        for (k in 0 until 6) {
            val want = turnMap(60.0 * k)
            assertTrue(
                "the copy turned ${60 * k}° is among the stroke's maps: ${v.copies.map { it.toList() }}",
                v.copies.any { m -> m.indices.all { abs(m[it] - want[it]) < 1e-3f } },
            )
        }
        val ink = pixels(clear.bitmap)
        checkInk("drawn", ink)
        val replay = render(content)
        assertArrayEquals("the live pixels are what the stroke's data replays", replay, ink)
        assertEquals("no caption for the stroke", emptyList<String>(), l.captions())
        Qa17LayersShots.shoot(c, "symmetry-vector-drawn")

        // Saved, then loaded: the same stroke, copies, ruler and pixels.
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        picture = l.pixels()
        drawn = content
        runBlocking { repo.save(c.doc, null) }
        val back = runBlocking { repo.load(c.doc.id) }
        assertEquals("no load warnings", emptyList<String>(), back.loadWarnings.toList())
        val vl = back.layers[1]
        assertTrue("the layer is still a vector layer", vl.isVectorLayer)
        assertEquals("the same objects (copies compared by content)", content, vl.vector)
        assertEquals("the same stroke id", v.id, (vl.vector!!.objects.single() as VStroke).id)
        assertEquals("the ruler is kept", c.symmetry, back.symmetry)
        assertArrayEquals("the same layer pixels", ink, pixels(vl.bitmap))
        assertEquals("the vector layer stays the active one", 1, back.activeLayerIndex)
        loaded = back
        Smoke.assertQuiet(c, "drawn")
    }

    // ================================================================== 2. reopen, erase one copy

    fun reopenAndErase() {
        val doc = requireNotNull(loaded) { "section 1 did not save" }
        attach(h.editor(doc) { it.snapping.enabled = false })
        val vl = layer
        assertSame("the vector layer is active", vl, c.activeLayer)
        assertTrue("vector mode is on", c.isVectorMode && has("Vector mode is on"))
        assertEquals("the rotation ruler, 6 divisions", SymmetrySettings(type = SymmetryType.ROTATION), c.symmetry)
        assertEquals("the same stroke and copies", drawn, vl.vector)
        assertArrayEquals("reopened: the same picture", picture, l.pixels())
        assertArrayEquals("its pixels are its data's", render(vl.vector!!), pixels(vl.bitmap))
        assertEquals("nothing said on opening", emptyList<String>(), l.captions())
        Qa17LayersShots.shoot(c, "symmetry-vector-reopened")

        l.ui.tool("Eraser")
        assertEquals("the eraser's size", 10f, c.eraser.size, 0f)
        assertEquals("the eraser starts in Object mode", VectorEraseMode.OBJECT, VectorEraserModes.mode(c))
        for (m in VectorEraseMode.entries) assertTrue("the \"${m.label}\" chip", has(m.label, exact = true))
        l.captions()

        val first = VectorStrokeCapture.eraserHint(VectorEraseMode.OBJECT)
        pass(3, VectorEraseMode.OBJECT, listOf(first))
        pass(5, VectorEraseMode.PARTIAL, listOf(SymmetryLabels.ERASER_NOTE))
        pass(1, VectorEraseMode.TO_INTERSECTION, emptyList())
        assertArrayEquals("all undone: the reopened picture", picture, l.pixels())
        Smoke.assertQuiet(c, "erased and undone")
    }

    /**
     * The eraser in [mode] (its chip tapped) passes across the copy turned 60° × [k] only: the
     * whole stroke goes in one "Erase" step with exactly [captions] said; one two-finger tap
     * brings back the same stroke and pixels.
     */
    private fun pass(k: Int, mode: VectorEraseMode, captions: List<String>) {
        val vl = layer
        val what = "Eraser ($mode) over the copy turned ${60 * k}°"
        if (VectorEraserModes.mode(c) != mode) {
            l.noStep("the \"${mode.label}\" chip") { press(mode.label) }
            assertEquals(mode, VectorEraserModes.mode(c))
        }
        l.captions()
        val before = vl.vector
        val px = pixels(vl.bitmap)
        val steps = l.steps()
        // Across the copy's middle, 15 px either side (its neighbours are 60° away: ~60 px).
        val r = (R0 + R1) / 2f
        val mid = turn(C, C - r, 60.0 * k)
        val a = turn(C - 15f, C - r, 60.0 * k)
        val b = turn(C + 15f, C - r, 60.0 * k)
        l.oneStep(what, "Erase") { l.ui.stroke(a, mid, b) }
        assertEquals("$what: the whole stroke goes", 0, vl.vector!!.objects.size)
        assertTrue("$what: no pixel left on the layer", pixels(vl.bitmap).all { it == 0 })
        assertTrue("$what: still a vector layer", vl.isVectorLayer)
        assertEquals("$what: what is said", captions, l.captions())

        l.ui.twoFingerUndo()
        settle()
        assertEquals("$what: one undo", steps, l.steps())
        assertEquals("$what: the same stroke back", before, vl.vector)
        assertArrayEquals("$what: the same pixels back", px, pixels(vl.bitmap))
        // The first finger of the tap began an eraser stroke the second one cancelled: it erased
        // nothing and says nothing (it used to spend the one-time symmetry note here).
        assertEquals("$what: the two-finger undo says nothing", emptyList<String>(), l.captions())
    }

    companion object {
        const val N = 240
        const val C = 120f
        const val R0 = 28f
        const val R1 = 88f
        val INK = 0xFFC02040.toInt()

        fun run() {
            ShadowLog.stream = null
            com.brushwork.paint.smoke.SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 90_000)
            val h = ChromeHarness()
            val t = Qa17LayersSymmetryVector(h)
            val times = mutableListOf<String>()
            fun timed(name: String, block: () -> Unit) {
                val t0 = System.nanoTime()
                h.section(name, block)
                times += "$name ${(System.nanoTime() - t0) / 1_000_000} ms"
            }
            timed("1 draw and save") { t.drawAndSave() }
            timed("2 reopen and erase") { t.reopenAndErase() }
            println("Qa17LayersSymmetryVector times: $times")
            t.release()
            dog.interrupt()
            h.finish()
        }
    }
}

/** Item 18 on the user's phone (392 dp): rotation × 6 on a vector layer, saved, reopened, erased by one copy. */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.layerssymmetryvectorsandbox"])
class Qa17LayersSymmetryVectorUiTest {
    @Test
    fun aRotationStrokeOnAVectorLayerIsOneObjectKeptByReopeningAndErasedWholeFromAnyCopy() = Qa17LayersSymmetryVector.run()
}
