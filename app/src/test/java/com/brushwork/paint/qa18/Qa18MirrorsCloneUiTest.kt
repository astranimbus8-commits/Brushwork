package com.brushwork.paint.qa18

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18MirrorsKit.channelsClose
import com.brushwork.paint.qa18.Qa18MirrorsKit.divisions
import com.brushwork.paint.qa18.Qa18MirrorsKit.inverse
import com.brushwork.paint.qa18.Qa18MirrorsKit.isIdentity
import com.brushwork.paint.qa18.Qa18MirrorsKit.localScale
import com.brushwork.paint.qa18.Qa18MirrorsKit.luminance
import com.brushwork.paint.qa18.Qa18MirrorsKit.map
import com.brushwork.paint.qa18.Qa18MirrorsKit.pixels
import com.brushwork.paint.qa18.Qa18MirrorsKit.spacingX
import com.brushwork.paint.qa18.Qa18MirrorsKit.spacingY
import com.brushwork.paint.qa18.Qa18MirrorsKit.toSegment
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneOffset
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.ObjectCopies
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.floor

/**
 * v1.8 final QA, item 1 (design §3.1 D1, D4): the clone stamp under each symmetry ruler on the
 * user's phone (392 dp), every step by a finger. The ruler goes on through the Symmetry tool (its
 * ruler dropdown, a value's field, Done); the layer holds a gradient (each pixel says where it is)
 * with a black "R" at the source spot and at each of its mirrored spots; Clone stamp, "Set source",
 * a tap on the R, then one finger stroke elsewhere. Then:
 * - one stroke is one step, and the top row's Undo takes the whole of it back;
 * - every copy is a true mirror: each pixel of copy k (map M_k) shows the layer as it was at
 *   M_k(M_k⁻¹(p) − o), o the stroke's whole-pixel offset (copy k samples through its own matrix),
 *   so each copy shows its own, mirrored R; nothing is painted outside the copies;
 * - more than 32 copies (Kaleidoscope, Divisions 17: 34) refuses the stroke with its message, no
 *   pixel and no step; Rotation 32 still paints.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.mirrorsclonesandbox"])
class Qa18MirrorsCloneUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui

    private fun editor(h: ChromeHarness) {
        s = h.editor(Smoke.document(W, H, layers = 2, whiteBottom = true)) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
    }

    private fun steps() = s.c.undoManager.undoCount

    /** The clone stamp's pen (a hard round tip, full opacity and flow, no taper): what a copy shows is what it sampled. */
    private fun cloneTool(): CloneTool {
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        s.c.updatePreset(
            ToolId.CLONE,
            pen.copy(size = SIZE, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f, taperStart = 0f, taperEnd = 0f),
        )
        ui.tool("Clone stamp")
        return s.c.currentTool as CloneTool
    }

    /** "Set source", then a finger on [at]: the source sits there. */
    private fun setSource(clone: CloneTool, at: Vec2) {
        ui.reach("Set source", 32f)
        click("Set source", exact = true)
        assertTrue("Set source arms the next tap", clone.armed)
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        ui.tap(at.x, at.y)
        val src = clone.anchor.source ?: throw AssertionError("the tap set no source")
        assertEquals(at.x, src.x, 1.5f)
        assertEquals(at.y, src.y, 1.5f)
        assertFalse("disarmed", clone.armed)
    }

    /** One finger from [a] to [b] through 11 points. */
    private fun strokeAlong(a: Vec2, b: Vec2) =
        ui.stroke(*Array(11) { i -> (a.x + (b.x - a.x) * i / 10f) to (a.y + (b.y - a.y) * i / 10f) })

    private class CopyCheck(val k: Int, val checked: Int, val matched: Int, val dark: Int, val identity: Boolean, val skipped: String = "") {
        val fraction get() = if (checked == 0) 0f else matched.toFloat() / checked
        override fun toString() = "copy $k${if (identity) " (the stroke)" else ""}: $matched of $checked px match, $dark dark$skipped"
    }

    /**
     * Each copy k's core pixels (within [CORE] px of the stroke's middle part, by its own map,
     * where no other copy paints, at a scale of at least 0.6, sampling inside the canvas from a
     * smooth spot of [before]) against [before] at M_k(M_k⁻¹(p) − o).
     */
    private fun checkCopies(before: IntArray, after: IntArray, maps: List<FloatArray>, a: Vec2, b: Vec2, o: CloneOffset): List<CopyCheck> {
        val inv = maps.map { inverse(it) }
        fun px(arr: IntArray, x: Int, y: Int) = arr[y * W + x]
        fun smooth(x: Int, y: Int): Boolean {
            if (x < 1 || y < 1 || x >= W - 1 || y >= H - 1) return false
            for (sh in intArrayOf(0, 8, 16, 24)) {
                var lo = 255
                var hi = 0
                for (yy in y - 1..y + 1) for (xx in x - 1..x + 1) {
                    val v = (px(before, xx, yy) ushr sh) and 0xFF
                    lo = minOf(lo, v); hi = maxOf(hi, v)
                }
                if (hi - lo > 30) return false
            }
            return true
        }
        val out = mutableListOf<CopyCheck>()
        for ((k, m) in maps.withIndex()) {
            val corners = listOf(Vec2(a.x - 12f, a.y - 12f), Vec2(b.x + 12f, a.y - 12f), Vec2(b.x + 12f, b.y + 12f), Vec2(a.x - 12f, b.y + 12f)).map { map(m, it) }
            if (corners.any { it == null }) continue
            val l = corners.minOf { it!!.x }.toInt().coerceAtLeast(0)
            val r = corners.maxOf { it!!.x }.toInt().coerceAtMost(W - 1)
            val t = corners.minOf { it!!.y }.toInt().coerceAtLeast(0)
            val bt = corners.maxOf { it!!.y }.toInt().coerceAtMost(H - 1)
            var checked = 0
            var matched = 0
            var dark = 0
            var core = 0
            var small = 0
            var overlap = 0
            var edge = 0
            for (y in t..bt) for (x in l..r) {
                val pc = Vec2(x + 0.5f, y + 0.5f)
                val q = map(inv[k], pc) ?: continue
                val (d, along) = toSegment(q, a, b)
                if (d > CORE || along < 0.15f || along > 0.85f) continue
                core++
                if (localScale(m, q) < 0.6f) { small++; continue }
                if (inv.indices.any { j -> j != k && map(inv[j], pc)?.let { toSegment(it, a, b).first <= SIZE / 2f + 4f } == true }) { overlap++; continue }
                val src = map(m, q.x - o.dx, q.y - o.dy) ?: continue
                val sx = floor(src.x).toInt()
                val sy = floor(src.y).toInt()
                if (!smooth(sx, sy)) { edge++; continue }
                checked++
                val got = px(after, x, y)
                if (channelsClose(got, px(before, sx, sy), TOL)) matched++
                if (luminance(got) < 90) dark++
            }
            if (core > 0) out += CopyCheck(k, checked, matched, dark, isIdentity(m), " (of $core core px: $small too small, $overlap shared, $edge on an edge)")
        }
        return out
    }

    /** Pixels that changed away from every copy (none of the maps takes them near the stroke). */
    private fun stray(before: IntArray, after: IntArray, maps: List<FloatArray>, a: Vec2, b: Vec2): Int {
        val inv = maps.map { inverse(it) }
        var n = 0
        for (y in 0 until H) for (x in 0 until W) {
            if (before[y * W + x] == after[y * W + x]) continue
            val pc = Vec2(x + 0.5f, y + 0.5f)
            if (inv.none { im -> map(im, pc)?.let { toSegment(it, a, b).first <= SIZE / 2f + 6f } == true }) n++
        }
        return n
    }

    /**
     * The ruler [type] (with [values] typed) on; the lettered layer; the source set on the R at
     * [source]; one stroke [a] to [b]: one step, every copy a true mirror ([minCopies] at least
     * checked), nothing stray; rendered; the top row's Undo takes it back.
     */
    private fun cloneUnder(
        name: String,
        type: SymmetryType,
        values: List<Qa18MirrorsKit.Value>,
        a: Vec2,
        b: Vec2,
        source: Vec2,
        minCopies: Int,
        drags: List<Pair<Vec2, Vec2>> = emptyList(),
    ) {
        Qa18MirrorsKit.rulerOn(ui, type, values, drags)
        val c = s.c
        val layer = c.activeLayer
        val maps = ObjectCopies.mapsAt(c, a.x, a.y)
        val counted = CloneTool.copiesCounted(type, maps, a.x, a.y, W, H)
        println("Qa18MirrorsClone $name: ${maps.size} maps, $counted counted, ruler ${c.symmetry}")
        assertTrue("$name: the ruler gives copies", maps.size >= 2)
        // The R where the stroke will sample it, and where each copy will (as if painted under the ruler).
        val mid = Vec2(source.x + (b.x - a.x) / 2f, source.y + (b.y - a.y) / 2f)
        // (Its stem and leg cross the band the stroke samples: letter and gradient both.)
        Qa18MirrorsKit.seedLettered(c, layer, maps, Vec2(mid.x, mid.y - 10f))
        val clone = cloneTool()
        setSource(clone, source)
        val before = pixels(layer.bitmap)
        val n0 = steps()
        strokeAlong(a, b)
        assertEquals("$name: one stroke, one step", n0 + 1, steps())
        val o = clone.anchor.fixed ?: throw AssertionError("$name: the stroke fixed no offset")
        val after = pixels(layer.bitmap)
        val checks = checkCopies(before, after, maps, a, b, o)
        println("Qa18MirrorsClone $name: offset $o; ${checks.joinToString("; ")}")
        val full = checks.filter { it.checked >= 40 }
        assertTrue("$name: at least $minCopies copies checked: $checks", full.size >= minCopies)
        val own = checks.single { it.identity }
        assertTrue("$name: the stroke itself is checked: $own", own.checked >= 60)
        assertTrue("$name: the stroke copies its source exactly: $own", own.fraction >= 0.98f)
        for (ck in full) {
            assertTrue("$name: $ck samples through its own map", ck.fraction >= 0.9f)
            assertTrue("$name: $ck shows its own R", ck.dark >= 8)
        }
        assertEquals("$name: nothing painted away from the copies", 0, stray(before, after, maps, a, b))
        Qa18MirrorsKit.shoot(s, "$name-clone")
        Finger.tap(s, "Undo")
        assertEquals("$name: Undo takes one step", n0, steps())
        assertArrayEquals("$name: one Undo takes the whole stroke back", before, pixels(layer.bitmap))
        Smoke.assertQuiet(c, name)
    }

    @Test
    fun cloneStampUnderEachRulerIsATrueMirrorAndRefusesPast32Copies() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()

        h.section("Mirror") {
            editor(h)
            cloneUnder("mirror", SymmetryType.MIRROR, emptyList(), Vec2(60f, 220f), Vec2(90f, 220f), Vec2(130f, 90f), minCopies = 2)
        }
        h.section("Kaleidoscope 3") {
            editor(h)
            cloneUnder("kaleidoscope", SymmetryType.KALEIDOSCOPE, listOf(divisions("3")), Vec2(110f, 215f), Vec2(140f, 215f), Vec2(230f, 70f), minCopies = 4)
        }
        h.section("Rotation 4") {
            editor(h)
            cloneUnder("rotation", SymmetryType.ROTATION, listOf(divisions("4")), Vec2(110f, 215f), Vec2(140f, 215f), Vec2(230f, 70f), minCopies = 4)
        }
        h.section("Array 150") {
            editor(h)
            cloneUnder("array", SymmetryType.ARRAY, listOf(spacingX("150"), spacingY("150")), Vec2(20f, 270f), Vec2(50f, 270f), Vec2(70f, 170f), minCopies = 4)
        }
        h.section("Perspective array") {
            editor(h)
            // The default cell (a fifth of the shorter side) gives this canvas more than 32 copies
            // (refused, as §3.1 D4 says): the user first drags its four corners out on the canvas.
            for ((w, ht) in listOf(W to H, 1080 to 2340, 2048 to 2048, 1920 to 1080)) {
                val st = com.brushwork.paint.model.SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY)
                val m = com.brushwork.paint.assist.SymmetryMaps.transforms(st, w, ht, w / 2f, ht / 2f)
                println("Qa18MirrorsClone perspective default cell on $w x $ht: ${CloneTool.copiesCounted(SymmetryType.PERSPECTIVE_ARRAY, m, w / 2f, ht / 2f, w, ht)} copies counted at the centre")
            }
            val corners = listOf(
                Vec2(230f, 180f) to Vec2(262f, 205f), Vec2(170f, 180f) to Vec2(138f, 205f),
                Vec2(176f, 120f) to Vec2(158f, 105f), Vec2(224f, 120f) to Vec2(242f, 105f),
            )
            cloneUnder("perspective", SymmetryType.PERSPECTIVE_ARRAY, emptyList(), Vec2(170f, 190f), Vec2(200f, 190f), Vec2(200f, 150f), minCopies = 2, drags = corners)
        }
        h.section("More than 32 copies refused; 32 paint") {
            editor(h)
            val c = s.c
            Qa18MirrorsKit.rulerOn(ui, SymmetryType.KALEIDOSCOPE, listOf(divisions("17")))
            assertEquals("Divisions 17 typed", 17, c.symmetry.divisions)
            val layer = c.activeLayer
            Qa18MirrorsKit.seedLettered(c, layer, emptyList(), Vec2(230f, 70f))
            val clone = cloneTool()
            setSource(clone, Vec2(230f, 70f))
            val before = pixels(layer.bitmap)
            val n0 = steps()
            strokeAlong(Vec2(110f, 215f), Vec2(140f, 215f))
            assertTrue(
                "the message says why: ${SmokeUi.shown().take(40)}",
                Smoke.pumpUntil(10_000) { settle(1); SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY, exact = true) },
            )
            assertEquals("34 copies: no step", n0, steps())
            assertArrayEquals("34 copies: no pixel", before, pixels(layer.bitmap))
            Qa18MirrorsKit.shoot(s, "kaleidoscope17-refused")

            Qa18MirrorsKit.rulerOn(ui, SymmetryType.ROTATION, listOf(divisions("32")))
            assertEquals("Divisions 32 typed", 32, c.symmetry.divisions)
            // Done goes back to the last painting tool (the brush: the clone stamp never is one, v1.7).
            ui.tool("Clone stamp")
            assertEquals("the clone stamp again", ToolId.CLONE, c.activeToolId)
            if ((c.currentTool as CloneTool).anchor.source == null) setSource(c.currentTool as CloneTool, Vec2(230f, 70f))
            strokeAlong(Vec2(110f, 215f), Vec2(140f, 215f))
            assertEquals("32 copies paint: one step", n0 + 1, steps())
            val changed = before.indices.count { before[it] != layer.bitmap.getPixel(it % W, it / W) }
            println("Qa18MirrorsClone rotation 32: $changed px changed")
            assertTrue("32 copies paint ($changed px)", changed > 32 * 100)
            Qa18MirrorsKit.shoot(s, "rotation32-clone")
            Smoke.assertQuiet(c, "32 copies")
        }

        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300
        const val SIZE = 16f

        /** Pixels this close to the stroke's line (its own map's space) are wholly covered. */
        const val CORE = 3f

        /** Per channel: a half-pixel or bilinear difference on the gradient, not a wrong spot. */
        const val TOL = 16
    }
}
