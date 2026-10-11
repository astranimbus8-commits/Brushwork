package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.differing
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshRender
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshVector
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.filters.GradientEditing
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.vector.VStop
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * v1.8 final QA, cluster "look" (item 2, design §3.3), verifier's flow: an array whose Look turns
 * its copies, then mirrored, as the user does it on the 392 dp phone. A reflection reverses the
 * sweep and the turn of the placement (v1.7), so it must reverse the look's turns too
 * (`ArraySpec.mapped`), or the mirrored copies lean the wrong way:
 * - A pixel array (from a selection), Count 4, End opacity 50, End size 60, End turn 30 and
 *   Sunset (each one step "Array look"); the Transform tool's "Flip horizontally" and ✓: ONE step
 *   "Transform", still a live array, End turn -30 and the rest of the look kept, the cache a fresh
 *   render of the data (I1), the pixels the mirror of what was there; a two-finger tap takes it
 *   back exactly.
 * - "Rotate 90° clockwise" and ✓ on the same array: no reflection, the turns kept; I1; the pixels
 *   the old ones turned a quarter; undone exactly.
 * - A vector array (a finger stroke on a vector layer, layer ⋮ "Array…") with the same look; the
 *   layer window's "Flip layer horizontally": ONE step, the turns reversed, I1, the mirror about
 *   the document's centre; undone exactly.
 * PNGs: `look-array-flip.png` (before, flipped, turned; the vector before and flipped) and
 * `look-array-flip-screen.png`. One test, own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.lookarrayflipsandbox"])
class Qa18LookArrayFlipTest {

    @Test
    fun aTurnedLookFollowsAMirror() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        h.section("a turned look under a mirror") { flow(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    private lateinit var u: Qa17ArrayUi

    private val sunset: List<VStop> = GradientEditing.presets.first { it.first == SUNSET }.second.map { VStop(it.position, it.color) }

    /** Runs [block] and asserts it recorded ONE step [label]. */
    private fun oneStep(what: String, label: String = ArrayLabels18.HISTORY_LOOK, block: () -> Unit) {
        val n = u.steps()
        block()
        u.settleRenders(what)
        assertEquals("$what: one step", n + 1, u.steps())
        assertEquals(what, label, u.c.undoManager.undoLabel)
    }

    private fun refused(): Boolean = has(TransformTool.ARRAY_REFUSAL, exact = true)

    /**
     * The Transform tool from the tool menu: it lifts the array (not refused). Already the tool
     * (an undo dropped its untouched lift): a finger on the array lifts it again, as on the phone.
     */
    private fun transform(): TransformTool {
        if (u.c.activeToolId == ToolId.TRANSFORM) {
            assertTrue(u.onCanvas(TOUCH.first, TOUCH.second))
            u.ui.tap(TOUCH.first, TOUCH.second)
        } else {
            u.tool("Transform")
        }
        val t = u.c.currentTool as TransformTool
        val ok = Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS / 4) { settle(1); t.lifted != null || refused() }
        settle()
        assertTrue("the array lifted; \"${TransformTool.ARRAY_REFUSAL}\" shown: ${refused()}", ok && t.lifted != null)
        assertEquals(TransformTool.Lifted.ARRAY, t.lifted)
        return t
    }

    /** ✓ "Apply transform edit": ONE step "Transform", nothing baked. */
    private fun applyTransform() {
        val n = u.steps()
        u.applyEdit("Apply transform edit")
        u.settleRenders("transform applied")
        assertEquals("one step; \"${TransformTool.ARRAY_REFUSAL}\" shown: ${refused()}", n + 1, u.steps())
        assertEquals(TransformTool.TRANSFORM_LABEL, u.c.undoManager.undoLabel)
        assertFalse("nothing baked", has(ArrayLabels.APPLIED, exact = true))
    }

    /** The Array sheet closed with its "Close" (and the layer window it came from). */
    private fun closeSheet() {
        if (u.s.tagged(V17Tags.ARRAY_SHEET) == null) return
        click("Close", exact = true)
        settle()
        assertNull("the Array sheet closed", u.s.tagged(V17Tags.ARRAY_SHEET))
        if (u.s.tagged(ChromeTags.LAYER_WINDOW) != null) u.closeLayers()
    }

    /** On the Look tab of the open Array sheet: End opacity 50, End size 60, End turn 30, Sunset; each one step. */
    private fun setLook(name: String): ArrayLook {
        u.showArraySheet()
        if (u.s.tagged(V18Tags.ARRAY_LOOK) == null) u.fingerPress(ArrayLabels18.LOOK, 44f)
        assertNotNull("$name: the Look tab", u.s.tagged(V18Tags.ARRAY_LOOK))
        oneStep("$name: End opacity") { u.typeField(ArrayLabels18.END_OPACITY, "50") }
        oneStep("$name: End size") { u.typeField(ArrayLabels18.END_SIZE, "60") }
        oneStep("$name: End turn") { u.typeField(ArrayLabels18.END_TURN, "30") }
        oneStep("$name: Sunset") { u.press(SUNSET) }
        closeSheet()
        return requireNotNull(u.c.activeLayer.array?.spec?.look) { "$name: a look" }
    }

    /** The box of [px]'s pixels with alpha above 8 ([w] wide). */
    private fun inkBox(px: IntArray, w: Int): Rect {
        val r = Rect(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
        for (i in px.indices) if (px[i] ushr 24 > 8) r.union(i % w, i / w, i % w + 1, i / w + 1)
        return r
    }

    /**
     * How far [after] is from [before] seen through [map] (after's pixel (x, y) -> before's), per
     * PREMULTIPLIED channel: the worst inside (before's 3×3 there is even), and how many edge
     * pixels are off by more than 48 (a copy drawn turned has hard edges: a pixel centre on an
     * edge may land on either side of its mirror).
     */
    private fun offBy(before: IntArray, after: IntArray, w: Int, h: Int, map: (Int, Int) -> Pair<Int, Int>): Pair<Int, Int> {
        fun at(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) before[y * w + x] else 0
        var inside = 0
        var edges = 0
        for (y in 0 until h) for (x in 0 until w) {
            val (bx, by) = map(x, y)
            val p = at(bx, by)
            val q = after[y * w + x]
            if (p == q) continue
            val ap = p ushr 24
            val aq = q ushr 24
            var d = abs(ap - aq)
            for (s in intArrayOf(16, 8, 0)) d = maxOf(d, abs(((p shr s) and 0xFF) * ap / 255 - ((q shr s) and 0xFF) * aq / 255))
            var even = true
            for (dy in -1..1) for (dx in -1..1) if (at(bx + dx, by + dy) != p) even = false
            if (even) inside = maxOf(inside, d) else if (d > 48) edges++
        }
        return inside to edges
    }

    /** The largest difference between [a] and [b] per PREMULTIPLIED channel. */
    private fun premultipliedOff(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) {
            val p = a[i]
            val q = b[i]
            if (p == q) continue
            val ap = p ushr 24
            val aq = q ushr 24
            worst = maxOf(worst, abs(ap - aq))
            for (s in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((p shr s) and 0xFF) * ap / 255 - ((q shr s) and 0xFF) * aq / 255))
        }
        return worst
    }

