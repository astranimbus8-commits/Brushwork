package com.brushwork.paint.qa17

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryHandle
import com.brushwork.paint.assist.SymmetryHandles
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v1.7 final QA, layers cluster (item 18, design §3.18; device checklist §6.4 row 12): the five
 * symmetry rulers on a raster layer, set the way the user sets them on the phone (the Symmetry
 * tool's ruler chips, a finger on the canvas handles, the "Label value ▾" chips typed into,
 * "Reset symmetry", "Done"), then a Brush stroke on a clear layer and an Eraser and a Smudge stroke
 * on a checkered one, each by finger.
 *
 * Every stroke is checked against copies worked out here (reflections, rotations and grid
 * translations by their own formulas; the perspective array's homographies from
 * `SymmetryMaps`): each copy that lands on the canvas shows the tool's effect, and nothing
 * changed anywhere else. Setting a ruler is never a step; each stroke is one.
 */
internal class Qa17LayersSymmetry(private val h: ChromeHarness) {
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private var u: Qa17LayersUi? = null
    private val l: Qa17LayersUi get() = u!!
    private val c: EditorController get() = s.c

    fun release() {
        u?.release()
        u = null
    }

    // ================================================================== the picture

    /** A checkered Layer 1 (for the eraser and the smudge) under a clear Layer 2 (for the brush). */
    private fun document(): Document = Smoke.document(N, N, layers = 2, whiteBottom = false).also { d ->
        val px = IntArray(N * N) { i -> if (((i % N) / 8 + (i / N) / 8) % 2 == 0) LIGHT else DARK }
        d.layers[0].bitmap.setPixels(px, 0, N, 0, 0, N, N)
        d.layers[0].markChanged()
    }

