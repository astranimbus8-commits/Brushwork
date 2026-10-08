package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.7 area A (§3.8 c, d): [FolderComposite] against flat documents and hand-built references
 * (Robolectric, native Skia). Pass-through folders at 100 % are invisible, isolated folders and
 * clip groups with folders equal the same group with the folder's composite as a layer, a
 * pass-through folder below 100 % is the lerp, adjustment layers keep to their folder, a layer
 * range may cut through a pass-through folder only, and folder tiles meet without seams.
 */
@RunWith(RobolectricTestRunner::class)
class FolderCompositeTest {
    private val w = 96
    private val h = 72

    // ------------------------------------------------------------------ building

    private fun layer(doc: Document, name: String, w: Int = this.w, h: Int = this.h, draw: (Canvas) -> Unit): Layer {
        val l = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h))
        draw(Canvas(l.bitmap))
        return l
    }

    private fun disc(doc: Document, name: String, color: Int, cx: Float, cy: Float, r: Float, w: Int = this.w, h: Int = this.h): Layer =
        layer(doc, name, w, h) { it.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }) }

    /** An opaque gradient with a translucent band over it. */
    private fun backdrop(doc: Document, w: Int = this.w, h: Int = this.h): Layer = layer(doc, "Backdrop", w, h) { c ->
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2050C0.toInt(), 0xFFF0C020.toInt(), Shader.TileMode.CLAMP) })
        c.drawRect(0f, h * 0.4f, w.toFloat(), h * 0.6f, Paint().apply { color = 0x9030F060.toInt() })
    }

    private fun folder(doc: Document, name: String, passThrough: Boolean = true): Layer =
        Layer.newFolder(doc.newLayerId(), name, FolderSpec(passThrough = passThrough))

    /** Puts [children] (already in `doc.layers`, directly below where [f] goes) into [f]. */
    private fun wrap(f: Layer, vararg children: Layer): Layer {
        for (c in children) c.parentId = f.id
        return f
    }

    private fun docOf(vararg layers: Layer, w: Int = this.w, h: Int = this.h): Document = Document("f", "f", w, h).also { d ->
        d.layers += layers
        assertNull(LayerTree.check(d.layers))
    }

    private fun flattened(doc: Document): Bitmap = Compositor(doc) { null }.renderFlattened()

    private fun argb(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The raw premultiplied bytes. */
    private fun premul(b: Bitmap): ByteArray = ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()

    private fun pixels(doc: Document): IntArray = argb(flattened(doc))

    private fun maxDiff(a: ByteArray, b: ByteArray): Int {
        assertEquals(a.size, b.size)
        var m = 0
        for (i in a.indices) m = maxOf(m, abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)))
        return m
    }

    // ------------------------------------------------------------------ pass-through at 100 %

    @Test
    fun wrappingALayerInAPassThroughFolderIsIdenticalForEveryBlendModeAndOpacity() {
        for (mode in LayerBlendMode.entries) for (opacity in listOf(0.3f, 1f)) {
            val flat = Document("f", "f", w, h)
            val b0 = backdrop(flat)
            val l0 = disc(flat, "L", 0xC0E04080.toInt(), 40f, 30f, 26f).also { it.blendMode = mode; it.opacity = opacity }
            flat.layers += listOf(b0, l0)
            val wrapped = Document("f", "f", w, h)
            val b1 = backdrop(wrapped)
            val l1 = disc(wrapped, "L", 0xC0E04080.toInt(), 40f, 30f, 26f).also { it.blendMode = mode; it.opacity = opacity }
            val f = wrap(folder(wrapped, "Folder 1"), l1)
            wrapped.layers += listOf(b1, l1, f)
            assertNull(LayerTree.check(wrapped.layers))
            assertArrayEquals("$mode at $opacity", pixels(flat), pixels(wrapped))
        }
    }

    /** Bottom, a clip group, an Invert, a Multiply layer with a clipped layer, a Screen layer. */
    private fun richLayers(doc: Document): List<Layer> = listOf(
        backdrop(doc),
        disc(doc, "base", 0xE0FF3020.toInt(), 30f, 30f, 24f),
        disc(doc, "clipped", 0xB02040FF.toInt(), 44f, 36f, 22f).also { it.clipping = true; it.blendMode = LayerBlendMode.OVERLAY },
        layer(doc, "invert") {}.also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.opacity = 0.6f },
        disc(doc, "multiply", 0x9900FF66.toInt(), 60f, 40f, 28f).also { it.blendMode = LayerBlendMode.MULTIPLY },
        disc(doc, "on multiply", 0x80FFFF00.toInt(), 66f, 30f, 20f).also { it.clipping = true },
        disc(doc, "screen", 0x70A0A0FF.toInt(), 20f, 50f, 18f).also { it.blendMode = LayerBlendMode.SCREEN; it.opacity = 0.8f },
    )

    @Test
    fun movingLayersIntoPassThroughFoldersAt100IsIdentical() {
        val flatDoc = Document("f", "f", w, h).also { it.layers += richLayers(it) }
        val expected = pixels(flatDoc)
        // Whole clip groups and the adjustment layer into nested pass-through folders.
        val doc = Document("f", "f", w, h)
        val ls = richLayers(doc)
        val inner = wrap(folder(doc, "Inner"), ls[3], ls[4], ls[5])
        val outer = folder(doc, "Outer")
        ls[1].parentId = outer.id; ls[2].parentId = outer.id; inner.parentId = outer.id
        doc.layers += listOf(ls[0], ls[1], ls[2], ls[3], ls[4], ls[5], inner, outer, ls[6])
        assertNull(LayerTree.check(doc.layers))
        val c = Compositor(doc) { null }
        assertArrayEquals(expected, argb(c.renderFlattened()))
        assertEquals("pass-through at 100 % needs no scratch", 0, c.folderScratch.allocated)
        // An empty folder changes nothing either.
        val empty = Document("f", "f", w, h).also { d -> d.layers += richLayers(d); d.layers += folder(d, "Empty", passThrough = false) }
        assertArrayEquals(expected, pixels(empty))
    }

    @Test
    fun aHiddenFolderHidesItsChildrenAndAClippingLayerAtTheBottomOfAFolderDrawsUnclipped() {
        val doc = Document("f", "f", w, h)
        val b = backdrop(doc)
        val small = disc(doc, "small", 0xFFFF0000.toInt(), 20f, 20f, 8f)
        val big = disc(doc, "big", 0xFF0000FF.toInt(), 50f, 40f, 30f).also { it.clipping = true }
        val f = wrap(folder(doc, "F"), big)
        doc.layers += listOf(b, small, big, f)
        // Per level: "big" has no base inside the folder, so it draws whole.
        val ref = Document("f", "f", w, h).also { d ->
            d.layers += listOf(backdrop(d), disc(d, "small", 0xFFFF0000.toInt(), 20f, 20f, 8f), disc(d, "big", 0xFF0000FF.toInt(), 50f, 40f, 30f))
        }
        assertArrayEquals(pixels(ref), pixels(doc))
        f.visible = false
        val bare = Document("f", "f", w, h).also { d -> d.layers += listOf(backdrop(d), disc(d, "small", 0xFFFF0000.toInt(), 20f, 20f, 8f)) }
        assertArrayEquals(pixels(bare), pixels(doc))
        f.visible = true
        f.opacity = 0f
        assertArrayEquals(pixels(bare), pixels(doc))
    }

    // ------------------------------------------------------------------ isolated

    @Test
    fun anIsolatedMultiplyChildDoesNotBlendWithTheBackdropOutside() {
        val doc = Document("f", "f", w, h)
        val b = backdrop(doc)
        val child = disc(doc, "multiply", 0xD080E040.toInt(), 48f, 36f, 26f).also { it.blendMode = LayerBlendMode.MULTIPLY }
        val f = wrap(folder(doc, "F", passThrough = false), child)
        doc.layers += listOf(b, child, f)
        // Multiply onto the folder's transparency is the colour itself: Normal over the backdrop.
        val normal = Document("f", "f", w, h).also { d ->
            d.layers += listOf(backdrop(d), disc(d, "normal", 0xD080E040.toInt(), 48f, 36f, 26f))
        }
        val isolated = pixels(doc)
        assertArrayEquals(pixels(normal), isolated)
        f.folder = FolderSpec(passThrough = true)
        assertFalse("pass-through multiplies with the backdrop", pixels(doc).contentEquals(isolated))
    }

    @Test
    fun anIsolatedFolderEqualsItsCompositeAsALayerWithItsBlendAndOpacity() {
        for (mode in listOf(LayerBlendMode.NORMAL, LayerBlendMode.MULTIPLY, LayerBlendMode.SCREEN, LayerBlendMode.OVERLAY)) {
            val doc = Document("f", "f", w, h)
            val ls = richLayers(doc)
            val f = wrap(folder(doc, "F", passThrough = false), ls[4], ls[5], ls[6]).also { it.blendMode = mode; it.opacity = 0.7f }
            doc.layers += ls + f
            val block = FolderComposite.renderBlock(doc, f)
            val ref = Document("f", "f", w, h).also { d ->
                val r = richLayers(d)
                d.layers += r.subList(0, 4)
                d.layers += Layer(d.newLayerId(), "block", block).also { it.blendMode = mode; it.opacity = 0.7f }
            }
            assertArrayEquals("$mode", pixels(ref), pixels(doc))
        }
    }

    @Test
    fun anAdjustmentInsideAnIsolatedFolderChangesOnlyTheFolder() {
        val doc = Document("f", "f", w, h)
        val b = backdrop(doc)
        val child = disc(doc, "child", 0xFFE06020.toInt(), 30f, 30f, 20f)
        val invert = layer(doc, "invert") {}.also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert") }
        val f = wrap(folder(doc, "F", passThrough = false), child, invert)
        doc.layers += listOf(b, child, invert, f)
        val out = flattened(doc)
        // Outside the child the backdrop is untouched.
        assertEquals(argb(flattened(docOf(backdrop(Document("x", "x", w, h)))))[80 + 60 * w], argb(out)[80 + 60 * w])
        // Inside, the inverted child over the backdrop.
        val inverted = flattened(Document("f", "f", w, h).also { d ->
            d.layers += disc(d, "child", 0xFFE06020.toInt(), 30f, 30f, 20f)
            d.layers += layer(d, "invert") {}.also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert") }
        })
        val ref = Document("f", "f", w, h).also { d -> d.layers += listOf(backdrop(d), Layer(d.newLayerId(), "inverted", inverted)) }
        assertArrayEquals(pixels(ref), argb(out))
        // Pass-through: it inverts everything below, as at the top level.
        f.folder = FolderSpec(passThrough = true)
        val atRoot = Document("f", "f", w, h).also { d ->
            d.layers += listOf(backdrop(d), disc(d, "child", 0xFFE06020.toInt(), 30f, 30f, 20f))
            d.layers += layer(d, "invert") {}.also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert") }
        }
        assertArrayEquals(pixels(atRoot), pixels(doc))
    }

    // ------------------------------------------------------------------ pass-through below 100 %

    @Test
    fun aPassThroughFolderBelow100IsTheLerpWithinOneLsb() {
        for (opaque in listOf(true, false)) for (opacity in listOf(0.5f, 0.25f, 0.9f)) {
            val doc = Document("f", "f", w, h)
            val b = if (opaque) backdrop(doc) else disc(doc, "translucent", 0x8040A0E0.toInt(), 40f, 36f, 34f)
            val m = disc(doc, "multiply", 0xC0FF8000.toInt(), 50f, 30f, 24f).also { it.blendMode = LayerBlendMode.MULTIPLY }
            val n = disc(doc, "normal", 0x9020F0F0.toInt(), 30f, 44f, 18f)
            val invert = layer(doc, "invert") {}.also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.opacity = 0.5f }
            val f = wrap(folder(doc, "F"), m, n, invert)
            doc.layers += listOf(b, m, n, invert, f)
            f.visible = false
            val below = premul(flattened(doc))
            f.visible = true
            val full = premul(flattened(doc))
            f.opacity = opacity
            val got = premul(flattened(doc))
            val o = (opacity * 255f + 0.5f).toInt() / 255f
            val expected = ByteArray(full.size) { i ->
                val c = full[i].toInt() and 0xFF
                val bb = below[i].toInt() and 0xFF
                (o * c + (1 - o) * bb).roundToInt().toByte()
            }
            val d = maxDiff(expected, got)
            assertTrue("opaque=$opaque at $opacity: off by $d", d <= 1)
        }
    }

    // ------------------------------------------------------------------ clipping with folders

    @Test
    fun aFolderClipBaseAndAClippedFolderEqualHandBuiltReferences() {
        // A folder as a clip base (isolated Screen).
        run {
            val doc = Document("f", "f", w, h)
            val b = backdrop(doc)
            val a = disc(doc, "a", 0xE0FF2020.toInt(), 30f, 30f, 20f)
            val c = disc(doc, "c", 0xC02020FF.toInt(), 50f, 36f, 18f).also { it.blendMode = LayerBlendMode.MULTIPLY }
            val f = wrap(folder(doc, "F", passThrough = false), a, c).also { it.blendMode = LayerBlendMode.SCREEN; it.opacity = 0.8f }
            val clip = layer(doc, "clip") { it.drawRect(0f, 20f, w.toFloat(), 50f, Paint().apply { color = 0xB000FF00.toInt() }) }.also {
                it.clipping = true; it.blendMode = LayerBlendMode.OVERLAY
            }
            doc.layers += listOf(b, a, c, f, clip)
            val block = FolderComposite.renderBlock(doc, f)
            val ref = Document("f", "f", w, h).also { d ->
                d.layers += backdrop(d)
                d.layers += Layer(d.newLayerId(), "block", block).also { it.blendMode = LayerBlendMode.SCREEN; it.opacity = 0.8f }
                d.layers += layer(d, "clip") { it.drawRect(0f, 20f, w.toFloat(), 50f, Paint().apply { color = 0xB000FF00.toInt() }) }.also {
                    it.clipping = true; it.blendMode = LayerBlendMode.OVERLAY
                }
            }
            assertArrayEquals("folder clip base", pixels(ref), pixels(doc))
            // A hidden clip still makes the folder a clip base: composited isolated with its own blend.
            clip.visible = false
            val alone = Document("f", "f", w, h).also { d ->
                d.layers += backdrop(d)
                d.layers += Layer(d.newLayerId(), "block", block).also { it.blendMode = LayerBlendMode.SCREEN; it.opacity = 0.8f }
            }
            assertArrayEquals("clip base with a hidden clip", pixels(alone), pixels(doc))

            // Pass-through: composited isolated all the same, Normal (the blend list shows "Pass
            // through", not the stored Screen), as "Merge folder" makes it.
            clip.visible = true
            f.folder = FolderSpec(passThrough = true)
            assertEquals(LayerBlendMode.NORMAL, FolderComposite.drawnBlend(f))
            val normal = Document("f", "f", w, h).also { d ->
                d.layers += backdrop(d)
                d.layers += Layer(d.newLayerId(), "block", block).also { it.opacity = 0.8f }
                d.layers += layer(d, "clip") { it.drawRect(0f, 20f, w.toFloat(), 50f, Paint().apply { color = 0xB000FF00.toInt() }) }.also {
                    it.clipping = true; it.blendMode = LayerBlendMode.OVERLAY
                }
            }
            assertArrayEquals("pass-through folder clip base", pixels(normal), pixels(doc))
        }
        // A clipped folder, and a folder clipped to a folder.
        run {
            val doc = Document("f", "f", w, h)
            val b = backdrop(doc)
            val base = disc(doc, "base", 0xFFFFFFFF.toInt(), 48f, 36f, 26f)
            val x = disc(doc, "x", 0xE0FF00FF.toInt(), 30f, 30f, 22f)
            val y = disc(doc, "y", 0xA000C0C0.toInt(), 60f, 40f, 22f).also { it.blendMode = LayerBlendMode.DARKEN }
            val f = wrap(folder(doc, "F", passThrough = false), x, y).also { it.clipping = true; it.opacity = 0.75f; it.blendMode = LayerBlendMode.MULTIPLY }
            doc.layers += listOf(b, base, x, y, f)
            val block = FolderComposite.renderBlock(doc, f)
            val ref = Document("f", "f", w, h).also { d ->
                d.layers += backdrop(d)
                d.layers += disc(d, "base", 0xFFFFFFFF.toInt(), 48f, 36f, 26f)
                d.layers += Layer(d.newLayerId(), "block", block).also { it.clipping = true; it.opacity = 0.75f; it.blendMode = LayerBlendMode.MULTIPLY }
            }
            assertArrayEquals("clipped folder", pixels(ref), pixels(doc))
            // Pass-through and clipped: isolated, Normal.
            f.folder = FolderSpec(passThrough = true)
            val normal = Document("f", "f", w, h).also { d ->
                d.layers += backdrop(d)
                d.layers += disc(d, "base", 0xFFFFFFFF.toInt(), 48f, 36f, 26f)
                d.layers += Layer(d.newLayerId(), "block", block).also { it.clipping = true; it.opacity = 0.75f }
            }
            assertArrayEquals("pass-through clipped folder", pixels(normal), pixels(doc))

            // The base becomes a folder too.
            val doc2 = Document("f", "f", w, h)
            val b2 = backdrop(doc2)
            val p = disc(doc2, "p", 0xFFFFFFFF.toInt(), 40f, 36f, 20f)
            val q = disc(doc2, "q", 0xFF808080.toInt(), 60f, 36f, 20f)
            val fb = wrap(folder(doc2, "FB", passThrough = false), p, q)
            val x2 = disc(doc2, "x", 0xE0FF00FF.toInt(), 30f, 30f, 22f)
            val y2 = disc(doc2, "y", 0xA000C0C0.toInt(), 60f, 40f, 22f).also { it.blendMode = LayerBlendMode.DARKEN }
            val fc = wrap(folder(doc2, "FC", passThrough = false), x2, y2).also { it.clipping = true; it.opacity = 0.75f; it.blendMode = LayerBlendMode.MULTIPLY }
            doc2.layers += listOf(b2, p, q, fb, x2, y2, fc)
            assertNull(LayerTree.check(doc2.layers))
            val baseBlock = FolderComposite.renderBlock(doc2, fb)
            val clipBlock = FolderComposite.renderBlock(doc2, fc)
            val ref2 = Document("f", "f", w, h).also { d ->
                d.layers += backdrop(d)
                d.layers += Layer(d.newLayerId(), "base block", baseBlock)
                d.layers += Layer(d.newLayerId(), "clip block", clipBlock).also { it.clipping = true; it.opacity = 0.75f; it.blendMode = LayerBlendMode.MULTIPLY }
            }
            assertArrayEquals("folder clipped to a folder", pixels(ref2), pixels(doc2))
        }
    }

    @Test
    fun renderBlockIgnoresTheFoldersOwnEyeOpacityAndBlend() {
        val doc = Document("f", "f", w, h)
        val b = backdrop(doc)
        val a = disc(doc, "a", 0xE0FF2020.toInt(), 30f, 30f, 20f)
        val c = disc(doc, "c", 0xC02020FF.toInt(), 50f, 36f, 18f).also { it.blendMode = LayerBlendMode.MULTIPLY }
        val hidden = disc(doc, "hidden", 0xFF00FF00.toInt(), 50f, 50f, 18f).also { it.visible = false }
        val f = wrap(folder(doc, "F"), a, c, hidden).also { it.visible = false; it.opacity = 0.2f; it.blendMode = LayerBlendMode.DIFFERENCE }
        doc.layers += listOf(b, a, c, hidden, f)
        val ref = Document("f", "f", w, h).also { d ->
            d.layers += disc(d, "a", 0xE0FF2020.toInt(), 30f, 30f, 20f)
            d.layers += disc(d, "c", 0xC02020FF.toInt(), 50f, 36f, 18f).also { it.blendMode = LayerBlendMode.MULTIPLY }
        }
        assertArrayEquals(pixels(ref), argb(FolderComposite.renderBlock(doc, f)))
        // Not a folder of the document: empty.
        assertTrue(argb(FolderComposite.renderBlock(doc, a)).all { it == 0 })
    }

    // ------------------------------------------------------------------ layer ranges

    @Test
    fun aRangeMayCutThroughAPassThroughFolderOnly() {
        val doc = Document("f", "f", w, h)
        val ls = richLayers(doc)
        // [backdrop, F{base, clipped, invert, multiply, on multiply}, screen]
        val f = wrap(folder(doc, "F"), ls[1], ls[2], ls[3], ls[4], ls[5])
        doc.layers += listOf(ls[0], ls[1], ls[2], ls[3], ls[4], ls[5], f, ls[6])
        val k = doc.indexOf(ls[3])
        val n = doc.layers.size
        fun render(vararg ranges: IntRange?): IntArray {
            val c = Compositor(doc) { null }
            val bmp = BitmapUtils.createLayerBitmap(w, h)
            val target = CompositeTarget.identity(bmp)
            val canvas = Canvas(bmp)
            for (r in ranges) c.drawDocument(canvas, null, useOverrides = false, target = target, layerRange = r)
            return argb(bmp)
        }
        val full = render(null)
        assertArrayEquals(full, render(0 until k, k until n))
        // Hidden: both halves draw nothing of it.
        f.visible = false
        assertArrayEquals(render(null), render(0 until k, k until n))
        f.visible = true
        // Isolated, below 100 %, or a clip base: the cut is refused.
        for (change in listOf<(Layer) -> Unit>({ it.folder = FolderSpec(passThrough = false) }, { it.opacity = 0.5f }, { ls[6].clipping = true })) {
            change(f)
            var threw = false
            try { render(0 until k) } catch (e: IllegalArgumentException) { threw = true }
            assertTrue(threw)
            f.folder = FolderSpec(); f.opacity = 1f; ls[6].clipping = false
        }
    }

    @Test
    fun drawnAsIsFollowsTheCompositorsGroups() {
        val doc = Document("f", "f", w, h)
        val bottom = disc(doc, "bottom", 0xFF102030.toInt(), 10f, 10f, 5f)
        val child = disc(doc, "child", 0xFF405060.toInt(), 20f, 20f, 5f)
        val f = wrap(folder(doc, "F"), child)
        val inG = disc(doc, "in G", 0xFF708090.toInt(), 30f, 30f, 5f)
        val g = wrap(folder(doc, "G"), inG)
        doc.layers += listOf(bottom, child, f, inG, g)
        assertNull(LayerTree.check(doc.layers))
        val ci = doc.indexOf(child)
        assertTrue(FolderComposite.drawnAsIs(doc.layers, doc.indexOf(bottom)))
        assertTrue(FolderComposite.drawnAsIs(doc.layers, ci))
        for (change in listOf<() -> Unit>({ f.folder = FolderSpec(passThrough = false) }, { f.visible = false }, { f.opacity = 0.99f })) {
            change()
            assertFalse(FolderComposite.drawnAsIs(doc.layers, ci))
            f.folder = FolderSpec(); f.visible = true; f.opacity = 1f
        }
        // Clipped onto the layer below: not as is; at the bottom of its level it is a lone base.
        f.clipping = true
        assertFalse(FolderComposite.drawnAsIs(doc.layers, ci))
        bottom.adjustment = AdjustmentSpec(filterId = "adjust.invert")
        assertTrue("above an adjustment layer a clipping unit is a base", FolderComposite.drawnAsIs(doc.layers, ci))
        bottom.adjustment = null
        f.clipping = false
        // The sibling FOLDER above clips onto it: a clip base (G's top, not its bottom child `index + 1`).
        g.clipping = true
        assertFalse(FolderComposite.drawnAsIs(doc.layers, ci))
        g.clipping = false
        assertTrue(FolderComposite.drawnAsIs(doc.layers, ci))
        // A clipping layer inside G does not make F a clip base.
        inG.clipping = true
        assertTrue(FolderComposite.drawnAsIs(doc.layers, ci))
    }

    // ------------------------------------------------------------------ tiles

    @Test
    fun folderTilesMeetWithoutSeamsAtScaleTwoAndOnOffsetTiles() {
        val size = 512
        val doc = Document("f", "f", size, size)
        val b = backdrop(doc, size, size)
        val a = disc(doc, "a", 0xE0FF2020.toInt(), 250f, 260f, 200f, size, size)
        val m = disc(doc, "m", 0xC02020FF.toInt(), 300f, 220f, 180f, size, size).also { it.blendMode = LayerBlendMode.MULTIPLY }
        val iso = wrap(folder(doc, "iso", passThrough = false), a, m).also { it.blendMode = LayerBlendMode.OVERLAY; it.opacity = 0.8f }
        val s = disc(doc, "s", 0x90FFFF00.toInt(), 256f, 256f, 240f, size, size)
        val half = wrap(folder(doc, "half"), s).also { it.opacity = 0.6f }
        doc.layers += listOf(b, a, m, iso, s, half)
        assertNull(LayerTree.check(doc.layers))

        // Scale 2 (1024² target px: 4 scratch tiles per folder) against a saveLayer draw (no
        // target: untiled), with the pass-through folder at 100 % so both are exact.
        half.opacity = 1f
        val scale = Matrix().apply { setScale(2f, 2f) }
        val tiled = BitmapUtils.createLayerBitmap(2 * size, 2 * size)
        val c = Compositor(doc) { null }
        Canvas(tiled).apply { concat(scale) }.let { c.drawDocument(it, null, useOverrides = false, target = CompositeTarget(tiled, scale)) }
        assertTrue(c.folderScratch.allocated >= 1)
        val untiled = BitmapUtils.createLayerBitmap(2 * size, 2 * size)
        Canvas(untiled).apply { concat(scale) }.let { Compositor(doc) { null }.drawDocument(it, null, useOverrides = false, target = null) }
        assertTrue("scale 2: off by ${maxDiff(premul(untiled), premul(tiled))}", maxDiff(premul(untiled), premul(tiled)) <= 1)

        // An offset display tile equals the same pixels of the full image (the lerp too).
        half.opacity = 0.6f
        val whole = flattened(doc)
        val tr = Rect(200, 136, 500, 436)
        val tile = BitmapUtils.createLayerBitmap(tr.width(), tr.height())
        Canvas(tile).apply { translate(-tr.left.toFloat(), -tr.top.toFloat()); clipRect(tr) }.let {
            Compositor(doc) { null }.drawDocument(it, tr, target = CompositeTarget.displayTile(tile, tr.left, tr.top))
        }
        val part = Bitmap.createBitmap(whole, tr.left, tr.top, tr.width(), tr.height())
        assertArrayEquals(argb(part), argb(tile))
    }
}
