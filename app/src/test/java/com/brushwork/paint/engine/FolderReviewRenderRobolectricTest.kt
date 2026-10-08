package com.brushwork.paint.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.7 area A review (§3.8 c, d, I11): one document with every kind of folder at once (an isolated
 * Overlay folder holding a Multiply child and an Invert adjustment, a pass-through folder at 50 %
 * holding a Multiply child and a nested isolated folder, a folder clip base with a clip, and a
 * clipped folder) looks the same on the canvas as in the flattened picture: small display tiles
 * that cut through every folder equal `renderFlattened`, also after a partial invalidation across
 * tile borders, and the document saves and reopens with the same tree and the same picture.
 */
@RunWith(RobolectricTestRunner::class)
class FolderReviewRenderRobolectricTest {
    private val w = 300
    private val h = 200

    private fun layer(doc: Document, name: String, draw: (Canvas) -> Unit): Layer =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { draw(Canvas(it.bitmap)) }

    private fun disc(doc: Document, name: String, color: Int, cx: Float, cy: Float, r: Float): Layer =
        layer(doc, name) { it.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }) }

    private fun folder(doc: Document, name: String, passThrough: Boolean): Layer =
        Layer.newFolder(doc.newLayerId(), name, FolderSpec(passThrough = passThrough))

    private fun wrap(f: Layer, vararg children: Layer): Layer = f.also { for (c in children) c.parentId = f.id }

    private fun argb(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun flattened(doc: Document): IntArray = argb(Compositor(doc) { null }.renderFlattened())

    private fun shown(t: DisplayTiles): IntArray {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        t.draw(Canvas(out), null, smooth = false)
        return argb(out)
    }

    /** Bottom first: backdrop, Iso{M, Invert}, Half{N, Inner{P}}, Base{Q}, Clip, Under, Clipped{R}. */
    private class Scene(val doc: Document, val halfChild: Layer)

    private fun scene(): Scene {
        val doc = Document("folder-review", "Folder review", w, h, 300f)
        val back = layer(doc, "Backdrop") { c ->
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2050C0.toInt(), 0xFFF0C020.toInt(), Shader.TileMode.CLAMP) })
            c.drawRect(0f, h * 0.4f, w.toFloat(), h * 0.6f, Paint().apply { color = 0x9030F060.toInt() })
        }
        val m = disc(doc, "M", 0xC02020FF.toInt(), 90f, 80f, 60f).also { it.blendMode = LayerBlendMode.MULTIPLY }
        val invert = layer(doc, "Invert") {}.also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.opacity = 0.6f }
        val iso = wrap(folder(doc, "Iso", passThrough = false), m, invert).also { it.blendMode = LayerBlendMode.OVERLAY; it.opacity = 0.8f }
        val n = disc(doc, "N", 0xD0FF8000.toInt(), 150f, 120f, 70f).also { it.blendMode = LayerBlendMode.MULTIPLY }
        val p = disc(doc, "P", 0x9020F0F0.toInt(), 200f, 70f, 50f)
        val inner = wrap(folder(doc, "Inner", passThrough = false), p).also { it.blendMode = LayerBlendMode.SCREEN }
        val half = wrap(folder(doc, "Half", passThrough = true), n, inner).also { it.opacity = 0.5f }
        val q = disc(doc, "Q", 0xFFFFFFFF.toInt(), 230f, 140f, 55f)
        val base = wrap(folder(doc, "Base", passThrough = false), q).also { it.blendMode = LayerBlendMode.DARKEN }
        val clip = layer(doc, "Clip") { it.drawRect(150f, 100f, 300f, 170f, Paint().apply { color = 0xB000FF00.toInt() }) }.also {
            it.clipping = true; it.blendMode = LayerBlendMode.OVERLAY
        }
        val under = disc(doc, "Under", 0xFFE0E0E0.toInt(), 60f, 160f, 40f)
        val r = disc(doc, "R", 0xE0FF00FF.toInt(), 80f, 150f, 45f)
        val clipped = wrap(folder(doc, "Clipped", passThrough = false), r).also {
            it.clipping = true; it.opacity = 0.75f; it.blendMode = LayerBlendMode.MULTIPLY
        }
        doc.layers += listOf(back, m, invert, iso, n, p, inner, half, q, base, clip, under, r, clipped)
        assertNull(LayerTree.check(doc.layers))
        return Scene(doc, n)
    }

    @Test
    fun smallDisplayTilesEqualTheFlattenedPictureAlsoAfterAPartialInvalidation() {
        val s = scene()
        val doc = s.doc
        val tiles = DisplayTiles(w, h, tileSize = 128)
        assertEquals(3, tiles.cols)
        assertEquals(2, tiles.rows)
        val compositor = Compositor(doc) { null }
        tiles.update(compositor, null)
        assertArrayEquals("every tile", flattened(doc), shown(tiles))

        // Paint inside the pass-through 50 % folder, then refresh only a rect across tile borders.
        Canvas(s.halfChild.bitmap).drawCircle(160f, 130f, 25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF20A040.toInt() })
        val dirty = Rect(100, 90, 220, 170)
        tiles.invalidate(dirty)
        tiles.update(compositor, null)
        assertArrayEquals("after a partial refresh", flattened(doc), shown(tiles))
        tiles.release()
    }

    @Test
    fun theMixedTreeSavesAndReopensWithTheSameTreeAndPicture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        val doc = scene().doc
        val before = flattened(doc)
        repo.save(doc, null)
        val loaded = repo.load(doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertNull(LayerTree.check(loaded.layers))
        assertEquals(doc.layers.map { it.name }, loaded.layers.map { it.name })
        assertEquals(doc.layers.map { it.parentId }, loaded.layers.map { it.parentId })
        assertEquals(doc.layers.map { it.folder }, loaded.layers.map { it.folder })
        assertEquals(doc.layers.map { it.blendMode }, loaded.layers.map { it.blendMode })
        assertEquals(doc.layers.map { it.clipping }, loaded.layers.map { it.clipping })
        assertEquals(doc.layers.map { it.opacity }, loaded.layers.map { it.opacity })
        assertEquals(doc.layers.map { it.adjustment }, loaded.layers.map { it.adjustment })
        val after = flattened(loaded)
        var off = 0
        for (i in before.indices) if (before[i] != after[i]) off++
        assertTrue("$off pixels differ after reopening", off == 0)
    }
}
