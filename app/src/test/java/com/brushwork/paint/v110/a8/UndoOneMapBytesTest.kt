package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A8 step 4 (design 2.6, I28; CLASSIC, where v1.9's numbers hold): a pixel edit's step
 * holds ONE map of undo tiles, so it costs 1x its touched tiles, as in v1.9 (two 256 x 256 tiles
 * = 2 x 256 KiB; an edge tile its own size), counted once under TILES and never more however
 * often it is undone and redone (each swap lets go of the tile it copies in). A fully transparent
 * piece is null and costs nothing. `byteSize` is fixed at construction. A delete counts the
 * logical bytes of the content and mask.
 */
@RunWith(RobolectricTestRunner::class)
class UndoOneMapBytesTest {
    @get:Rule
    val engine = EngineRule()

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    /** An editor over [n] 600 x 300 layers filled with [fill] (transparent when null), the top one active. */
    private fun editor(fill: Int?, n: Int = 1): EditorController {
        val doc = Document("a8", "a8", 600, 300).also { docs += it }
        repeat(n) { i ->
            val b = BitmapUtils.createLayerBitmap(600, 300).also { if (fill != null) it.eraseColor(fill); made += it }
            doc.layers += Layer(doc.newLayerId(), "Layer $i", b)
        }
        doc.activeLayerIndex = n - 1
        val sc = Smoke.newScope().also { scope = it }
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
    }

    private fun bytes(w: Int, h: Int): Long = w.toLong() * h * 4

    @Test
    fun aStepCostsItsTouchedTilesOnceAsInV19() {
        val c = editor(0xFF808080.toInt())
        val l = c.doc.activeLayer
        val before = A8Kit.tiles()
        A8Kit.paint(c, l, Rect(10, 10, 300, 200), 0xFF3366AA.toInt())
        val step = A8Kit.newestStep(c)
        val v19 = 2 * bytes(256, 256)
        assertEquals("two 256 x 256 tiles, as v1.9's two saved bitmaps", v19, step.byteSize)
        assertEquals(2, A8Kit.tilesOfStep(step).size)
        assertEquals("the map is counted once", before + v19, A8Kit.tiles())
        repeat(3) {
            c.undo()
            assertEquals("fixed at construction", v19, step.byteSize)
            assertEquals("undone: swapped, never held twice", before + v19, A8Kit.tiles())
            c.redo()
            assertEquals(v19, step.byteSize)
            assertEquals("redone: swapped, never held twice", before + v19, A8Kit.tiles())
        }
    }

    @Test
    fun anEdgeTileCostsItsOwnSize() {
        val c = editor(0xFF808080.toInt())
        A8Kit.paint(c, c.doc.activeLayer, Rect(500, 260, 600, 300), 0xFF3366AA.toInt())
        val step = A8Kit.newestStep(c)
        // Columns 1 (256..512) and 2 (512..600, 88 wide) of row 1 (256..300, 44 high).
        assertEquals(bytes(256, 44) + bytes(88, 44), step.byteSize)
        assertEquals(listOf(256 to 44, 88 to 44).sortedBy { it.first }, A8Kit.tilesOfStep(step).map { it.w to it.h }.sortedBy { it.first })
    }

    @Test
    fun aTransparentPieceIsNullAndCostsNothing() {
        val c = editor(null)
        val l = c.doc.activeLayer
        val before = A8Kit.tiles()
        val clear = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(10, 10, 300, 200), 0xFF3366AA.toInt())
        val painted = A8Kit.pixelsOf(l).first
        val step = A8Kit.newestStep(c)
        assertEquals(0L, step.byteSize)
        assertTrue("no tile for transparent pieces", A8Kit.tilesOfStep(step).isEmpty())
        assertEquals(before, A8Kit.tiles())

        c.undo()
        assertTrue("cleared back", clear.contentEquals(A8Kit.pixelsOf(l).first))
        assertEquals("fixed at construction", 0L, step.byteSize)
        c.redo()
        assertTrue("painted again", painted.contentEquals(A8Kit.pixelsOf(l).first))
        assertTrue("transparent again after the redo: no tile", A8Kit.tilesOfStep(step).isEmpty())
        assertEquals(before, A8Kit.tiles())
    }

    @Test
    fun aDeleteCountsTheLogicalBytesOfContentAndMask() {
        val c = editor(0xFF808080.toInt(), n = 3)
        val masked = c.doc.layers[1]
        masked.mask = BitmapUtils.createMaskBitmap(600, 300).also { made += it }
        c.deleteLayer(masked)
        assertEquals(2, c.doc.layers.size)
        assertEquals(bytes(600, 300) * 2, A8Kit.newestStep(c).byteSize)
        c.deleteLayer(c.doc.layers[0])
        assertEquals(bytes(600, 300), A8Kit.newestStep(c).byteSize)
    }
}
