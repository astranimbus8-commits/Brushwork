package com.brushwork.paint.vector

import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Selection
import com.brushwork.paint.vector.draw.CutKind
import com.brushwork.paint.vector.draw.EraseSession
import com.brushwork.paint.vector.draw.EraseTarget
import com.brushwork.paint.vector.draw.FillHits
import com.brushwork.paint.vector.draw.FillPart
import com.brushwork.paint.vector.draw.TargetCache
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.geom.StrokeHits
import com.brushwork.paint.vector.geom.TileSet
import com.brushwork.paint.vector.select.ObjectEdits
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 F1 (item 18): the frozen readers of a [VStroke]'s symmetry copies ([VStroke.copies]).
 * Bounds, hit tests, the selection footprint, tiles and the eraser see the union over the
 * copies; transforms carry the copies along (M·Ck·M⁻¹); the codec keeps usable maps only; a
 * stroke without copies encodes and compares exactly as in v1.6 (I13). Also [VSplinePoint.sharp]
 * (item 4): never written when false, cleared on the ends of an open spline.
 */
@RunWith(RobolectricTestRunner::class)
class VStrokeCopiesTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** The mirror about the vertical line x = [ax]. */
    private fun mirrorX(ax: Float) = floatArrayOf(-1f, 0f, 2f * ax, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun translate(dx: Float, dy: Float) = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    /** A horizontal stroke from (60, 100) to (160, 100), 8 px wide, hard round tip. */
    private fun stroke(copies: List<FloatArray> = emptyList(), id: Long = 1): VStroke {
        val n = 21
        val xs = FloatArray(n) { 60f + it * 5f }
        val ys = FloatArray(n) { 100f }
        return VStroke(
            id, preset = BrushLibrary.defaultBrush.copy(size = 8f, scatter = 0f), color = -16777216, seed = 7L,
            stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }), copies = copies,
        )
    }

    private fun mirrored(s: VStroke, ax: Float): VStroke =
        s.copy(points = PackedPoints(FloatArray(s.points.size) { 2f * ax - s.points.x[it] }, s.points.y.copyOf(), s.points.p.copyOf()))

    private fun contains(outer: RectF, inner: RectF) =
        outer.left <= inner.left + 1e-3f && outer.top <= inner.top + 1e-3f && outer.right >= inner.right - 1e-3f && outer.bottom >= inner.bottom - 1e-3f

    private val json = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }

    @Test
    fun theBoundsCoverEveryCopy() {
        val plain = stroke()
        val s = stroke(listOf(identity, mirrorX(256f)))
        val b = VectorOps.bounds(s)
        val own = StrokeRaster.strokeBounds(plain.preset, plain.sizeScale, plain.points)
        val m = mirrored(plain, 256f)
        val other = StrokeRaster.strokeBounds(m.preset, m.sizeScale, m.points)
        assertTrue("$b holds the stroke $own", contains(b, own))
        assertTrue("$b holds the mirrored copy $other", contains(b, other))
        assertTrue("a stroke without copies keeps its v1.6 bounds", VectorOps.bounds(plain) == own)
        // A copy that doubles sizes paints twice the radius.
        val big = stroke(listOf(identity, floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f)))
        val doubled = StrokeRaster.strokeBounds(plain.preset, 2f, PackedPoints(FloatArray(21) { (60f + it * 5f) * 2f }, FloatArray(21) { 200f }, FloatArray(21) { 1f }))
        assertTrue("${VectorOps.bounds(big)} holds the doubled copy $doubled", contains(VectorOps.bounds(big), doubled))
    }

    @Test
    fun aTransformCarriesTheCopiesAlong() {
        val s = stroke(listOf(identity, mirrorX(256f)))
        val moved = VectorOps.transformed(s, translate(30f, 20f)) as VStroke
        assertEquals(2, moved.copies.size)
        assertTrue("the identity stays first", moved.copies[0].contentEquals(identity))
        // M·C·M⁻¹: the mirror line moves with the stroke (x = 286).
        val c = moved.copies[1]
        for (i in 0 until 9) assertEquals("coefficient $i", mirrorX(286f)[i], c[i], 1e-3f)
        val b0 = VectorOps.bounds(s)
        val b1 = VectorOps.bounds(moved)
        assertEquals(b0.left + 30f, b1.left, 0.01f); assertEquals(b0.right + 30f, b1.right, 0.01f)
        assertEquals(b0.top + 20f, b1.top, 0.01f); assertEquals(b0.bottom + 20f, b1.bottom, 0.01f)
        // The moved mirror copy's dabs are the old ones moved.
        val d0 = StrokeCopies.mappedDabs(StrokeHits.dabs(s), s.copies[1])
        val d1 = StrokeCopies.mappedDabs(StrokeHits.dabs(moved), moved.copies[1])
        assertEquals(d0.size, d1.size)
        for (i in 0 until d0.size / 3) {
            assertEquals(d0[3 * i] + 30f, d1[3 * i], 0.01f)
            assertEquals(d0[3 * i + 1] + 20f, d1[3 * i + 1], 0.01f)
            assertEquals(d0[3 * i + 2], d1[3 * i + 2], 0.01f)
        }
        // A scaling document map scales the mirror's axis too (x = 512) and the stroke's size.
        val scaled = VectorOps.transformed(s, floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f)) as VStroke
        for (i in 0 until 9) assertEquals("coefficient $i", mirrorX(512f)[i], scaled.copies[1][i], 1e-3f)
        assertEquals(2f, scaled.sizeScale, 1e-4f)
        // A singular map leaves the copies alone (the stroke is squashed anyway).
        val flat = VectorOps.transformed(s, floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)) as VStroke
        assertTrue(StrokeCopies.sameMaps(s.copies, flat.copies))
        // Without copies: nothing to carry.
        assertTrue((VectorOps.transformed(stroke(), translate(3f, 4f)) as VStroke).copies.isEmpty())
    }

    @Test
    fun aStrokeWithoutCopiesEncodesAndComparesAsBefore() {
        val a = stroke()
        val text = json.encodeToString(VStroke.serializer(), a)
        assertFalse("no copies key: $text", text.contains("copies"))
        val back = json.decodeFromString(VStroke.serializer(), text)
        assertEquals(a, back)
        assertEquals(a.hashCode(), back.hashCode())
        // Equal by content, as a data class (the maps are arrays: compared by value).
        val x = stroke(listOf(identity, mirrorX(256f)))
        val y = stroke(listOf(identity.copyOf(), mirrorX(256f)))
        assertEquals(x, y)
        assertEquals(x.hashCode(), y.hashCode())
        assertNotEquals(a, x)
        assertNotEquals(x, stroke(listOf(identity, mirrorX(255f))))
        assertNotEquals(a, a.copy(sizeScale = 2f))
        assertNotEquals(a, a.copy(taperOut = false))
        assertEquals(a, a.copy())
        val withCopies = json.encodeToString(VStroke.serializer(), x)
        assertTrue(withCopies, withCopies.contains("\"copies\""))
    }

    @Test
    fun theCodecKeepsUsableMapsOnly() {
        val good = listOf(identity, mirrorX(256f), floatArrayOf(0.5f, -0.866f, 10f, 0.866f, 0.5f, 3f, 0.0001f, 0f, 1f))
        val content = VectorContent.EMPTY.plus(listOf(stroke(good), stroke())).first
        val back = VectorCodec.decode(VectorCodec.encode(content))
        assertEquals(content, back)
        assertTrue(StrokeCopies.sameMaps(good, (back.objects[0] as VStroke).copies))
        assertTrue((back.objects[1] as VStroke).copies.isEmpty())

        val bad = listOf(
            identity,
            floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(1f, 2f, 0f, 2f, 4f, 0f, 0f, 0f, 1f),
            floatArrayOf(1f, 0f, 0f, 0f, 1f),
            floatArrayOf(1e9f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            mirrorX(256f),
        )
        val damaged = VectorContent.EMPTY.plus(listOf(stroke(bad))).first
        val read = VectorCodec.decode(VectorCodec.encode(damaged)).objects[0] as VStroke
        assertTrue(StrokeCopies.sameMaps(listOf(identity, mirrorX(256f)), read.copies))

        val many = List(StrokeCopies.MAX + 40) { translate(it.toFloat(), 0f) }
        val capped = VectorCodec.decode(VectorCodec.encode(VectorContent.EMPTY.plus(listOf(stroke(many))).first)).objects[0] as VStroke
        assertEquals(StrokeCopies.MAX, capped.copies.size)
        // Content whose copies are all usable is the same instance after sanitizing.
        assertSame(content, VectorCodec.sanitizedCopies(content))
    }

    @Test
    fun aCopyIsHitTouchedAndTiled() {
        val s = stroke(listOf(identity, mirrorX(256f)))
        val plain = stroke()
        // (412, 100) is on the mirrored copy only.
        assertTrue(VectorOps.hit(s, Vec2(412f, 100f), 0f))
        assertFalse(VectorOps.hit(plain, Vec2(412f, 100f), 0f))
        assertTrue(VectorOps.hit(s, Vec2(100f, 100f), 0f))
        assertFalse(VectorOps.hit(s, Vec2(256f, 100f), 0f))
        assertTrue(StrokeHits.distance(s, 412f, 100f) <= 0f)
        assertTrue(StrokeHits.distance(plain, 412f, 100f) > 200f)

        val w = 512; val h = 256
        val content = VectorContent.EMPTY.plus(listOf(s)).first
        val disc = Selection.fromPath(Path().apply { addCircle(412f, 100f, 6f, Path.Direction.CW) }, w, h)
        assertEquals(setOf(content.objects[0].id), VectorOps.touching(content, disc))
        val plainContent = VectorContent.EMPTY.plus(listOf(plain)).first
        assertTrue(VectorOps.touching(plainContent, disc).isEmpty())

        val tiles = TileSet(w, h, 64)
        tiles.addObject(s, VectorOps.bounds(s))
        assertTrue("the copy's tile", tiles.has(412 / 64, 100 / 64))
        assertTrue("the stroke's tile", tiles.has(100 / 64, 100 / 64))
        assertFalse("nothing between them", tiles.has(256 / 64, 100 / 64))
    }

    @Test
    fun theEraserRemovesAStrokeWithCopiesWhole() {
        val s = stroke(listOf(identity, mirrorX(256f)))
        val tg = EraseTarget.of(s)
        assertEquals(CutKind.WHOLE, tg.cut)
        assertTrue(tg.isLine)
        assertEquals(2, tg.lines.size)
        assertTrue(tg.touchedBy(412f, 100f, 412f, 100f, 2f))
        assertFalse(tg.touchedBy(256f, 100f, 256f, 100f, 2f))
        assertEquals(CutKind.STROKE, EraseTarget.of(stroke()).cut)

        val content = VectorContent.EMPTY.plus(listOf(s)).first
        for (mode in VectorEraseMode.entries) {
            val session = EraseSession(content, mode)
            session.add(412f, 100f, 3f)
            val out = session.result()
            assertNotNull("$mode erases something", out)
            assertTrue("$mode erases the whole stroke", out!!.objects.isEmpty())
        }
    }

    @Test
    fun theBucketAndObjectEditsSeeTheCopies() {
        val s = stroke(listOf(identity, mirrorX(256f)))
        val content = VectorContent.EMPTY.plus(listOf(s)).first
        // The bucket finds the stroke's line on its copy (EraseTarget's centerlines), and only there.
        val hit = FillHits.find(content, Vec2(412f, 100f), 1f, TargetCache())
        assertEquals(FillPart.LINE, hit?.part)
        assertEquals(null, FillHits.find(content, Vec2(256f, 100f), 1f, TargetCache()))
        // Duplicate moves the copies with the stroke (the mirror line too).
        val (after, ids) = ObjectEdits.duplicate(content, setOf(content.objects[0].id), 10f, 0f)!!
        val dup = after.objects.first { it.id in ids } as VStroke
        for (i in 0 until 9) assertEquals("coefficient $i", mirrorX(266f)[i], dup.copies[1][i], 1e-3f)
        // Recoloring keeps them.
        val red = ObjectEdits.recolored(s, 0xFFFF0000.toInt(), linesOnly = false) as VStroke
        assertTrue(StrokeCopies.sameMaps(s.copies, red.copies))
    }

    @Test
    fun sharpSplinePointsAreWrittenOnlyWhenSetAndNeverOnTheEndsOfAnOpenSpline() {
        val plain = json.encodeToString(VSplinePoint.serializer(), VSplinePoint(1f, 2f))
        assertFalse(plain, plain.contains("sharp"))
        val corner = json.encodeToString(VSplinePoint.serializer(), VSplinePoint(1f, 2f, sharp = true))
        assertTrue(corner, corner.contains("\"sharp\":true"))
        assertEquals(VSplinePoint(1f, 2f, sharp = true), json.decodeFromString(VSplinePoint.serializer(), corner))

        val pts = List(4) { VSplinePoint(it * 10f, 0f, sharp = true) }
        val open = VSpline(pts).sanitized()
        assertEquals(listOf(false, true, true, false), open.points.map { it.sharp })
        val closed = VSpline(pts, cyclic = true)
        assertSame(closed, closed.sanitized())
        val inner = VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(5f, 5f, sharp = true), VSplinePoint(10f, 0f)))
        assertSame(inner, inner.sanitized())
    }
}