    /** [after] the mirror of [before] (left and right), about the axis their ink boxes give; returns the axis sum. */
    private fun assertMirror(what: String, before: IntArray, after: IntArray, w: Int, h: Int): Int {
        val b = inkBox(before, w)
        val a = inkBox(after, w)
        assertEquals("$what: the ink as wide: $b -> $a", b.width().toFloat(), a.width().toFloat(), 1f)
        assertEquals("$what: the ink as high: $b -> $a", b.height().toFloat(), a.height().toFloat(), 1f)
        assertEquals("$what: the same rows: $b -> $a", b.top, a.top)
        val sum = a.left + b.right - 1
        val (inside, edges) = offBy(before, after, w, h) { x, y -> (sum - x) to y }
        println("Qa18LookArrayFlip $what: ink $b -> $a, mirror off by $inside inside, $edges edge px off")
        assertTrue("$what: the mirror of before inside the ink (off by $inside)", inside <= 4)
        assertTrue("$what: the mirror of before on the edges ($edges px off)", edges <= 24)
        return sum
    }

    private fun flow(ui: Qa17ArrayUi) {
        u = ui
        u.editor(Smoke.document(512, 512, layers = 2, whiteBottom = true))
        val c = u.c
        val w = c.doc.width
        val h = c.doc.height

        // ------------------------------------------------------------ A pixel array with a turned look
        // An asymmetric source: a light square with a dark corner at its top left.
        u.seed(c.activeLayer, 184f, 224f, 224f, 264f, LIGHT)
        u.seed(c.activeLayer, 188f, 228f, 200f, 240f, DARK)
        c.setSelection(u.rectSelection(180f, 220f, 228f, 268f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val layer = c.activeLayer
        assertNotNull("a live pixel array", layer.array?.pixels)
        u.showArraySheet()
        oneStep("Count", ArrayLabels.EDIT) { u.typeField("Count", "4") }
        val look0 = setLook("pixels")
        assertEquals(30f, look0.turnEnd, 1e-3f)
        assertEquals(sunset, look0.tint)
        val spec0 = layer.array!!.spec
        val src0 = pixels(layer.array!!.pixels!!.bitmap)
        val px0 = pixels(layer.bitmap)
        val shot0 = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        assertEquals("I1 before: the cache is a fresh render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        val n0 = u.steps()

        // The Transform tool's "Flip horizontally", then ✓.
        transform()
        assertTrue("\"Flip horizontally\" is offered for a pixel array", has("Flip horizontally", exact = true))
        u.press("Flip horizontally")
        applyTransform()
        assertNotNull("flipped: still a live array", layer.array?.pixels)
        val flippedLook = requireNotNull(layer.array!!.spec.look) { "flipped: the look kept" }
        assertEquals("flipped: End turn reversed", -30f, flippedLook.turnEnd, 1e-3f)
        assertEquals("flipped: Start turn still 0", 0f, flippedLook.turnStart, 0f)
        assertEquals("flipped: the rest of the look kept", look0, flippedLook.copy(turnStart = look0.turnStart, turnEnd = look0.turnEnd))
        assertEquals("flipped: I1, the cache is a fresh render of the data", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        val pxFlip = pixels(layer.bitmap)
        assertMirror("Transform flip", px0, pxFlip, w, h)
        val shotFlip = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        u.ui.twoFingerUndo()
        u.settleRenders("undo the flip")
        assertEquals("undo: the steps as before", n0, u.steps())
        assertEquals("undo: the spec as it was", spec0, layer.array!!.spec)
        assertArrayEquals("undo: the source pixels as they were", src0, pixels(layer.array!!.pixels!!.bitmap))
        assertArrayEquals("undo: the copies as they were", px0, pixels(layer.bitmap))

        // "Rotate 90° clockwise", then ✓: no reflection, the turns kept.
        transform()
        u.press("Rotate 90° clockwise")
        applyTransform()
        assertNotNull("turned: still a live array", layer.array?.pixels)
        assertEquals("turned: the look as it was (no reflection)", look0, layer.array!!.spec.look)
        assertEquals("turned: I1", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        val pxTurn = pixels(layer.bitmap)
        val b = inkBox(px0, w)
        val a = inkBox(pxTurn, w)
        assertEquals("turned: the ink's width becomes its height: $b -> $a", b.width().toFloat(), a.height().toFloat(), 1f)
        assertEquals(b.height().toFloat(), a.width().toFloat(), 1f)
        // After's (x, y) shows before's (y - a.top + b.left, a.left + b.bottom - 1 - x).
        val (tin, tedges) = offBy(px0, pxTurn, w, h) { x, y -> (y - a.top + b.left) to (a.left + b.bottom - 1 - x) }
        println("Qa18LookArrayFlip quarter turn: ink $b -> $a, off by $tin inside, $tedges edge px off")
        assertTrue("turned: the old pixels turned a quarter inside the ink (off by $tin)", tin <= 4)
        assertTrue("turned: on the edges ($tedges px off)", tedges <= 24)
        val shotTurn = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        u.ui.twoFingerUndo()
        u.settleRenders("undo the turn")
        assertEquals(n0, u.steps())
        assertEquals("undo: the spec as it was", spec0, layer.array!!.spec)
        assertArrayEquals("undo: the copies as they were", px0, pixels(layer.bitmap))

        // ------------------------------------------------------------ A vector array, the layer flipped
        val vec = requireNotNull(c.addVectorLayer("Strokes"))
        settle()
        assertSame(vec, c.activeLayer)
        u.tool("Brush")
        c.color = VCOLOR
        c.brush = c.brush.copy(size = 8f, opacity = 1f)
        // A "7" by finger: a bar and a slanted leg (it mirrors into another shape).
        val seven = listOf(40f to 60f, 70f to 60f, 100f to 60f, 90f to 85f, 80f to 110f, 70f to 140f)
        for ((x, y) in seven) assertTrue("($x, $y) on the canvas", u.onCanvas(x, y))
        oneStep("the stroke", "Brush") { u.ui.stroke(*seven.toTypedArray()) }
        assertTrue("a vector stroke", vec.vector?.objects?.isNotEmpty() == true)
        oneStep("Array…", ArrayLabels.BUTTON) { u.layerMenu(ArrayLabels.OPEN) }
        assertNotNull("a live vector array", vec.array)
        u.showArraySheet()
        if (u.s.tagged(V18Tags.ARRAY_LOOK) != null) u.fingerPress(ArrayLabels18.PLACEMENT, 44f)
        oneStep("vector Count", ArrayLabels.EDIT) { u.typeField("Count", "4") }
        val vlook0 = setLook("vector")
        val vdata0 = vec.dataSnapshot()
        val vpx0 = pixels(vec.bitmap)
        val vshot0 = vec.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        assertEquals("vector I1 before", 0, differing(vec.bitmap, freshVector(c, vdata0)))
        val vn = u.steps()

        // The layer window's "Flip layer horizontally".
        u.openLayers()
        u.ui.reach(LayerLabels.FLIP_H, 40f)
        oneStep("Flip layer horizontally", LayerLabels.FLIP_H) { click(LayerLabels.FLIP_H, exact = true) }
        if (u.s.tagged(ChromeTags.LAYER_WINDOW) != null) u.closeLayers()
        assertNotNull("flipped: still a live vector array", vec.array)
        val vflip = requireNotNull(vec.array!!.spec.look) { "flipped: the look kept" }
        assertEquals("vector flipped: End turn reversed", -30f, vflip.turnEnd, 1e-3f)
        assertEquals("vector flipped: the rest of the look kept", vlook0, vflip.copy(turnStart = vlook0.turnStart, turnEnd = vlook0.turnEnd))
        // The layer's pixels are mirrored in place (its round brush mirrors into itself): against a
        // fresh render of the mirrored data, the turned copies' soft edges differ by rounding only.
        val vI1 = premultipliedOff(pixels(vec.bitmap), pixels(freshVector(c, vec.dataSnapshot())))
        println("Qa18LookArrayFlip layer flip I1: off by at most $vI1 (premultiplied)")
        assertTrue("vector flipped: I1 within rounding (off by $vI1)", vI1 <= 3)
        val vpxFlip = pixels(vec.bitmap)
        val sum = assertMirror("layer flip", vpx0, vpxFlip, w, h)
        assertEquals("the layer mirrored about the document's centre", w - 1, sum)
        val vshotFlip = vec.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        save(IbisShots.capture(), "look-array-flip-screen.png")
        u.ui.twoFingerUndo()
        u.settleRenders("undo the layer flip")
        assertEquals(vn, u.steps())
        assertEquals("undo: the data as it was", vdata0, vec.dataSnapshot())
        assertArrayEquals("undo: the pixels as they were", vpx0, pixels(vec.bitmap))

        save(sheet(listOf(shot0, shotFlip, shotTurn, vshot0, vshotFlip)), "look-array-flip.png")
        Smoke.assertQuiet(c, "a turned look under a mirror")
    }

    /** [layers] over white at half size, three to a row. */
    private fun sheet(layers: List<Bitmap>): Bitmap {
        val cell = layers[0].width / 2
        val rows = (layers.size + 2) / 3
        val out = Bitmap.createBitmap(cell * 3, layers[0].height / 2 * rows, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(0xFFFFFFFF.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val frame = Paint().apply { style = Paint.Style.STROKE; color = 0xFFB0B0B0.toInt() }
        for ((i, b) in layers.withIndex()) {
            val r = Rect((i % 3) * cell, (i / 3) * (b.height / 2), (i % 3 + 1) * cell, (i / 3 + 1) * (b.height / 2))
            cv.drawBitmap(b, null, r, p)
            cv.drawRect(r, frame)
        }
        return out
    }

    private companion object {
        const val LIGHT = 0xFFE6E6E6.toInt()
        const val DARK = 0xFF707070.toInt()
        const val VCOLOR = 0xFF203040.toInt()
        const val SUNSET = "Sunset"
        /** A point on the pixel array's first copy (document px). */
        val TOUCH = 244f to 244f

        private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

        /** Writes [b] as [file] in the v1.8 QA shots folder when this machine has it. */
        fun save(b: Bitmap, file: String): String? {
            if (SHOTS.parentFile?.isDirectory != true) return null
            SHOTS.mkdirs()
            val f = File(SHOTS, file)
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }
    }
}
