package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.filters.GradientEditing
import com.brushwork.paint.vector.VStop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.8 final QA, cluster "look" (item 2, design §3.3): an array made from a selection (pixels),
 * its Look set the way the user sets it on the 392 dp phone, by finger where a finger is used:
 * - End opacity dragged on its slider: the preview follows the finger, ONE step "Array look" on
 *   release; then typed.
 * - Each Pace chip: the middle copies' opacity follows its curve (Count 5: t = 1/3 and 2/3).
 * - End size and End turn: the last copy at half size, then turned (its ink box grows by √2).
 * - A 3-stop tint built on the gradient bar with a finger (a tap adds a stop, a tap on it opens
 *   "Stop color", a typed hex), Tint strength typed: each copy is its color tinted toward the
 *   stop at its t (ArrayTint), keeping its shading (the dark centre stays darker than the ring)
 *   and its opacity.
 * - The source keeps its pixels throughout (I19).
 * - Two-finger undo and three-finger redo; the sheet closed and reopened shows the values; a save
 *   and reopen gives the same spec and pixels.
 * - The draft rule: Count 70, a finger held on a slider: the screen shows the copies untinted,
 *   the tint back on release.
 * - "Apply array": one step, the same pixels, undone.
 * - Tint strength through 0 and back (a drag, then typed) keeps the chosen colors (QA fix).
 * PNGs: `look-array-pixels.png` (the layer, 2x) and `look-array-pixels-screen.png`.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.lookarraypixelssandbox"])
class Qa18LookArrayPixelsTest {

    @Test
    fun aSelectionArraysLookSetByFinger() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        h.section("a selection array's look") { flow(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    private lateinit var u: Qa17ArrayUi
    private lateinit var layer: Layer
    private lateinit var tool: ArrayTool

    private val look get() = layer.array!!.spec.look

    /** Runs [block] and asserts it recorded ONE step "Array look". */
    private fun lookStep(what: String, block: () -> Unit) {
        val n = u.steps()
        block()
        u.settleRenders(what)
        assertEquals("$what: one step", n + 1, u.steps())
        assertEquals(what, ArrayLabels18.HISTORY_LOOK, u.c.undoManager.undoLabel)
    }

    /** Where source point ([x], [y]) lands on copy [k] (document px). */
    private fun onCopy(k: Int, x: Float, y: Float): Pair<Int, Int> {
        val src = requireNotNull(ArrayDraw.sourceBounds(layer.dataSnapshot())) { "no source bounds" }
        val inst = ArrayInstances.of(layer.array!!.spec, src).first { it.k == k }
        val p = floatArrayOf(x, y)
        Matrix().apply { setValues(inst.matrix) }.mapPoints(p)
        return p[0].roundToInt() to p[1].roundToInt()
    }

    private fun px(p: Pair<Int, Int>): Int = layer.bitmap.getPixel(p.first, p.second)

    /** The ink box (alpha > 8) of the layer around ([cx], [cy]), [r] px each way. */
    private fun inkBox(cx: Int, cy: Int, r: Int): Rect {
        val out = Rect(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            if (layer.bitmap.getPixel(x, y) ushr 24 > 8) {
                out.left = minOf(out.left, x); out.top = minOf(out.top, y); out.right = maxOf(out.right, x + 1); out.bottom = maxOf(out.bottom, y + 1)
            }
        }
        return out
    }

    private fun sourcePixels(): IntArray = IntArray(40 * 40).also { layer.bitmap.getPixels(it, 0, 40, 40, 40, 40, 40) }

    /** The slider [label] wholly in view: its bounds (window px). */
    private fun slider(label: String): android.graphics.RectF {
        u.intoView(label) { u.ui.slider(label).node }
        val b = u.ui.slider(label).bounds
        return RectF(b.left, b.top, b.right, b.bottom)
    }

    /** The x of [f] (0..1) on a Material slider of bounds [b] (its track inset by half a thumb). */
    private fun xAt(b: RectF, f: Float): Float {
        val pad = 10f * u.s.density
        // Past 0 or 1: the finger goes on beyond the track's end (the value stays at its end).
        return b.left + pad + f * (b.width() - 2 * pad)
    }

    /** A finger down on [b] at [from], moved to each of [to] (12 samples per leg); still down. */
    private fun fingerDrag(b: RectF, from: Float, vararg to: Float) {
        u.s.touch.idle(300)
        u.s.touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, xAt(b, from), b.centerY()))
        u.s.touch.idle(16)
        fingerMove(b, from, *to)
    }

