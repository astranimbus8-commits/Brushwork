package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.CanvasLimits
import com.brushwork.paint.tools.text.frames.TextThreads
import com.brushwork.paint.ui.gallery.CanvasPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.max

/**
 * v1.10 F0 (design 3.2 C15, "Limit rewiring in F0"; lead, frozen): [LayerBudgetV19] gives v1.9's
 * numbers, line by line. The formulas below are copied from v1.9 (`EditorController`'s `layerCap`
 * region and `canAddAdjustmentLayer`, `ArraySources.hasRoom`, `CanvasOps.memoryBudget`, the render
 * caches' eighth of the heap, the gallery and import checks with the heap) and every member is
 * compared with them on 12 canvas sizes (both ends of the 2..100 clamp, sizes the gallery refuses)
 * times 6 document states (plain; masks; a folder; array sources; masks switched off; about 100
 * layers). The budget-only members are checked on documents of the declared size whose bitmaps are
 * 1 x 1 (the formulas read the document size, not the bitmaps); the controller members, the
 * refusals, `ArraySources.hasRoom`, `CanvasOps.memoryBudget` and the array patches' cap are checked
 * on a real editor, and a stand-in policy shows each of them reads [LayerBudget.current].
 */
@RunWith(RobolectricTestRunner::class)
class LayerBudgetV19ParityTest {
    private val heap = Runtime.getRuntime().maxMemory()
    private val made = mutableListOf<Bitmap>()
    private var saved: LayerBudget = LayerBudgetV19
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @Before
    fun pin() {
        saved = LayerBudget.current
        LayerBudget.current = LayerBudgetV19
    }

