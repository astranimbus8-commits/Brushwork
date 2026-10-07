package com.brushwork.paint.assist

import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.vector.StrokeCopies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * v1.7 (item 18, §3.18): the symmetry rulers' maps. Mirror at 90° reflects x about the centre;
 * Kaleidoscope 6 gives 12 maps forming a closed group; the array is capped at 256 with culling;
 * perspective cell (1, 0) maps the quad's left edge onto its right edge and its right edge onto
 * the next cell's left edge.
 */
class SymmetryMapsTest {
    private fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    private fun assertNear(msg: String, ex: Float, ey: Float, got: Pair<Float, Float>, eps: Float = 1e-2f) {
        assertTrue("$msg: expected ($ex, $ey), got $got", abs(got.first - ex) <= eps && abs(got.second - ey) <= eps)
    }

    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(9) { n ->
        val r = n / 3; val c = n % 3
        a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
    }

    private fun same(a: FloatArray, b: FloatArray, eps: Float = 1e-3f) = a.indices.all { abs(a[it] - b[it]) <= eps }

    @Test
    fun offAndUnusableSettingsGiveNoCopies() {
        assertTrue(SymmetryMaps.transforms(SymmetrySettings(), 400, 300).isEmpty())
        assertTrue(SymmetryMaps.transforms(SymmetrySettings(SymmetryType.MIRROR), 0, 300).isEmpty())
        // A grid cell larger than the canvas and its ring: nothing but the identity, so no copies.
        assertTrue(SymmetryMaps.transforms(SymmetrySettings(SymmetryType.ARRAY, spacingX = 100_000f, spacingY = 100_000f), 400, 300).isEmpty())
    }

    @Test
    fun mirrorAt90ReflectsXAboutTheCentre() {
        val maps = SymmetryMaps.transforms(SymmetrySettings(SymmetryType.MIRROR), 400, 300)
        assertEquals(2, maps.size)
        assertTrue(SymmetryMaps.isIdentity(maps[0]))
        // The canvas centre (200, 150): x' = 2·200 − x, y unchanged, exactly.
        assertEquals(Pair(350f, 80f), map(maps[1], 50f, 80f))
        assertEquals(Pair(200f, 7f), map(maps[1], 200f, 7f))
        assertTrue("a reflection", maps[1][0] * maps[1][4] - maps[1][1] * maps[1][3] < 0f)
        // A placed centre.
        val placed = SymmetryMaps.transforms(SymmetrySettings(SymmetryType.MIRROR, centerX = 100f, centerY = 50f), 400, 300)
        assertEquals(Pair(170f, 70f), map(placed[1], 30f, 70f))
        // At 0° the axis is horizontal: y is reflected about the centre.
        val flat = SymmetryMaps.transforms(SymmetrySettings(SymmetryType.MIRROR, angleDeg = 0f), 400, 300)
        assertEquals(Pair(30f, 230f), map(flat[1], 30f, 70f))
    }

    @Test
    fun kaleidoscope6Gives12MapsFormingAClosedGroup() {
        val s = SymmetrySettings(SymmetryType.KALEIDOSCOPE, centerX = 130f, centerY = 170f, angleDeg = 75f, divisions = 6)
        val maps = SymmetryMaps.transforms(s, 400, 300)
        assertEquals(12, maps.size)
        assertTrue(SymmetryMaps.isIdentity(maps[0]))
        assertEquals("6 rotations, 6 reflections", 6, maps.count { it[0] * it[4] - it[1] * it[3] < 0f })
        for (i in maps.indices) for (j in maps.indices) {
            if (i != j) assertTrue("maps $i and $j differ", !same(maps[i], maps[j]))
            val p = multiply(maps[i], maps[j])
            assertTrue("map $i · map $j is in the group", maps.any { same(it, p) })
        }
        // Every map keeps the centre and the first mirror reflects about the 75° axis.
        for (m in maps) assertNear("the centre stays", 130f, 170f, map(m, 130f, 170f))
        val onAxis = map(maps[1], 130f + 50f * kotlin.math.cos(Math.toRadians(75.0)).toFloat(), 170f + 50f * kotlin.math.sin(Math.toRadians(75.0)).toFloat())
        assertNear("a point on the axis stays", 130f + 50f * kotlin.math.cos(Math.toRadians(75.0)).toFloat(), 170f + 50f * kotlin.math.sin(Math.toRadians(75.0)).toFloat(), onAxis)
        // Rotation n: n rotations, all keeping orientation.
        val rot = SymmetryMaps.transforms(s.copy(type = SymmetryType.ROTATION, divisions = 5), 400, 300)
        assertEquals(5, rot.size)
        assertTrue(rot.all { it[0] * it[4] - it[1] * it[3] > 0f })
        // Divisions are clamped to 2..32.
        assertEquals(64, SymmetryMaps.transforms(s.copy(divisions = 99), 400, 300).size)
    }