    private fun editor() {
        u?.release()
        s = h.editor(document()) { it.snapping.enabled = false }
        // The tools are made by now (they would bring their own presets back).
        c.color = INK
        c.brush = c.brush.copy(size = 6f, opacity = 1f, pressureSize = false)
        c.eraser = c.eraser.copy(size = 10f, opacity = 1f, pressureSize = false)
        c.smudgeBrush = c.smudgeBrush.copy(size = 14f)
        u = Qa17LayersUi(s)
        ui = l.ui
        settle()
        assertEquals(392f, s.widthDp, 1f)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    // ================================================================== fingers

    /** [label] brought wholly into view in its strip, the strip at rest, then tapped by finger. */
    private fun press(label: String) {
        ui.reach(label, 32f)
        Smoke.pump(600)
        settle()
        Finger.tap(s, label)
    }

    /** A "Label value ▾" chip: tapped, [value] typed into its [field] (Done), the menu closed. */
    private fun chip(label: String, field: String, value: String) {
        press(label)
        SmokeUi.typeAndDone(field, value)
        if (runCatching { SmokeUi.field(field) }.isSuccess) Finger.back()
        settle()
    }

    /** Document px per dp at the current zoom (the handles' size and the angle knob's arm are dp). */
    private fun docPerDp(): Float = c.viewTransform.let { it.dp(1f) / it.zoom }

    private fun handleAt(handle: SymmetryHandle) =
        SymmetryHandles.position(c.symmetry.sanitized(), N, N, handle, docPerDp())

    /** A finger on [handle] dragged by ([dx], [dy]) document px. */
    private fun drag(handle: SymmetryHandle, dx: Float, dy: Float) {
        val p = handleAt(handle)
        assertEquals("$handle is under the finger", handle, SymmetryHandles.hit(c.symmetry.sanitized(), N, N, p.x, p.y, docPerDp()))
        ui.stroke(p.x to p.y, p.x + dx / 2f to p.y + dy / 2f, p.x + dx to p.y + dy)
    }

    // ================================================================== maps worked out here

    private fun reflect(cx: Float, cy: Float, deg: Double): FloatArray {
        val a = Math.toRadians(2 * deg)
        val co = cos(a).toFloat(); val si = sin(a).toFloat()
        // p' = c + [[cos 2θ, sin 2θ], [sin 2θ, −cos 2θ]] (p − c): the line through c along θ.
        return floatArrayOf(co, si, cx - co * cx - si * cy, si, -co, cy - si * cx + co * cy, 0f, 0f, 1f)
    }

    private fun rotate(cx: Float, cy: Float, deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        val co = cos(a).toFloat(); val si = sin(a).toFloat()
        return floatArrayOf(co, -si, cx - co * cx + si * cy, si, co, cy - si * cx - co * cy, 0f, 0f, 1f)
    }

    private fun shift(tx: Float, ty: Float) = floatArrayOf(1f, 0f, tx, 0f, 1f, ty, 0f, 0f, 1f)

    private fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    // ================================================================== checks

    /** Points every pixel along the polyline [p]. */
    private fun dense(p: List<Pair<Float, Float>>): List<Pair<Float, Float>> = buildList {
        for (i in 1 until p.size) {
            val (ax, ay) = p[i - 1]; val (bx, by) = p[i]
            val n = maxOf(1, hypot(bx - ax, by - ay).toInt())
            for (k in 0..n) add(ax + (bx - ax) * k / n to ay + (by - ay) * k / n)
        }
    }

    /**
     * The stroke [stroke] repeated by [maps]: every copy wholly on the canvas (and not shrunk below
     * half size) shows [effect] within [reach] px of its middle points, at least [minCopies] such
     * copies; and every pixel with [effect] lies within [margin] px of some copy.
     */
    private fun checkCopies(
        what: String,
        stroke: List<Pair<Float, Float>>,
        maps: List<FloatArray>,
        reach: Int,
        margin: Float,
        minCopies: Int,
        effect: (Int, Int) -> Boolean,
    ) {
        val line = dense(stroke)
        val samples = listOf(0.35f, 0.5f, 0.65f).map { line[(it * (line.size - 1)).toInt()] }
        fun near(x: Float, y: Float): Boolean {
            val cx = x.toInt(); val cy = y.toInt()
            for (yy in cy - reach..cy + reach) for (xx in cx - reach..cx + reach) {
                if (xx in 0 until N && yy in 0 until N && effect(xx, yy)) return true
            }
            return false
        }
        var checked = 0
        for ((k, m) in maps.withIndex()) {
            val at = samples.map { apply(m, it.first, it.second) }
            val inside = at.all { (x, y) -> x >= reach + 1 && y >= reach + 1 && x < N - reach - 1 && y < N - reach - 1 }
            val (ax, ay) = apply(m, samples[1].first, samples[1].second)
            val (bx, by) = apply(m, samples[1].first + 1f, samples[1].second)
            if (!inside || hypot(bx - ax, by - ay) < 0.5f) continue
            checked++
            for ((x, y) in at) assertTrue("$what: copy $k at (${x.toInt()}, ${y.toInt()})", near(x, y))
        }
        assertTrue("$what: $checked copies on the canvas, want ≥ $minCopies", checked >= minCopies)
        val copies = maps.flatMap { m -> line.map { apply(m, it.first, it.second) } }
        var stray = 0
        var first = ""
        for (y in 0 until N) for (x in 0 until N) if (effect(x, y)) {
            if (copies.none { (px, py) -> hypot(px - x, py - y) <= margin }) {
                if (stray++ == 0) first = "($x, $y)"
            }
        }
        if (stray > 0) {
            val px = IntArray(N * N) { i -> if (effect(i % N, i / N)) 0xFF000000.toInt() else -1 }
            for (m in maps) for ((x, y) in line.map { apply(m, it.first, it.second) }) {
                val xi = x.toInt(); val yi = y.toInt()
                if (xi in 0 until N && yi in 0 until N) px[yi * N + xi] = 0xFFFF0000.toInt()
            }
            Qa17LayersShots.saveGrid("debug-" + what.replace(Regex("[^a-z]+"), "-"), listOf(px), N, N, 1)
        }
        assertEquals("$what: pixels changed away from every copy (first $first)", 0, stray)
    }

    /**
     * Brush on Layer 2, then Eraser and Smudge on Layer 1, each a finger stroke and one step,
     * each checked against [maps] (for a stroke starting at a point: the array and perspective
     * copies depend on it).
     */
    private fun strokes(
        name: String,
        brush: List<Pair<Float, Float>>,
        eraser: List<Pair<Float, Float>>,
        smudge: List<Pair<Float, Float>>,
        minCopies: Int,
        marginScale: Float = 1f,
        maps: (Pair<Float, Float>) -> List<FloatArray>,
    ) {
        val (l1, l2) = c.doc.layers
        assertEquals("the brush", ToolId.BRUSH, c.activeToolId)
        l.captions()
        val before = pixels(l1.bitmap)

        // Brush on the clear layer.
        if (c.activeLayer !== l2) { l.pick(l2); l.closeLayers() }
        assertEquals("the brush's size", 6f, c.brush.size, 0f)
        l.oneStep("$name: brush", "Brush") { ui.stroke(*brush.toTypedArray()) }
        val ink = pixels(l2.bitmap)
        checkCopies("$name: brush", brush, maps(brush[0]), 3, 6f * marginScale, minCopies) { x, y -> ink[y * N + x] ushr 24 >= 200 }
        assertArrayEquals("$name: Layer 1 untouched by the brush", before, pixels(l1.bitmap))

        // Eraser on the checkered layer.
        l.pick(l1)
        l.closeLayers()
        ui.tool("Eraser")
        assertEquals("the eraser's size", 10f, c.eraser.size, 0f)
        l.oneStep("$name: eraser", "Eraser") { ui.stroke(*eraser.toTypedArray()) }
        val erased = pixels(l1.bitmap)
        checkCopies("$name: eraser", eraser, maps(eraser[0]), 4, 8f * marginScale, minCopies) { x, y -> erased[y * N + x] ushr 24 < 250 }
        val holes = erased.count { it ushr 24 <= 55 }
        assertTrue("$name: the eraser cleared pixels ($holes)", holes > 100)

        // Smudge on the checkered layer, away from the erased copies.
        ui.tool("Smudge")
        assertEquals("the smudge's size", 14f, c.smudgeBrush.size, 0f)
        l.oneStep("$name: smudge", "Smudge") { ui.stroke(*smudge.toTypedArray()) }
        val smudged = pixels(l1.bitmap)
        checkCopies("$name: smudge", smudge, maps(smudge[0]), 6, 13f * marginScale, minCopies) { x, y -> smudged[y * N + x] != erased[y * N + x] }
        assertEquals("$name: no caption", emptyList<String>(), l.captions())
        assertArrayEquals("$name: the brush layer untouched by the eraser and smudge", ink, pixels(l2.bitmap))
        ui.tool("Brush")
        Qa17LayersShots.shoot(c, "symmetry-$name")
    }

    /** The Symmetry tool picked and [type] chosen by its chip; settings changes are no step. */
    private fun ruler(type: SymmetryType) {
        ui.tool(SymmetryLabels.TOOL)
        assertEquals("picking the tool turns the mirror on", SymmetryType.MIRROR, c.symmetry.type)
        for (t in SymmetryType.entries) assertTrue("the chip \"${t.label}\"", has(t.label, exact = true))
        if (type != SymmetryType.MIRROR) press(type.label)
        assertEquals(type, c.symmetry.type)
    }

    private fun done() {
        press("Done")
        assertEquals("Done: back to the brush", ToolId.BRUSH, c.activeToolId)
    }

    /** [block] (setting the ruler) is no step and changes no pixel. */
    private fun setting(what: String, block: () -> Unit) = l.noStep(what, block)

    /** Points [r0]..[r1] px from [cx], [cy] along [deg] (y down). */
    private fun radial(cx: Float, cy: Float, deg: Double, r0: Float, r1: Float): List<Pair<Float, Float>> {
        val a = Math.toRadians(deg)
        val ux = cos(a).toFloat(); val uy = sin(a).toFloat()
        return listOf(cx + ux * r0 to cy + uy * r0, cx + ux * (r0 + r1) / 2f to cy + uy * (r0 + r1) / 2f, cx + ux * r1 to cy + uy * r1)
    }

    private fun across(x0: Float, x1: Float, y: Float) = listOf(x0 to y, (x0 + x1) / 2f to y, x1 to y)

    // ================================================================== the five rulers

    fun mirror() {
        editor()
        setting("the mirror ruler") {
            ruler(SymmetryType.MIRROR)
            assertTrue("its angle chip", has("Angle 0°", exact = true))
            // The centre handle dragged 20 px left; then "Angle" typed: 30°.
            drag(SymmetryHandle.CENTER, -20f, 0f)
            assertEquals(100f, c.symmetry.centerX, 0.5f)
            assertEquals(120f, c.symmetry.centerY, 0.5f)
            chip("Angle 0°", "Angle", "30")
            assertEquals("shown 30° is stored 120°", 120f, c.symmetry.angleDeg, 0.01f)
            assertTrue(has("Angle 30°", exact = true))
            done()
        }
        val sym = c.symmetry
        val m = listOf(SymmetryMaps.identity(), reflect(sym.centerX, sym.centerY, sym.angleDeg.toDouble()))
        strokes(
            "mirror",
            brush = listOf(25f to 60f, 40f to 55f, 55f to 50f),
            eraser = listOf(25f to 110f, 40f to 105f, 55f to 100f),
            smudge = listOf(20f to 160f, 35f to 155f, 50f to 150f),
            minCopies = 2,
        ) { m }
    }

    fun kaleidoscope() {
        editor()
        setting("the kaleidoscope ruler") {
            ruler(SymmetryType.KALEIDOSCOPE)
            chip("Divisions 6", SymmetryLabels.DIVISIONS, "4")
            assertEquals(4, c.symmetry.divisions)
            // The angle knob (straight down at first) dragged round to 45°.
            val knob = handleAt(SymmetryHandle.ANGLE)
            val arm = hypot(knob.x - 120f, knob.y - 120f)
            val to = 120f + arm * cos(Math.toRadians(45.0)).toFloat() to 120f + arm * sin(Math.toRadians(45.0)).toFloat()
            drag(SymmetryHandle.ANGLE, to.first - knob.x, to.second - knob.y)
            assertEquals("the axes turned to 45°", 45f, c.symmetry.angleDeg, 0.01f)
            assertTrue("the chip shows it: ${SmokeUi.shown().filter { it.startsWith("Angle") }}", has("Angle -45°", exact = true))
            done()
        }
        val sym = c.symmetry
        val m = (0 until 4).flatMap { k ->
            listOf(rotate(120f, 120f, 90.0 * k), reflect(120f, 120f, sym.angleDeg + 45.0 * k))
        }
        strokes(
            "kaleidoscope",
            brush = radial(120f, 120f, -22.5, 30f, 50f),
            eraser = radial(120f, 120f, -22.5, 55f, 75f),
            smudge = radial(120f, 120f, -22.5, 85f, 105f),
            minCopies = 8,
        ) { m }
    }

    fun rotation() {
        editor()
        setting("the rotation ruler") {
            ruler(SymmetryType.ROTATION)
            chip("Divisions 6", SymmetryLabels.DIVISIONS, "5")
            assertEquals(5, c.symmetry.divisions)
            drag(SymmetryHandle.CENTER, 10f, -10f)
            assertEquals(130f, c.symmetry.centerX, 0.5f)
            assertEquals(110f, c.symmetry.centerY, 0.5f)
            done()
        }
        val m = (0 until 5).map { k -> rotate(130f, 110f, 72.0 * k) }
        strokes(
            "rotation",
            brush = radial(130f, 110f, -90.0, 25f, 45f),
            eraser = radial(130f, 110f, -90.0, 50f, 65f),
            smudge = radial(130f, 110f, -90.0, 75f, 92f),
            minCopies = 5,
        ) { m }
        // "Reset symmetry": the defaults again, the ruler kept; no step, no pixel.
        setting("Reset symmetry") {
            ui.tool(SymmetryLabels.TOOL)
            assertTrue(SmokeUi.isEnabled(SymmetryLabels.RESET))
            press(SymmetryLabels.RESET)
            assertEquals(SymmetrySettings(type = SymmetryType.ROTATION), c.symmetry)
            assertTrue(has("Divisions 6", exact = true))
            assertFalse("nothing more to reset", SmokeUi.isEnabled(SymmetryLabels.RESET))
            done()
        }
    }

    fun array() {
        editor()
        setting("the array ruler") {
            ruler(SymmetryType.ARRAY)
            chip("Spacing X 300 px", "Spacing X", "80")
            chip("Spacing Y 300 px", "Spacing Y", "60")
            assertEquals(80f, c.symmetry.spacingX, 0.01f)
            assertEquals(60f, c.symmetry.spacingY, 0.01f)
            assertTrue(has("Spacing X 80 px", exact = true) && has("Spacing Y 60 px", exact = true))
            // The grid's corner handle dragged by (10, 8).
            drag(SymmetryHandle.CENTER, 10f, 8f)
            assertEquals(130f, c.symmetry.centerX, 0.5f)
            assertEquals(128f, c.symmetry.centerY, 0.5f)
            done()
        }
        // Upright cells 80 × 60: every translation by whole cells.
        val m = (-4..4).flatMap { j -> (-4..4).map { i -> shift(80f * i, 60f * j) } }
        strokes(
            "array",
            brush = across(58f, 78f, 76f),
            eraser = across(58f, 78f, 98f),
            smudge = across(90f, 118f, 113f),
            minCopies = 9,
        ) { m }
    }

    fun perspectiveArray() {
        editor()
        setting("the perspective array ruler") {
            ruler(SymmetryType.PERSPECTIVE_ARRAY)
            val tr = handleAt(SymmetryHandle.CORNER_TR)
            drag(SymmetryHandle.CORNER_TR, 10f, -6f)
            val q = SymmetryMaps.quad(c.symmetry, N, N)
            assertEquals(tr.x + 10f, q[2], 0.5f)
            assertEquals(tr.y - 6f, q[3], 0.5f)
            assertTrue("still a convex cell", SymmetrySettings.isConvexQuad(c.symmetry.quad))
            done()
        }
        val sym = c.symmetry
        strokes(
            "perspective-array",
            brush = across(110f, 130f, 102f),
            eraser = across(108f, 132f, 118f),
            smudge = across(106f, 134f, 134f),
            minCopies = 4,
            marginScale = 3f,
        ) { start -> SymmetryMaps.transforms(sym, N, N, start.first, start.second) }
    }

    companion object {
        const val N = 240
        val LIGHT = 0xFFF0E0C0.toInt()
        val DARK = 0xFF4070A0.toInt()
        val INK = 0xFFC02040.toInt()

        fun run() {
            ShadowLog.stream = null
            SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 90_000)
            val h = ChromeHarness()
            val t = Qa17LayersSymmetry(h)
            val times = mutableListOf<String>()
            fun timed(name: String, block: () -> Unit) {
                val t0 = System.nanoTime()
                h.section(name, block)
                times += "$name ${(System.nanoTime() - t0) / 1_000_000} ms"
            }
            timed("mirror") { t.mirror() }
            timed("kaleidoscope") { t.kaleidoscope() }
            timed("rotation") { t.rotation() }
            timed("array") { t.array() }
            timed("perspective array") { t.perspectiveArray() }
            println("Qa17LayersSymmetry times: $times")
            t.release()
            dog.interrupt()
            h.finish()
        }
    }
}

/** Item 18 on the user's phone (392 dp): the five symmetry rulers with brush, eraser and smudge, by fingers. */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.layerssymmetrysandbox"])
class Qa17LayersSymmetryUiTest {
    @Test
    fun theFiveRulersRepeatBrushEraserAndSmudgeByFingersAt392dp() = Qa17LayersSymmetry.run()
}