    @After
    fun restore() {
        LayerBudget.current = saved
        controller?.dispose()
        scope?.cancel()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    // ------------------------------------------------------------------ v1.9's formulas (copied)

    private fun v19MaxLayers(doc: Document): Int {
        val budget = (heap * 0.55).toLong()
        val per = max(1L, doc.layerBytes)
        return (budget / per - 3).toInt().coerceIn(2, 100)
    }

    private fun v19EffectiveLayerCount(doc: Document): Int {
        val per = max(1L, doc.layerBytes)
        val arrayBytes = doc.layers.sumOf { it.array?.pixels?.bytes ?: 0L }
        val masks = doc.layers.count { it.mask != null }
        return doc.pixelLayerCount + masks + ((arrayBytes + per - 1) / per).toInt()
    }

    private fun v19CanAddLayer(doc: Document): Boolean = v19EffectiveLayerCount(doc) < v19MaxLayers(doc)

    private fun v19CanAddAdjustmentLayer(doc: Document): Boolean = v19EffectiveLayerCount(doc) + 2 <= v19MaxLayers(doc)

    private fun v19HasRoom(doc: Document, newLayers: Int, addBytes: Long, freedBytes: Long = 0L): Boolean {
        val per = max(1L, doc.layerBytes)
        val bytes = max(0L, doc.layers.sumOf { it.array?.pixels?.bytes ?: 0L } - freedBytes + addBytes)
        val masks = doc.layers.count { it.mask != null }
        val count = doc.pixelLayerCount.toLong() + masks + newLayers + (bytes + per - 1) / per
        return count <= v19MaxLayers(doc)
    }

    private fun v19Message(doc: Document): String = "Layer limit reached (${v19MaxLayers(doc)}) for this canvas size"

    private fun v19MemoryBudget(): Long = (heap * 0.55).toLong()

    private fun v19RenderCap(): Long = heap / 8

    private fun v19RawMaxLayers(w: Int, h: Int): Long = (heap * 0.55).toLong() / max(1L, w.toLong() * h * 4) - 3

    // ------------------------------------------------------------------ fixtures

    private fun bitmap(w: Int = 1, h: Int = 1): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { made += it }

    private fun layer(doc: Document, name: String, full: Boolean): Layer =
        Layer(doc.newLayerId(), name, if (full) BitmapUtils.createLayerBitmap(doc.width, doc.height).also { made += it } else bitmap())

    private fun mask(doc: Document, full: Boolean): Bitmap = if (full) bitmap(doc.width, doc.height) else bitmap()

    /** The six document states at [w] x [h]; [full] = bitmaps of the document size (an editor's). */
    private fun state(i: Int, w: Int, h: Int, full: Boolean): Document {
        val doc = Document("parity", STATES[i], w, h)
        doc.layers += layer(doc, "Background", full)
        doc.layers += layer(doc, "Layer 1", full)
        doc.layers += layer(doc, "Layer 2", full)
        when (i) {
            0 -> Unit
            1 -> { doc.layers[1].mask = mask(doc, full); doc.layers[2].mask = mask(doc, full) }
            2 -> {
                val folder = Layer.newFolder(doc.newLayerId(), "Folder")
                val child = layer(doc, "Inside", full).also { it.parentId = folder.id }
                doc.layers += child
                doc.layers += folder
            }
            3 -> {
                // Array sources: one small, one of about two and a half layers on small canvases.
                doc.layers[1].array = LayerArray(ArraySpec(count = 4), ArrayPixels(bitmap(3, 2), 0, 0))
                val aw = minOf(2 * w + 1, 64)
                val ah = minOf(h + 1, 64)
                doc.layers[2].array = LayerArray(ArraySpec(count = 5), ArrayPixels(bitmap(aw, ah), 0, 0))
            }
            4 -> {
                doc.layers[1].mask = mask(doc, full); doc.layers[1].maskEnabled = false
                doc.layers[2].mask = mask(doc, full); doc.layers[2].maskEnabled = false
            }
            5 -> {
                // 95 more layers and one mask: 99 counted, one short of the largest cap.
                repeat(95) { doc.layers += layer(doc, "Layer ${it + 3}", full) }
                doc.layers[4].mask = mask(doc, full)
            }
        }
        return doc
    }

    // ------------------------------------------------------------------ the budget, 12 sizes x 6 states

    @Test
    fun everyMemberGivesVersion19sNumbersOnTwelveSizesAndSixStates() {
        val seen = mutableSetOf<Int>()
        for ((w, h) in SIZES) {
            for (s in STATES.indices) {
                val doc = state(s, w, h, full = false)
                val at = "${w}x$h ${STATES[s]}"
                val b = LayerBudgetV19
                val max = v19MaxLayers(doc)
                seen += max
                assertEquals("$at maxLayers", max, b.maxLayers(doc))
                assertEquals("$at used", v19EffectiveLayerCount(doc), b.used(doc))
                assertEquals("$at canAdd", v19CanAddLayer(doc), b.canAdd(doc))
                assertEquals("$at room", (max - v19EffectiveLayerCount(doc)).coerceAtLeast(0), b.room(doc))
                assertEquals("$at canAddAdjustment", v19CanAddAdjustmentLayer(doc), b.canAddAdjustment(doc))
                assertEquals("$at refusal", v19Message(doc), b.refusal(doc))
                assertEquals("$at refusal with the text suffix", TextThreads.LAYER_LIMIT.format(max), b.refusal(doc, TextThreads.LIMIT_SUFFIX))
                // ArraySources.hasRoom's arguments: new layers, added and freed array bytes.
                val per = max(1L, doc.layerBytes)
                val arrays = doc.layers.sumOf { it.array?.pixels?.bytes ?: 0L }
                for (n in 0..3) {
                    for (add in longArrayOf(0L, 1L, per - 1, per, per + 1, 3 * per)) {
                        for (freed in longArrayOf(0L, arrays, arrays + 5, per)) {
                            assertEquals("$at canAdd($n, +$add -$freed)", v19HasRoom(doc, n, add, freed), b.canAdd(doc, n, add - freed))
                        }
                    }
                }
                // The canvas operations: the budget and the size check with this document's bitmaps.
                assertEquals("$at canvasBudgetBytes", v19MemoryBudget(), b.canvasBudgetBytes())
                val bitmaps = CanvasSnapshot.of(doc).bitmapCount
                for ((nw, nh) in listOf(w to h, 2 * w to 2 * h, w / 2 + 1 to h, CanvasOps.MAX_SIDE + 1 to h, 0 to h)) {
                    assertEquals("$at canTransformCanvas(${nw}x$nh)", CanvasOps.validateSize(nw, nh, bitmaps, v19MemoryBudget()), b.canTransformCanvas(doc, nw, nh))
                }
            }
            // The gallery and import checks (no document).
            val at = "${w}x$h"
            assertEquals("$at rawMaxLayers", v19RawMaxLayers(w, h), LayerBudgetV19.rawMaxLayers(w, h))
            assertEquals("$at rawMaxLayers (CanvasLimits)", CanvasLimits.rawMaxLayers(w, h, heap), LayerBudgetV19.rawMaxLayers(w, h))
            assertEquals("$at canvasCheck", CanvasPresets.check(w, h, heap), LayerBudgetV19.canvasCheck(w, h))
            assertEquals("$at importMaxSide", CanvasLimits.importMaxSide(w, h, heap), LayerBudgetV19.importMaxSide(w, h))
            assertEquals("$at capacity", CanvasPresets.maxLayers(w, h, heap), LayerBudgetV19.capacity(w, h))
            assertEquals("$at capacity is the clamped raw number", v19RawMaxLayers(w, h).coerceIn(2L, 100L).toInt(), LayerBudgetV19.capacity(w, h))
            for (painted in intArrayOf(1, 2, 5, 120)) {
                val fits = CanvasPresets.check(w, h, heap).ok && v19RawMaxLayers(w, h) >= painted
                assertEquals("$at canvasFits($painted)", fits, LayerBudgetV19.canvasFits(w, h, painted))
            }
            assertEquals("$at renderCacheCap", v19RenderCap(), LayerBudgetV19.renderCacheCap())
        }
        // The sizes reach both ends of the clamp and a number between them.
        assertTrue("the largest cap is reached: $seen", 100 in seen)
        assertTrue("the smallest cap is reached: $seen", 2 in seen)
        assertTrue("a cap between the ends: $seen", seen.any { it in 3..99 })
        assertTrue(LayerBudgetV19.maxLayers(state(0, 1, 1, false)) <= LayerBudget.MAX_LAYERS_GUARD)
    }

    @Test
    fun theStatesCountAsVersion19Counted() {
        // Folders count 0, a mask counts 1 (also switched off), array sources by their bytes.
        assertEquals(3, LayerBudgetV19.used(state(0, 64, 64, false)))
        assertEquals(5, LayerBudgetV19.used(state(1, 64, 64, false)))
        assertEquals(4, LayerBudgetV19.used(state(2, 64, 64, false)))
        assertEquals(5, LayerBudgetV19.used(state(4, 64, 64, false)))
        assertEquals(99, LayerBudgetV19.used(state(5, 64, 64, false)))
        // 1 x 1 canvas (4 bytes a layer): two sources of 3 x 2 px, 24 bytes each, count 6 layers each.
        assertEquals(15, LayerBudgetV19.used(state(3, 1, 1, false)))
        // At the largest cap, 99 counted: one more layer fits, an adjustment layer and its mask do not.
        val near = state(5, 64, 64, false)
        assertEquals(100, LayerBudgetV19.maxLayers(near))
        assertTrue(LayerBudgetV19.canAdd(near))
        assertFalse(LayerBudgetV19.canAdd(near, 2))
        assertFalse(LayerBudgetV19.canAddAdjustment(near))
        assertEquals(1, LayerBudgetV19.room(near))
    }

    // ------------------------------------------------------------------ the sites, on a real editor

    private fun editor(doc: Document): EditorController {
        controller?.dispose()
        scope?.cancel()
        val sc = Smoke.newScope().also { scope = it }
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
    }

    @Test
    fun theEditorsMembersAndTheSitesGiveVersion19sNumbers() {
        for (s in STATES.indices) {
            val doc = state(s, EDITOR_W, EDITOR_H, full = true)
            val c = editor(doc)
            val at = STATES[s]
            assertEquals("$at maxLayers", v19MaxLayers(doc), c.maxLayers)
            assertEquals("$at effectiveLayerCount", v19EffectiveLayerCount(doc), c.effectiveLayerCount)
            assertEquals("$at canAddLayer", v19CanAddLayer(doc), c.canAddLayer)
            assertEquals("$at canAddAdjustmentLayer", v19CanAddAdjustmentLayer(doc), c.canAddAdjustmentLayer)
            assertEquals("$at layerLimitMessage", v19Message(doc), c.layerLimitMessage())
            assertEquals("$at text refusal", TextThreads.LAYER_LIMIT.format(v19MaxLayers(doc)), c.layerLimitMessage(TextThreads.LIMIT_SUFFIX))
            val per = doc.layerBytes
            for (n in 0..2) for (add in longArrayOf(0L, per, 2 * per + 1)) for (freed in longArrayOf(0L, per)) {
                assertEquals("$at hasRoom($n, $add, $freed)", v19HasRoom(doc, n, add, freed), ArraySources.hasRoom(c, n, add, freed))
            }
            assertEquals("$at CanvasOps.memoryBudget", v19MemoryBudget(), CanvasOps.memoryBudget())
            assertEquals("$at array patch budget", v19RenderCap(), c.arrayRenders.patchBudget())
        }
    }

    @Test
    fun everySiteReadsTheCurrentPolicy() {
        val c = editor(state(0, EDITOR_W, EDITOR_H, full = true))
        val asked = mutableListOf<String>()
        LayerBudget.current = object : LayerBudget by LayerBudgetV19 {
            override fun used(doc: Document): Int = 41
            override fun maxLayers(doc: Document): Int = 42
            override fun canAdd(doc: Document, layers: Int, extraBytes: Long): Boolean { asked += "canAdd($layers, $extraBytes)"; return layers == 7 }
            override fun canAddAdjustment(doc: Document): Boolean = true
            override fun refusal(doc: Document, suffix: String?): String = "stand-in: $suffix"
            override fun canvasBudgetBytes(): Long = 4321L
            override fun renderCacheCap(): Long = 1234L
        }
        assertEquals(42, c.maxLayers)
        assertEquals(41, c.effectiveLayerCount)
        asked.clear()
        assertFalse(c.canAddLayer)
        assertEquals(listOf("canAdd(1, 0)"), asked)
        assertTrue(c.canAddAdjustmentLayer)
        assertEquals("stand-in: null", c.layerLimitMessage())
        assertEquals("stand-in: ${TextThreads.LIMIT_SUFFIX}", c.layerLimitMessage(TextThreads.LIMIT_SUFFIX))
        asked.clear()
        assertTrue(ArraySources.hasRoom(c, 7, 500L, 200L))
        assertEquals("hasRoom passes the added minus the freed bytes", listOf("canAdd(7, 300)"), asked)
        assertEquals(4321L, CanvasOps.memoryBudget())
        assertEquals(1234L, c.arrayRenders.patchBudget())
        LayerBudget.current = LayerBudgetV19
        assertSame(LayerBudgetV19, LayerBudget.current)
        assertEquals(v19MaxLayers(c.doc), c.maxLayers)
    }

    private companion object {
        const val EDITOR_W = 64
        const val EDITOR_H = 48

        val STATES = listOf("plain", "masks", "folder", "array sources", "masks off", "near 100 layers")

        /** 12 sizes: the largest cap, between the ends, the smallest cap, and sizes the gallery refuses. */
        val SIZES = listOf(
            1 to 1, 64 to 64, 512 to 384, 1080 to 1920, 2048 to 2048, 2480 to 3508,
            3000 to 4000, 4096 to 4096, 5000 to 5000, 6000 to 8000, 8000 to 8000, 10_000 to 10_000,
        )
    }
}