    @Test
    fun theArrayIsCappedAt256WithCulling() {
        // A fine grid: thousands of cells meet the canvas; the 256 nearest to the start are kept.
        val fine = SymmetryMaps.transforms(SymmetrySettings(SymmetryType.ARRAY, spacingX = 10f, spacingY = 10f), 512, 512, 256f, 256f)
        assertEquals(StrokeCopies.MAX, fine.size)
        assertTrue(SymmetryMaps.isIdentity(fine[0]))
        var last = 0.0
        for (m in fine.drop(1)) {
            assertTrue("a translation", m[0] == 1f && m[1] == 0f && m[3] == 0f && m[4] == 1f && m[6] == 0f && m[7] == 0f && m[8] == 1f)
            assertEquals("by whole cells", 0f, m[2] - 10f * (m[2] / 10f).roundToLong(), 1e-3f)
            assertEquals("by whole cells", 0f, m[5] - 10f * (m[5] / 10f).roundToLong(), 1e-3f)
            val d = hypot(m[2].toDouble(), m[5].toDouble())
            assertTrue("nearest to the start first", d >= last - 1e-6)
            last = d
        }
        assertTrue("the cap keeps the cells around the start", last < 100.0)
        // Culling: 200 px cells around the centre of a 512 px canvas: 4 columns × 4 rows meet it,
        // plus the ring one cell wide around them that a stroke can reach into (6 × 6), less the
        // moves by a canvas size or more (−600 px), which nothing on the canvas survives: 5 × 5.
        val coarse = SymmetryMaps.transforms(SymmetrySettings(SymmetryType.ARRAY, spacingX = 200f, spacingY = 200f), 512, 512, 256f, 256f)
        assertEquals(25, coarse.size)
        val xs = coarse.map { it[2] }.toSet()
        assertEquals(setOf(-400f, -200f, 0f, 200f, 400f), xs)
        // The cells meeting the canvas come before the ring.
        val firstSixteen = coarse.take(16).map { it[2] to it[5] }
        assertTrue(firstSixteen.all { (x, y) -> x in -400f..200f && y in -400f..200f })
        // A turned grid keeps translations only, at most 256.
        val turned = SymmetryMaps.transforms(SymmetrySettings(SymmetryType.ARRAY, angleDeg = 120f, spacingX = 37f, spacingY = 23f), 512, 512, 100f, 400f)
        assertTrue(turned.size in 2..StrokeCopies.MAX)
        assertTrue(turned.all { it[0] == 1f && it[4] == 1f && it[1] == 0f && it[3] == 0f })
        // The design's large canvas: 4000 × 5000 at 300 px keeps every cell meeting the canvas.
        val big = SymmetryMaps.transforms(SymmetrySettings(SymmetryType.ARRAY), 4000, 5000, 2000f, 2500f)
        assertTrue("${big.size}", big.size in 221..StrokeCopies.MAX)
    }

    @Test
    fun perspectiveCell10MapsTheQuadsRightEdgeOntoTheNextCellsLeftEdge() {
        val quad = listOf(200f, 200f, 260f, 205f, 270f, 280f, 190f, 270f)
        val s = SymmetrySettings(SymmetryType.PERSPECTIVE_ARRAY, quad = quad)
        val maps = SymmetryMaps.transforms(s, 512, 512, 230f, 240f)
        assertTrue("${maps.size}", maps.size in 9..StrokeCopies.MAX)
        assertTrue(SymmetryMaps.isIdentity(maps[0]))
        assertTrue(maps.all { StrokeCopies.isUsable(it) })
        val h = SymmetryMaps.squareToQuad(quad)!!
        fun corner(i: Int, j: Int) = SymmetryMaps.apply(h, i.toDouble(), j.toDouble())!!.let { it[0].toFloat() to it[1].toFloat() }
        // Cell (1, 0) is the copy taking TL to TR.
        val c10 = maps.single { m -> map(m, 200f, 200f).let { abs(it.first - 260f) < 1e-2f && abs(it.second - 205f) < 1e-2f } }
        // The quad's left edge lands on its right edge: cell (1, 0) starts where the quad ends.
        assertNear("TL", 260f, 205f, map(c10, 200f, 200f))
        assertNear("BL", 270f, 280f, map(c10, 190f, 270f))
        // The quad's right edge lands on the next cell's left edge (the corners of cell (2, 0)).
        val (x20, y20) = corner(2, 0)
        val (x21, y21) = corner(2, 1)
        assertNear("TR", x20, y20, map(c10, 260f, 205f))
        assertNear("BR", x21, y21, map(c10, 270f, 280f))
        // The default cell gives copies too; a start beyond the horizon gives none.
        assertTrue(SymmetryMaps.transforms(SymmetrySettings(SymmetryType.PERSPECTIVE_ARRAY), 512, 512).size >= 9)
        val steep = listOf(240f, 200f, 272f, 200f, 300f, 260f, 212f, 260f)
        val hs = SymmetryMaps.squareToQuad(steep)!!
        // (The horizon of this trapezoid is above y = 200 − 60·32/56·…; far above it is behind.)
        val behind = SymmetryMaps.transforms(SymmetrySettings(SymmetryType.PERSPECTIVE_ARRAY, quad = steep), 512, 512, 256f, -5000f)
        assertTrue("start beyond the horizon: ${behind.size}", behind.isEmpty())
        assertTrue(SymmetryMaps.apply(hs, 0.5, 0.5) != null)
    }
}