    /** The finger still down on [b], moved from [from] to each of [to] (12 samples per leg). */
    private fun fingerMove(b: RectF, from: Float, vararg to: Float) {
        var at = from
        for (target in to) {
            for (i in 1..12) {
                u.s.touch.idle(16)
                u.s.touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, xAt(b, at + (target - at) * i / 12f), b.centerY()))
            }
            at = target
        }
        settle(2)
    }

    private fun fingerUp(b: RectF, at: Float) {
        u.s.touch.idle(16)
        u.s.touch.send(MotionEvent.ACTION_UP, Smoke.P(0, xAt(b, at), b.centerY()))
        u.s.touch.idle(50)
        settle(4)
    }

    /** The text in the field [label]. */
    private fun fieldText(label: String): String =
        SmokeUi.field(label).node.config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    /** In the open color picker: the hex typed, then OK. */
    private fun pickHex(hex: String) {
        assertTrue("the color picker is open", SmokeUi.has("OK", exact = true))
        SmokeUi.field("Hex").type(hex)
        settle(2)
        SmokeUi.click("OK", exact = true)
        settle()
        assertFalse("the color picker closed", SmokeUi.has("OK", exact = true))
    }

    private fun assertRgb(what: String, expected: Int, actual: Int, tol: Int = 3) {
        val ok = listOf(16, 8, 0).all { abs(((expected shr it) and 0xFF) - ((actual shr it) and 0xFF)) <= tol }
        assertTrue("%s: expected #%06X, got #%08X".format(what, expected and 0xFFFFFF, actual), ok)
    }

    private fun alpha(c: Int) = c ushr 24

    private fun assertNear(what: String, want: Int, got: Int, tol: Int) = assertTrue("$what: want $want ± $tol, got $got", abs(want - got) <= tol)

    private fun flow(ui: Qa17ArrayUi) {
        u = ui
        u.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
        val c = u.c
        u.seed(c.activeLayer, 40f, 40f, 80f, 80f, LIGHT)
        u.seed(c.activeLayer, 50f, 50f, 70f, 70f, DARK)
        c.setSelection(u.rectSelection(36f, 36f, 84f, 84f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        layer = c.activeLayer
        tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
        assertNotNull("a live array", layer.array)
        val source = sourcePixels()
        assertEquals("the source's ring", LIGHT, source[5 * 40 + 5])
        u.showArraySheet()

        // ------------------------------------------------------------ Count 5 (Placement)
        u.typeField("Count", "5")
        u.settleRenders("count 5")
        assertEquals(5, layer.array!!.spec.count)
        assertNull("no look yet", look)
        val ring = 45f to 60f      // the light ring, left of the dark centre
        val centre = 60f to 60f    // the dark centre
        for (k in 1..4) assertEquals("copy $k plain", LIGHT, px(onCopy(k, ring.first, ring.second)))

        // ------------------------------------------------------------ End opacity, by a finger on its slider
        u.fingerPress(ArrayLabels18.LOOK, 44f)
        assertNotNull("the Look tab", u.s.tagged(V18Tags.ARRAY_LOOK))
        val n0 = u.steps()
        val endOpacity = slider(ArrayLabels18.END_OPACITY)
        fingerDrag(endOpacity, 1f, 0.5f)
        assertEquals("mid-drag: nothing recorded yet", n0, u.steps())
        val dragged = tool.previewSpec?.look?.opacityEnd
        assertTrue("mid-drag: the preview follows the finger ($dragged)", dragged != null && dragged < 0.75f)
        fingerUp(endOpacity, 0.5f)
        u.settleRenders("End opacity dragged")
        assertEquals("the drag: one step", n0 + 1, u.steps())
        assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
        val opacityEnd = look!!.opacityEnd
        assertTrue("End opacity dragged to about half: $opacityEnd", opacityEnd in 0.3f..0.7f)
        assertNear("the last copy at the dragged opacity", (opacityEnd * 255).roundToInt(), alpha(px(onCopy(4, ring.first, ring.second))), 2)
        lookStep("End opacity typed") { u.typeField(ArrayLabels18.END_OPACITY, "40") }
        assertEquals(0.4f, look!!.opacityEnd, 1e-4f)

        // ------------------------------------------------------------ Pace: each chip's curve, read off the copies
        val paces = listOf(
            ArrayLabels18.SPEEDS_UP to ArrayEasing.EASE_IN, ArrayLabels18.SLOWS_DOWN to ArrayEasing.EASE_OUT,
            ArrayLabels18.S_CURVE to ArrayEasing.EASE_IN_OUT, ArrayLabels18.EVEN to ArrayEasing.LINEAR,
        )
        val seen = mutableListOf<String>()
        for ((label, easing) in paces) {
            lookStep(label) { u.fingerPress(label) }
            assertEquals(label, easing, look!!.easing)
            for (k in 2..3) {
                val t = ArrayInstances.ease(easing, (k - 1) / 3f)
                val want = ((1f - 0.6f * t) * 255f).roundToInt()
                val got = px(onCopy(k, ring.first, ring.second))
                assertNear("$label: copy $k's opacity", want, alpha(got), 2)
                assertRgb("$label: copy $k keeps its color", LIGHT, got, 2)
                seen += "$label k$k a=${alpha(got)}"
            }
        }
        println("Qa18LookArrayPixels paces: $seen")
        assertArrayEquals("the source keeps its pixels (paces)", source, sourcePixels())

        // ------------------------------------------------------------ End size and End turn
        lookStep(ArrayLabels18.END_SIZE) { u.typeField(ArrayLabels18.END_SIZE, "50") }
        val c4 = onCopy(4, centre.first, centre.second)
        val half = inkBox(c4.first, c4.second, 18)
        assertEquals("the last copy at half size: $half", 20f, half.width().toFloat(), 2f)
        val c1x = onCopy(1, 60f, 60f).first
        assertNear("copy 1 keeps its size (its column's ink)", 40, (20 until 100).count { y -> layer.bitmap.getPixel(c1x, y) ushr 24 > 8 }, 1)
        lookStep(ArrayLabels18.END_TURN) { u.typeField(ArrayLabels18.END_TURN, "45") }
        val turned = inkBox(c4.first, c4.second, 18)
        assertEquals("the last copy turned 45°: its ink box grows by √2: $turned", 28.3f, turned.width().toFloat(), 3f)
        assertArrayEquals("the source keeps its pixels (size, turn)", source, sourcePixels())

        // ------------------------------------------------------------ A 3-stop tint built with a finger on the bar
        u.intoView(GRADIENT) { described(GRADIENT).node }
        val bar = described(GRADIENT).bounds
        val barY = bar.top + 17f * u.s.density   // the bar: the top 34 dp (stops in the lane under it)
        lookStep("a stop added on the bar") {
            u.s.touch.idle(300)
            u.s.touch.tap(bar.center.x, barY)
            settle(4)
        }
        val added = look!!.tint
        assertEquals("three stops: $added", 3, added.size)
        assertEquals("the new stop where the finger was", 0.5f, added[1].offset, 0.02f)
        assertEquals("colors chosen at strength 0 show at 100 %", 1f, look!!.tintStrength, 1e-4f)
        // A tap on the new stop opens "Stop color".
        u.s.touch.idle(400)
        u.s.touch.tap(bar.center.x, barY)
        settle(4)
        assertTrue("the stop's color picker", SmokeUi.has("Stop color", exact = true))
        lookStep("the stop recolored") { pickHex(GREEN_HEX) }
        val stops = look!!.tint
        assertEquals("the middle stop green: $stops", 0xFF000000.toInt() or GREEN_HEX.toInt(16), stops[1].color)
        assertEquals("the ends kept", listOf(added[0], added[2]), listOf(stops[0], stops[2]))
        lookStep(ArrayLabels18.TINT_STRENGTH) { u.typeField(ArrayLabels18.TINT_STRENGTH, "60") }
        assertEquals(0.6f, look!!.tintStrength, 1e-4f)
        assertArrayEquals("the source keeps its pixels (tint)", source, sourcePixels())
        assertEquals("the source untinted", LIGHT, px(ring.first.roundToInt() to ring.second.roundToInt()))
        // Copy 1 (t = 0): the ring tinted toward the first stop, opaque.
        val c1 = px(onCopy(1, ring.first, ring.second))
        assertRgb("copy 1's ring", ArrayTint.apply(LIGHT, ArrayTint.sample(stops, 0f), 0.6f), c1)
        assertEquals("copy 1 opaque", 255, alpha(c1))
        // Copy 2 (t = 1/3): its dark centre tinted toward the stop at 1/3, at 80 %.
        val c2 = px(onCopy(2, centre.first, centre.second))
        assertRgb("copy 2's centre", ArrayTint.apply(DARK, ArrayTint.sample(stops, 1f / 3f), 0.6f), c2)
        assertNear("copy 2 at 80 %", 204, alpha(c2), 2)
        // Copy 3 (t = 2/3, near green): its shading kept: the centre darker than its ring.
        val c3ring = px(onCopy(3, ring.first, ring.second))
        val c3centre = px(onCopy(3, centre.first, centre.second))
        assertTrue("copy 3 keeps its shading: centre #%08X, ring #%08X".format(c3centre, c3ring), luma(c3centre) < luma(c3ring) - 40)
        assertRgb("copy 3's centre", ArrayTint.apply(DARK, ArrayTint.sample(stops, 2f / 3f), 0.6f), c3centre)
        val shotLayer = saveLayer("look-array-pixels.png")
        val shotScreen = save(IbisShots.capture(), "look-array-pixels-screen.png")
        println("Qa18LookArrayPixels PNGs: $shotLayer, $shotScreen")

        // ------------------------------------------------------------ Two-finger undo, three-finger redo
        val tinted = Qa17ArrayUi.pixels(layer.bitmap)
        val atSixty = look
        u.ui.twoFingerUndo()
        u.settleRenders("undo")
        assertEquals("undo: 100 % again", 1f, look!!.tintStrength, 1e-4f)
        assertRgb("undo: copy 1 fully tinted", ArrayTint.apply(LIGHT, ArrayTint.sample(stops, 0f), 1f), px(onCopy(1, ring.first, ring.second)))
        u.ui.threeFingerRedo()
        u.settleRenders("redo")
        assertEquals("redo: 60 % again", atSixty, look)
        assertArrayEquals("redo: the same pixels", tinted, Qa17ArrayUi.pixels(layer.bitmap))

        // ------------------------------------------------------------ Closed and reopened: the values show
        SmokeUi.click("Close", exact = true)
        assertNull(u.s.tagged(V17Tags.ARRAY_SHEET))
        u.showArraySheet()
        assertNotNull("the Look tab again", u.s.tagged(V18Tags.ARRAY_LOOK))
        for ((label, v) in listOf(
            ArrayLabels18.END_OPACITY to "40", ArrayLabels18.END_SIZE to "50", ArrayLabels18.END_TURN to "45", ArrayLabels18.TINT_STRENGTH to "60",
            ArrayLabels18.START_OPACITY to "100", ArrayLabels18.START_SIZE to "100", ArrayLabels18.START_TURN to "0",
        )) {
            u.intoView(label) { SmokeUi.field(label).node }
            assertTrue("\"$label\" shows $v: \"${fieldText(label)}\"", fieldText(label).startsWith(v))
        }

        // ------------------------------------------------------------ Saved and reopened
        val app = RuntimeEnvironment.getApplication()
        runBlocking { ProjectRepository(app).save(c.doc, null) }
        val loaded = runBlocking { ProjectRepository(app).load(c.doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val twin = loaded.layers[c.doc.indexOf(layer)]
        assertEquals("reopened: the spec, its look included", layer.array!!.spec, twin.array!!.spec)
        assertArrayEquals("reopened: the same pixels", Qa17ArrayUi.pixels(layer.bitmap), Qa17ArrayUi.pixels(twin.bitmap))

        // ------------------------------------------------------------ The draft rule: 70 copies, a finger held on a slider
        u.fingerPress(ArrayLabels18.PLACEMENT, 44f)
        u.typeField("Count", "70")
        u.settleRenders("count 70")
        assertEquals(70, layer.array!!.spec.count)
        u.fingerPress(ArrayLabels18.LOOK, 44f)
        // The ring's top-left corner: off the guide's arrow (drawn along y = 60 to copy 1's centre).
        val corner = onCopy(1, 45f, 45f)
        val ringOnScreen = u.s.screen(corner.first.toFloat(), corner.second.toFloat())
        assertTrue("copy 1's ring on the canvas, not under the sheet", u.onCanvas(corner.first.toFloat(), corner.second.toFloat()))
        fun screenAt(shot: Bitmap) = shot.getPixel(ringOnScreen.first.roundToInt(), ringOnScreen.second.roundToInt())
        val tintedOne = ArrayTint.apply(LIGHT, ArrayTint.sample(stops, 0f), 0.6f)
        assertRgb("before the drag: copy 1 tinted on screen", tintedOne, screenAt(IbisShots.capture()), 6)
        val endOpacity2 = slider(ArrayLabels18.END_OPACITY)
        fingerDrag(endOpacity2, 0.4f, 0.3f)
        assertTrue("70 copies, a tinted look, a finger on a slider: a draft", tool.previewIsDraft)
        val draftShot = IbisShots.capture()
        assertRgb("the draft: copy 1 untinted on screen", LIGHT, screenAt(draftShot), 6)
        save(draftShot, "look-array-pixels-draft-screen.png")
        fingerUp(endOpacity2, 0.3f)
        u.settleRenders("the draft released")
        assertFalse("released: no draft", tool.previewIsDraft)
        assertRgb("released: copy 1 tinted again on screen", tintedOne, screenAt(IbisShots.capture()), 6)
        u.ui.twoFingerUndo()
        u.settleRenders("undo the drag")
        u.ui.twoFingerUndo()
        u.settleRenders("undo count 70")
        assertEquals("two undos: Count 5", 5, layer.array!!.spec.count)
        assertEquals(atSixty, look)

        // ------------------------------------------------------------ Apply array: one step, the same pixels
        val live = Qa17ArrayUi.pixels(layer.bitmap)
        val beforeApply = u.steps()
        u.fingerPress(ArrayLabels.APPLY)
        u.settleRenders("apply")
        assertNull("applied: no live array", layer.array)
        assertEquals("apply: one step", beforeApply + 1, u.steps())
        assertEquals(ArrayLabels.APPLY, c.undoManager.undoLabel)
        assertArrayEquals("applied: the pixels as drawn", live, Qa17ArrayUi.pixels(layer.bitmap))
        u.ui.twoFingerUndo()
        u.settleRenders("undo apply")
        assertEquals("undo: the live array with its look", atSixty, look)
        assertArrayEquals(live, Qa17ArrayUi.pixels(layer.bitmap))

        // ------------------------------------------------------------ Tint strength through 0 and back keeps the colors
        u.showArraySheet()
        if (u.s.tagged(V18Tags.ARRAY_LOOK) == null) u.fingerPress(ArrayLabels18.LOOK, 44f)
        lookStep(ArrayLabels18.RESET_LOOK) { u.fingerPress(ArrayLabels18.RESET_LOOK) }
        assertNull(look)
        val sunset = GradientEditing.presets.first { it.first == SUNSET }.second.map { VStop(it.position, it.color) }
        lookStep(SUNSET) { u.press(SUNSET) }
        assertEquals("a tint-only look: Sunset at 100 %", sunset, look!!.tint)
        val strength = slider(ArrayLabels18.TINT_STRENGTH)
        // A finger past the left end (0 %: no tint at all) and back to the middle, in one drag.
        fingerDrag(strength, 1f, -0.3f)
        assertEquals("the finger at the left end: 0 %", 0f, tool.lookBase().tintStrength, 0f)
        assertNotNull("mid-drag: a preview", tool.previewSpec)
        assertNull("0 %: the preview shows no look", tool.previewSpec?.look)
        fingerMove(strength, -0.3f, 0.5f)
        fingerUp(strength, 0.5f)
        u.settleRenders("strength through 0")
        assertTrue("the strength back to about half: ${look?.tintStrength}", (look?.tintStrength ?: 0f) in 0.3f..0.7f)
        assertEquals("through 0 and back in one drag: the colors kept", sunset, look!!.tint)
        // Typed: 0 (no tint, no look), then 50.
        u.typeField(ArrayLabels18.TINT_STRENGTH, "0")
        u.settleRenders("strength 0")
        assertNull("0 %: no look", look)
        u.typeField(ArrayLabels18.TINT_STRENGTH, "50")
        u.settleRenders("strength 50")
        assertEquals("0 then 50: the colors kept", sunset, look!!.tint)
        assertEquals(0.5f, look!!.tintStrength, 1e-4f)
        // "Reset look" forgets them: a strength set with no colors takes the default warm-to-cool stops.
        lookStep(ArrayLabels18.RESET_LOOK) { u.fingerPress(ArrayLabels18.RESET_LOOK) }
        u.typeField(ArrayLabels18.TINT_STRENGTH, "50")
        u.settleRenders("strength 50 after a reset")
        assertEquals("after Reset look: the default colors", listOf(VStop(0f, 0xFFFF6A3D.toInt()), VStop(1f, 0xFF3D7BFF.toInt())), look!!.tint)
        Smoke.assertQuiet(c, "the selection array's look")
    }

    /** The element described exactly [d]. */
    private fun described(d: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true
    }

    private fun luma(c: Int) = (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)).roundToInt()

    /** The array layer over white, the copies' row (0..260 × 20..100), at 2x. */
    private fun saveLayer(name: String): String? {
        val crop = Bitmap.createBitmap(260, 80, Bitmap.Config.ARGB_8888)
        val cv = Canvas(crop)
        cv.drawColor(0xFFFFFFFF.toInt())
        cv.drawBitmap(layer.bitmap, Rect(0, 20, 260, 100), Rect(0, 0, 260, 80), Paint())
        return save(Bitmap.createScaledBitmap(crop, 520, 160, false), name)
    }

    private companion object {
        const val LIGHT = 0xFFE6E6E6.toInt()
        const val DARK = 0xFF707070.toInt()
        const val GREEN_HEX = "30C040"
        const val GRADIENT = "Gradient editor"
        const val SUNSET = "Sunset"

        private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

        /** Writes [b] as [file] in the v1.8 QA shots folder when this machine has it; the path, or null. */
        fun save(b: Bitmap, file: String): String? {
            if (SHOTS.parentFile?.isDirectory != true) return null
            SHOTS.mkdirs()
            val f = File(SHOTS, file)
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }
    }
}
