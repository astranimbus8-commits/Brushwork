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
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.live.AboveProjection
import com.brushwork.paint.engine.live.BelowProjection
import com.brushwork.paint.engine.live.ProjectionRules
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.ProjectionOracle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

/**
 * v1.10 A9 step 7 (design 2.5 X5, 2.16): while a finger paints, the above projection stays close
 * to the frame without projections ([ProjectionOracle]), premultiplied channels as stored, and the
 * first update after the gesture is exact, bit for bit.
 *
 * The bound measured, not the design's 2 LSB: source-over is associative in exact arithmetic, but
 * the oracle rounds to 8 bits after every unit onto what is below, the projection after every unit
 * onto transparency and once more over the rest, so the two differ by the oracle's own rounding.
 * Plain Normal layers alone reach 3 LSB at 30 units above; an F16 cache measured the same (the
 * difference is the oracle's rounding, not the cache's). Every case asserts the largest byte
 * difference is at most [MAX_LSB] (the largest seen over the fixed seeds, Robolectric's Skia) and
 * that at most [TAIL] of the bytes the projection drew differ by more than 2.
 *
 * Each construct the rules admit above the active unit is measured on its own (Normal layers plain
 * and masked, clip groups with a Normal base and blended clips, isolated Normal folders with
 * blended children, pass-through folders at 100 %), then random mixes with a layer clipped to the
 * active layer and the active layer inside a pass-through folder, and a stack of 200 parked layers
 * with the active one at index 10. Inactive layers are parked, the below projection installed.
 * Every case first proves the projection drew (`hits > 0`); the stroke touches several display
 * tiles; the whole display is compared after every frame. The projection never draws for an
 * ineligible stack (a MULTIPLY unit, a pass-through folder at 60 %, a visible adjustment inside a
 * pass-through folder, 7 units above, a clipped active layer, a render override above), with the
 * check's switch off, at CLASSIC, or with the finger up: those frames are exact.
 */
