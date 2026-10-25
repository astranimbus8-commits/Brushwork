package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.live.BelowProjection
import com.brushwork.paint.engine.live.ProjectionRules
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.ProjectionOracle
import com.brushwork.paint.testing.RandomStacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

/**
 * v1.10 A9 step 3 (design 2.5 X4, 2.16): the below projection is exact. On random stacks (blends,
 * clips, masks, folders, adjustments, blank and sparse layers; inactive layers parked) the display
 * drawn through the projection equals the frame without projections ([ProjectionOracle]) bit for
 * bit: after the first render, through a run of stroke frames on the active layer, after an edit
 * below (eye, opacity, a layer's pixels: the key drops the whole projection), after a switch of
 * the active layer, and under memory pressure. Below [ProjectionRules.MIN_LAYERS] units nothing is
 * built, at CLASSIC nothing is used, and the first update after a lift never uses it.
 */
@RunWith(RobolectricTestRunner::class)
class BelowProjectionExactTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private class Setup(val doc: Document, val tiles: DisplayTiles, val compositor: Compositor, val projection: BelowProjection, var active: Layer)

    private fun setup(doc: Document, active: Layer): Setup {
        val tiles = DisplayTiles(doc.width, doc.height, tileSize = 128)
        lateinit var s: Setup
        val projection = BelowProjection({ doc }, { s.active }, { null })
        s = Setup(doc, tiles, Compositor(doc) { null }, projection, active)
        activate(s, active)
        projection.install(tiles)
        return s
    }

    /** Makes [layer] the active layer: flat, every other layer parked (as the residency leaves them at TILES). */
    private fun activate(s: Setup, layer: Layer) {
        s.active = layer
        val chaos = ChaosResidency()
        chaos.unpark(layer)
        for (l in s.doc.layers) if (l !== layer) chaos.park(l)
    }

    private fun shown(s: Setup): Bitmap {
        val out = keep(BitmapUtils.createLayerBitmap(s.doc.width, s.doc.height))
        val c = Canvas(out)
        for (i in 0 until s.tiles.tileCount) s.tiles.drawTile(c, i, smooth = false)
        return out
    }

    private fun assertExact(message: String, s: Setup) {
        val expected = keep(ProjectionOracle.frame(s.doc, display = true))
        A9Docs.assertBitEqual(message, expected, shown(s))
    }

    /** The eligible active layers of [doc]: a split with at least MIN_LAYERS visible units below. */
    private fun eligible(doc: Document): List<Layer> =
        doc.layers.filter { !it.isFolder && !it.isAdjustmentLayer && ProjectionRules.belowEligible(doc, ProjectionRules.splitFor(doc, it)) }

    /** A dab on the active layer (flat), as a stroke paints it; returns the dirty rect. */
    private fun dab(s: Setup, r: Random): Rect {
        val l = s.active
        val b = (l.pixels as FlatPixels).bitmap
        val x = r.nextInt(s.doc.width)
        val y = r.nextInt(s.doc.height)
        val rad = 6 + r.nextInt(24)
        Canvas(b).drawCircle(x.toFloat(), y.toFloat(), rad.toFloat(), Paint().apply {
            isAntiAlias = true
            color = Color.argb(60 + r.nextInt(196), r.nextInt(256), r.nextInt(256), r.nextInt(256))
        })
        return Rect(x - rad - 1, y - rad - 1, x + rad + 1, y + rad + 1).apply { intersect(0, 0, s.doc.width, s.doc.height) }
    }

    @Test
    fun theProjectionIsExactOnRandomStacks() {
        var cases = 0
        for (seed in 1L..40L) {
            val doc = RandomStacks.document(seed, RandomStacks.Spec(layers = 14 + (seed % 9).toInt()))
            val candidates = eligible(doc)
            if (candidates.isEmpty()) continue
            val r = Random(seed)
            val s = setup(doc, candidates[r.nextInt(candidates.size)])
            s.tiles.update(s.compositor)
            assertExact("seed $seed: first render", s)
            assertTrue("seed $seed: the projection was built", s.projection.builds > 0)
            val hitsBefore = s.projection.hits
            repeat(6) { f ->
                s.tiles.invalidate(dab(s, r))
                s.tiles.update(s.compositor)
                assertExact("seed $seed: stroke frame $f", s)
            }
            assertTrue("seed $seed: stroke frames drew from the projection", s.projection.hits > hitsBefore)
            // An edit below: the key drops the whole projection.
            val split = ProjectionRules.splitFor(doc, s.active)
            val below = doc.layers.subList(0, split).filter { !it.isFolder && !it.isAdjustmentLayer }
            val edited = below[r.nextInt(below.size)]
            val drops = s.projection.drops
            when (seed % 3) {
                0L -> edited.visible = !edited.visible
                1L -> edited.opacity = if (edited.opacity > 0.5f) 0.35f else 0.9f
                else -> {
                    edited.pixels = FlatPixels(keep(A9Docs.content(seed, doc.width, doc.height)))
                    edited.markChanged()
                }
            }
            s.tiles.invalidate(null)
            s.tiles.update(s.compositor)
            assertExact("seed $seed: after an edit below", s)
            // (An eye turned off may leave fewer than MIN_LAYERS units below: then the plain path draws.)
            if (ProjectionRules.belowEligible(doc, split)) assertTrue("seed $seed: the edit below dropped the projection", s.projection.drops > drops)
            // Another active layer.
            val next = eligible(doc).firstOrNull { it !== s.active }
            if (next != null) {
                activate(s, next)
                s.tiles.invalidate(dab(s, r))
                s.tiles.update(s.compositor)
                s.tiles.invalidate(null)
                s.tiles.update(s.compositor)
                assertExact("seed $seed: after a switch", s)
            }
            s.projection.freeAll()
            s.tiles.release()
            cases++
        }
        assertTrue("enough eligible stacks ($cases)", cases >= 10)
    }

    @Test
    fun belowEightUnitsAtClassicAndAfterALiftNothingIsUsed() {
        val doc = WaitingTimingKit.stack(12, 3L)
        // Active layer 5: four units below, too few.
        val s = setup(doc, doc.layers[5])
        assertFalse(ProjectionRules.belowEligible(doc, 5))
        s.tiles.update(s.compositor)
        assertEquals(0L, s.projection.builds)
        assertExact("too few units below", s)
        // Active layer 10: eligible, but the first update after a lift renders on the plain path.
        activate(s, doc.layers[10])
        s.tiles.projectionsOff = true
        s.tiles.invalidate(null)
        s.tiles.update(s.compositor)
        s.tiles.projectionsOff = false
        assertEquals(0L, s.projection.hits)
        assertExact("the first update after a lift", s)
        s.tiles.invalidate(null)
        s.tiles.update(s.compositor)
        assertTrue(s.projection.hits > 0)
        assertExact("through the projection", s)
        // CLASSIC: never used.
        A9Docs.setLevel(EngineLevel.CLASSIC)
        val hits = s.projection.hits
        s.tiles.invalidate(null)
        s.tiles.update(s.compositor)
        assertEquals(hits, s.projection.hits)
        assertExact("at CLASSIC", s)
        A9Docs.setLevel(EngineLevel.TILES)
    }
}