@RunWith(RobolectricTestRunner::class)
class AboveProjectionErrorTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    @Before
    fun aboveOn() {
        A9Docs.setAboveProjection(true)
    }

    @After
    fun tearDown() {
        A9Docs.disposeTracked()
    }

    /** The units stacked above the active layer. */
    private enum class Kind { NORMAL, MASKED, CLIP_GROUP, ISOLATED, PASS_100, PASS_60 }

    private class Setup(val doc: Document, val active: Layer) {
        var override: LayerRenderOverride? = null
        val tiles = DisplayTiles(doc.width, doc.height, tileSize = 128)
        val compositor = Compositor(doc) { override }
        val below = BelowProjection({ doc }, { active }, { override })
        val above = AboveProjection({ doc }, { active }, { override })
    }

    /** Differences from the oracle over stroke frames: the largest byte difference, and the bytes over 2 LSB among those the projection drew. */
    private class Err {
        var max = 0
        var over = 0L
        var drawn = 0L

        fun add(o: Err) {
            max = maxOf(max, o.max)
            over += o.over
            drawn += o.drawn
        }

        val tail: Double get() = if (drawn == 0L) 0.0 else over.toDouble() / drawn

        override fun toString() = "max $max LSB, ${"%.5f".format(tail * 100)} % of $drawn drawn bytes over 2 LSB ($over)"
    }

    /** The display of [doc] with [active] painted on: every other layer parked, both projections installed, rendered once with the finger up. */
    private fun setup(doc: Document, active: Layer): Setup {
        val s = Setup(doc, active)
        val chaos = ChaosResidency()
        chaos.unpark(active)
        for (l in doc.layers) if (l !== active) chaos.park(l)
        s.below.install(s.tiles)
        s.above.install(s.tiles)
        s.tiles.update(s.compositor)
        return s
    }

    private fun release(s: Setup) {
        s.above.freeAll()
        s.below.freeAll()
        s.tiles.release()
    }

    private fun shown(s: Setup): Bitmap {
        val out = BitmapUtils.createLayerBitmap(s.doc.width, s.doc.height)
        val c = Canvas(out)
        for (i in 0 until s.tiles.tileCount) s.tiles.drawTile(c, i, smooth = false)
        return out
    }

    private fun oracle(s: Setup): Bitmap = ProjectionOracle.frame(s.doc, override = s.override, display = true)

    /** The display's difference from the oracle now (the parts drawn exactly differ by 0, so every byte over 2 is in a part the projection drew). */
    private fun error(s: Setup): Err {
        val expected = oracle(s)
        val actual = shown(s)
        try {
            val h = A9Docs.premultipliedErrorHistogram(expected, actual)
            val e = Err()
            for (d in h.indices) if (h[d] > 0L) {
                e.max = maxOf(e.max, d)
                if (d > 2) e.over += h[d]
            }
            for (i in 0 until s.tiles.tileCount) s.tiles.approxPart(i)?.let { e.drawn += it.width().toLong() * it.height() * 4L }
            return e
        } finally {
            expected.recycle()
            actual.recycle()
        }
    }

    private fun assertWithinBound(message: String, e: Err) {
        assertTrue("$message: at most $MAX_LSB LSB per channel ($e)", e.max <= MAX_LSB)
        assertTrue("$message: at most ${TAIL * 100} % of the drawn bytes over 2 LSB ($e)", e.tail <= TAIL)
    }

    private fun assertExact(message: String, s: Setup) {
        val expected = oracle(s)
        val actual = shown(s)
        try {
            A9Docs.assertBitEqual(message, expected, actual)
        } finally {
            expected.recycle()
            actual.recycle()
        }
        assertFalse("$message: no part is left approximate", s.tiles.hasApprox)
        for (i in 0 until s.tiles.tileCount) assertTrue("$message: display tile $i is exact", s.tiles.isExact(i))
    }

    /** A dab on the active layer (flat), as a stroke paints it; returns the dirty rect. */
    private fun dab(s: Setup, r: Random): Rect {
        val b = (s.active.pixels as FlatPixels).bitmap
        val x = r.nextInt(s.doc.width)
        val y = r.nextInt(s.doc.height)
        val rad = 8 + r.nextInt(28)
        Canvas(b).drawCircle(x.toFloat(), y.toFloat(), rad.toFloat(), Paint().apply {
            isAntiAlias = true
            color = Color.argb(60 + r.nextInt(196), r.nextInt(256), r.nextInt(256), r.nextInt(256))
        })
        return Rect(x - rad - 1, y - rad - 1, x + rad + 1, y + rad + 1).apply { intersect(0, 0, s.doc.width, s.doc.height) }
    }

    /** [frames] stroke frames with the finger down (dabs across the display); their differences from the oracle. */
    private fun stroke(s: Setup, r: Random, frames: Int): Err {
        val all = Err()
        s.tiles.gestureDown = true
        try {
            repeat(frames) {
                s.tiles.invalidate(dab(s, r))
                s.tiles.update(s.compositor)
                all.add(error(s))
            }
        } finally {
            s.tiles.gestureDown = false
        }
        return all
    }

    /** The view's first update after a gesture: what the above projection drew is rendered again, projections off. */
    private fun lift(s: Setup) {
        s.tiles.exactAfterGesture()
        s.tiles.projectionsOff = true
        s.tiles.update(s.compositor)
        s.tiles.projectionsOff = false
    }

    /**
     * The stacks: [A9Docs.backdrop] (opaque, its right third translucent), [below] painted layers
     * of random blends, the active layer (Normal; with a MULTIPLY layer clipped to it when
     * [clippedToActive]; inside a pass-through folder with a Normal layer under and over it when
     * [activeInFolder]), then one unit per entry of [kinds], bottom first.
     */
    private inner class Stack(seed: Long, kinds: List<Kind>, below: Int = 9, clippedToActive: Boolean = false, activeInFolder: Boolean = false) {
        private val r = Random(seed)
        val doc: Document = A9Docs.track(Document("above$seed", "above$seed", A9Docs.W, A9Docs.H))
        val active: Layer
        private val maskSeed = seed * 31

        init {
            doc.layers += Layer(doc.newLayerId(), "Backdrop", A9Docs.backdrop(seed))
            val modes = LayerBlendMode.entries
            repeat(below) { doc.layers += layer("B$it", blend = modes[r.nextInt(modes.size)], opacity = opacity()) }
            val folder = if (activeInFolder) Layer.newFolder(doc.newLayerId(), "Active folder", FolderSpec(passThrough = true)) else null
            val parent = folder?.id ?: Layer.ROOT_ID
            if (folder != null) doc.layers += layer("Under", parent, opacity = opacity())
            active = layer("Active", parent, opacity = opacity())
            doc.layers += active
            if (clippedToActive) doc.layers += layer("Clipped to active", parent, LayerBlendMode.MULTIPLY).apply { clipping = true }
            if (folder != null) {
                doc.layers += layer("Over", parent, opacity = opacity())
                doc.layers += folder
            }
            for ((i, k) in kinds.withIndex()) unit(k, "U$i", Layer.ROOT_ID)
        }

        private fun opacity(): Float = 0.5f + r.nextInt(51) / 100f

        private fun blended(): LayerBlendMode = listOf(LayerBlendMode.MULTIPLY, LayerBlendMode.SCREEN, LayerBlendMode.OVERLAY, LayerBlendMode.DARKEN)[r.nextInt(4)]

        private fun painted(): Bitmap {
            val w = A9Docs.W
            val h = A9Docs.H
            val b = BitmapUtils.createLayerBitmap(w, h)
            val c = Canvas(b)
            val p = Paint().apply { isAntiAlias = true }
            repeat(2 + r.nextInt(4)) {
                p.color = Color.argb(40 + r.nextInt(216), r.nextInt(256), r.nextInt(256), r.nextInt(256))
                val x = r.nextInt(w).toFloat()
                val y = r.nextInt(h).toFloat()
                val s = 20f + r.nextInt(160)
                if (r.nextBoolean()) c.drawCircle(x, y, s / 2f, p) else c.drawRect(x - s / 2f, y - s / 3f, x + s / 2f, y + s / 3f, p)
            }
            return b
        }

        fun layer(name: String, parent: Long = Layer.ROOT_ID, blend: LayerBlendMode = LayerBlendMode.NORMAL, opacity: Float = 1f): Layer =
            Layer(doc.newLayerId(), name, painted()).apply {
                parentId = parent
                blendMode = blend
                this.opacity = opacity
            }

        fun folder(name: String, parent: Long, passThrough: Boolean, opacity: Float): Layer =
            Layer.newFolder(doc.newLayerId(), name, FolderSpec(passThrough = passThrough)).apply {
                parentId = parent
                this.opacity = opacity
            }

        fun unit(k: Kind, name: String, parent: Long) {
            when (k) {
                Kind.NORMAL -> doc.layers += layer(name, parent, opacity = opacity())
                Kind.MASKED -> doc.layers += layer(name, parent, opacity = opacity()).apply {
                    mask = A9Docs.mask(maskSeed + doc.layers.size)
                    maskEnabled = true
                }
                Kind.CLIP_GROUP -> {
                    doc.layers += layer("$name base", parent, opacity = opacity())
                    doc.layers += layer("$name clip 1", parent, blended(), opacity()).apply { clipping = true }
                    doc.layers += layer("$name clip 2", parent, blended()).apply { clipping = true }
                }
                Kind.ISOLATED -> {
                    val f = folder(name, parent, passThrough = false, opacity = opacity())
                    doc.layers += layer("$name child 1", f.id, opacity = opacity())
                    doc.layers += layer("$name child 2", f.id, blended(), opacity())
                    doc.layers += layer("$name child 3", f.id, blended())
                    doc.layers += f
                }
                Kind.PASS_100, Kind.PASS_60 -> {
                    val f = folder(name, parent, passThrough = true, opacity = if (k == Kind.PASS_100) 1f else 0.6f)
                    doc.layers += layer("$name child 1", f.id, opacity = opacity())
                    unit(Kind.CLIP_GROUP, "$name group", f.id)
                    unit(Kind.MASKED, "$name masked", f.id)
                    doc.layers += f
                }
            }
        }
    }

    /**
     * One case: a stroke of [frames] frames with the finger down, which must draw through the above
     * projection, stay within the bound and never drop it; the lift, exact; a second stroke along
     * the same dabs, which keeps the tiles built (persistent while the key stays) and lifts exact.
     * Returns the differences of both strokes.
     */
    private fun case(name: String, doc: Document, active: Layer, seed: Long, frames: Int = 10): Err {
        assertTrue("$name: eligible", ProjectionRules.aboveEligible(doc, active, ProjectionRules.aboveStart(doc, active)))
        val s = setup(doc, active)
        assertExact("$name: the first render, finger up", s)
        assertEquals("$name: nothing is built with the finger up", 0L, s.above.builds)
        val first = stroke(s, Random(seed), frames)
        assertTrue("$name: the stroke frames drew through the above projection", s.above.hits > 0)
        assertTrue("$name: the parts it drew are known", s.tiles.hasApprox)
        assertEquals("$name: edits of the active layer never drop the projection", 0L, s.above.drops)
        println("AboveProjectionErrorTest: $name: $first over $frames frames (${s.above.builds} display tiles built, ${s.above.hits} parts drawn)")
        assertWithinBound("$name, while the finger is down", first)
        lift(s)
        assertExact("$name: the first update after the lift", s)
        val hits = s.above.hits
        val again = stroke(s, Random(seed), frames)
        assertEquals("$name: the lift and a second stroke keep the tiles built", 0L, s.above.drops)
        assertTrue("$name: the second stroke drew through the projection", s.above.hits > hits)
        assertWithinBound("$name, the second stroke", again)
        lift(s)
        assertExact("$name: after the second lift", s)
        release(s)
        A9Docs.dispose(doc)
        return first.also { it.add(again) }
    }

    private fun case(name: String, stack: Stack, seed: Long, frames: Int = 10): Err = case(name, stack.doc, stack.active, seed, frames)

    @Test
    fun normalLayersAboveStayWithinTheBound() {
        val kinds = List(6) { Kind.NORMAL } + List(4) { Kind.MASKED }
        case("Normal layers", Stack(11L, kinds.shuffled(Random(11L))), 11L)
        case("30 Normal layers", Stack(12L, List(30) { Kind.NORMAL }), 12L)
    }

    @Test
    fun clipGroupsWithANormalBaseStayWithinTheBound() {
        case("clip groups", Stack(12L, listOf(Kind.CLIP_GROUP, Kind.NORMAL, Kind.CLIP_GROUP, Kind.NORMAL, Kind.CLIP_GROUP, Kind.NORMAL, Kind.CLIP_GROUP, Kind.NORMAL)), 12L)
    }

    @Test
    fun isolatedNormalFoldersStayWithinTheBound() {
        case("isolated folders", Stack(13L, listOf(Kind.ISOLATED, Kind.NORMAL, Kind.ISOLATED, Kind.NORMAL, Kind.ISOLATED, Kind.NORMAL)), 13L)
    }

    @Test
    fun passThroughFoldersAt100StayWithinTheBound() {
        case("pass-through folders at 100 %", Stack(14L, listOf(Kind.PASS_100, Kind.NORMAL, Kind.PASS_100, Kind.NORMAL, Kind.PASS_100)), 14L)
    }

    /** 200 parked layers (each sparse, Normal at 95 %), the active one at index 10: 189 units above, as the timings use. */
    @Test
    fun aDeepStackStaysWithinTheBound() {
        val doc = WaitingTimingKit.stack(200, 61L)
        case("200 layers, active at 10", doc, doc.layers[10], 61L, frames = 12)
    }

    @Test
    fun randomEligibleStacksStayWithinTheBoundAndLiftExact() {
        val all = Err()
        val kindsUsed = Kind.entries - Kind.PASS_60
        for (seed in 21L..36L) {
            val r = Random(seed)
            val kinds = List(8 + r.nextInt(5)) { kindsUsed[r.nextInt(kindsUsed.size)] }
            val stack = Stack(seed, kinds, below = 8 + r.nextInt(4), clippedToActive = r.nextBoolean(), activeInFolder = r.nextBoolean())
            all.add(case("random stack $seed", stack, seed, frames = 8))
        }
        println("AboveProjectionErrorTest: random stacks: $all")
    }

    /**
     * A pass-through folder below 100 % draws through A8's coverage lerp, not source-over: not
     * eligible (when the rules still admitted it, it measured 3 LSB, as admitted constructs do; the
     * exclusion keeps the arithmetic the bound was measured on).
     */
    @Test
    fun passThroughFoldersBelow100AreNotEligible() {
        val st = Stack(15L, listOf(Kind.PASS_60, Kind.NORMAL, Kind.PASS_60, Kind.NORMAL, Kind.PASS_60))
        assertFalse(ProjectionRules.aboveEligible(st.doc, st.active, ProjectionRules.aboveStart(st.doc, st.active)))
        val s = setup(st.doc, st.active)
        assertNotUsed("pass-through folders at 60 % above", s, 15L)
        release(s)
    }

    @Test
    fun aKeyChangeAboveDuringAStrokeRebuildsAndTheLiftIsExact() {
        val stack = Stack(41L, List(10) { Kind.NORMAL })
        val s = setup(stack.doc, stack.active)
        val r = Random(41L)
        assertWithinBound("before the change", stroke(s, r, 4))
        assertTrue(s.above.hits > 0)
        val drops = s.above.drops
        // An eye above turns off while the finger is down (the controller invalidates its bounds).
        val top = stack.doc.layers.last()
        top.visible = false
        s.tiles.invalidate(null)
        s.tiles.gestureDown = true
        s.tiles.update(s.compositor)
        s.tiles.gestureDown = false
        assertTrue("the key dropped the projection", s.above.drops > drops)
        assertWithinBound("right after the change", error(s))
        assertWithinBound("after the change", stroke(s, r, 4))
        lift(s)
        assertExact("after the lift", s)
        release(s)
    }

    /** A stroke that must not draw through the above projection: its frames are exact. */
    private fun assertNotUsed(name: String, s: Setup, seed: Long) {
        val hits = s.above.hits
        val e = stroke(s, Random(seed), 6)
        assertEquals("$name: the above projection never drew", hits, s.above.hits)
        assertEquals("$name: the frames are exact", 0, e.max)
        assertFalse("$name: nothing approximate", s.tiles.hasApprox)
    }

    @Test
    fun neverUsedWhereTheRulesOrTheSwitchesSayNo() {
        // A MULTIPLY unit above.
        Stack(51L, List(9) { Kind.NORMAL }).let { st ->
            st.doc.layers[st.doc.layers.size - 3].blendMode = LayerBlendMode.MULTIPLY
            val s = setup(st.doc, st.active)
            assertNotUsed("a MULTIPLY unit above", s, 51L)
            release(s)
        }
        // A visible adjustment layer inside a pass-through folder above.
        Stack(52L, List(9) { Kind.NORMAL }).let { st ->
            val f = st.folder("Folder", Layer.ROOT_ID, passThrough = true, opacity = 1f)
            st.doc.layers += st.layer("Child", f.id)
            st.doc.layers += Layer(st.doc.newLayerId(), "Adjustment", A9Docs.empty()).apply {
                adjustment = AdjustmentSpec(filterId = "adjust.invert")
                parentId = f.id
            }
            st.doc.layers += f
            val s = setup(st.doc, st.active)
            assertNotUsed("an adjustment inside a pass-through folder above", s, 52L)
            release(s)
        }
        // 7 units above: too few; 8: used.
        Stack(53L, List(7) { Kind.NORMAL }).let { st ->
            assertEquals(7, ProjectionRules.unitsAbove(st.doc, ProjectionRules.aboveStart(st.doc, st.active)))
            val s = setup(st.doc, st.active)
            assertNotUsed("7 units above", s, 53L)
            st.doc.layers += st.layer("Eighth")
            s.tiles.invalidate(null)
            s.tiles.update(s.compositor)
            assertWithinBound("8 units above", stroke(s, Random(53L), 4))
            assertTrue("8 units above: used", s.above.hits > 0)
            lift(s)
            assertExact("8 units above, after the lift", s)
            release(s)
        }
        // A clipped active layer.
        Stack(54L, List(9) { Kind.NORMAL }).let { st ->
            st.active.clipping = true
            assertEquals(-1, ProjectionRules.aboveStart(st.doc, st.active))
            val s = setup(st.doc, st.active)
            assertNotUsed("a clipped active layer", s, 54L)
            release(s)
        }
        // A render override drawing a layer above (a tool preview).
        Stack(55L, List(9) { Kind.NORMAL }).let { st ->
            val s = setup(st.doc, st.active)
            val target = st.doc.layers.last()
            s.override = object : LayerRenderOverride {
                override val layer: Layer = target
                override fun drawContent(canvas: Canvas): Boolean = false
            }
            assertNotUsed("a render override above", s, 55L)
            s.override = null
            release(s)
        }
        // The check's switch off, CLASSIC, the finger up.
        Stack(56L, List(9) { Kind.NORMAL }).let { st ->
            val s = setup(st.doc, st.active)
            A9Docs.setAboveProjection(false)
            assertNotUsed("EngineConfig.aboveProjection off", s, 56L)
            A9Docs.setAboveProjection(true)
            A9Docs.setLevel(EngineLevel.CLASSIC)
            try {
                assertNotUsed("at CLASSIC", s, 57L)
            } finally {
                A9Docs.setLevel(EngineLevel.TILES)
            }
            val hits = s.above.hits
            val r = Random(58L)
            repeat(4) {
                s.tiles.invalidate(dab(s, r))
                s.tiles.update(s.compositor)
            }
            assertEquals("the finger up: never used", hits, s.above.hits)
            assertExact("the finger up", s)
            // And eligible: used once the finger is down.
            assertWithinBound("the finger down", stroke(s, r, 3))
            assertTrue(s.above.hits > hits)
            release(s)
        }
    }

    private companion object {
        /**
         * The largest byte difference seen over the fixed seeds (Robolectric's Skia, x86): the
         * oracle's own 8-bit rounding after every unit, which no cache reproduces (an F16 cache
         * measured the same).
         */
        const val MAX_LSB = 4

        /**
         * At most 1 % of the bytes the projection drew differ by more than 2 LSB. Measured: 0.37 %
         * at worst (30 overlapping Normal layers, the second stroke), 0.006 % over the random
         * stacks, none under the 200-layer stack; a fault (not rounding) moves most bytes.
         */
        const val TAIL = 1e-2
    }
}
