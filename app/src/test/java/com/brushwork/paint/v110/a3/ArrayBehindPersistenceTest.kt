package com.brushwork.paint.v110.a3

import android.graphics.Color
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayLookKit.RED_TO_BLUE
import com.brushwork.paint.array.ArrayLookKit.pixels
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels110
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.10 (R4, R5; A3 review): a copies-only layer of every source kind (selected pixels, selected
 * objects, a whole text and a whole shape layer), some Recolored, survives save and reopen: it
 * comes back directly below its source with `hideSource` and the look's `colorMode`, the same
 * data and the same cache (the source's place empty), still says
 * [ArrayLabels110.REMOVE_WITH_LAYER], and stays live (the same edit after reopening gives the same
 * pixels as before saving, copy 0 still left out).
 */
@RunWith(RobolectricTestRunner::class)
class ArrayBehindPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val root get() = File(app.filesDir, "projects")

    @Before
    fun setUp() {
        root.deleteRecursively()
    }

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun save(doc: Document) = runBlocking { ProjectRepository(app).save(doc, null) }

    private fun load(id: String): Document = runBlocking { ProjectRepository(app).load(id) }

    /**
     * Leaves the Array tool for the Brush, as a user does once the copies are set: [source] is
     * selected again, so the next new layer goes above it and the copies stay directly below it.
     */
    private fun leave(c: EditorController, source: Layer) {
        c.selectTool(ToolId.BRUSH)
        ArrayLookKit.settle(c)
        assertSame("leaving selects the source", source, c.activeLayer)
    }

    /** The four copies-only layers, by kind, each paired with its source; two of them Recolored. */
    private fun behindLayers(c: EditorController): Map<String, Pair<Layer, Layer>> {
        val raster = BehindKit.selectedSquare(c)
        assertTrue(ArrayBehind.fromSelectionBelow(c))
        ArrayLookKit.settle(c)
        val px = c.activeLayer
        ArrayLookKit.edit(c, px, px.array!!.spec.copy(count = 4, look = RECOLORED))
        leave(c, raster)
        c.setSelection(null, recordUndo = false)

        val text = BehindKit.textLayer(c)
        assertTrue(ArrayBehind.fromLayerBelow(c, text))
        ArrayLookKit.settle(c)
        val tx = c.activeLayer
        leave(c, text)

        val o = ShapeObject(ShapeType.ELLIPSE, cx = 60f, cy = 200f, w = 40f, h = 30f, style = ShapeStyle.FILL, strokeColor = ArrayLookKit.GRAY, fillColor = ArrayLookKit.GRAY)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, c.doc.colorMode, c.doc.width, c.doc.height))!!
        assertTrue(ArrayBehind.fromLayerBelow(c, shape))
        ArrayLookKit.settle(c)
        val sh = c.activeLayer
        ArrayLookKit.edit(c, sh, sh.array!!.spec.copy(look = RECOLORED))
        leave(c, shape)

        val v = BehindKit.twoBoxes(c)
        val first = v.vector!!.objects[0].id
        c.vectors.setSelection(v, setOf(first))
        assertTrue(ArrayBehind.fromObjectsBelow(c, setOf(first)))
        ArrayLookKit.settle(c)
        val ob = c.activeLayer
        ArrayLookKit.edit(c, ob, ob.array!!.spec.copy(count = 4))
        leave(c, v)
        c.vectors.flushPending()
        ArrayLookKit.settle(c)
        return mapOf("pixels" to (raster to px), "text" to (text to tx), "shape" to (shape to sh), "objects" to (v to ob))
    }

    @Test
    fun copiesOnlyLayersSurviveSaveAndReopenAndStayLive() {
        val c = ArrayLookKit.controller(app)
        val made = behindLayers(c)
        for ((kind, p) in made) {
            assertTrue("$kind: copies only", ArrayBehind.isCopiesOnly(p.second))
            assertEquals("$kind: below its source", c.doc.indexOf(p.first) - 1, c.doc.indexOf(p.second))
        }
        save(c.doc)
        val loaded = load(c.doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val c2 = Smoke.controller(app, loaded)
        fun twin(l: Layer) = loaded.layers[c.doc.indexOf(l)]

        for ((kind, p) in made) {
            val (src, l) = p
            val back = twin(l)
            assertNotNull("$kind: still an array", back.array)
            assertTrue("$kind: still copies only", ArrayBehind.isCopiesOnly(back))
            assertEquals("$kind: the spec", l.array!!.spec, back.array!!.spec)
            assertEquals("$kind: still directly below its source", loaded.indexOf(twin(src)) - 1, loaded.indexOf(back))
            assertEquals(kind, l.name, back.name)
            assertEquals(kind, l.textData, back.textData)
            assertEquals(kind, l.shapeData, back.shapeData)
            assertEquals(kind, l.vector, back.vector)
            l.array!!.pixels?.let { px ->
                val bp = requireNotNull(back.array!!.pixels) { "$kind: the source pixels" }
                assertEquals(kind, px.left to px.top, bp.left to bp.top)
                assertArrayEquals("$kind: the source pixels", pixels(px.bitmap), pixels(bp.bitmap))
            }
            assertArrayEquals("$kind: the cache", pixels(l.bitmap), pixels(back.bitmap))
            assertEquals(kind, ArrayLabels110.REMOVE_WITH_LAYER, ArrayBehind.removeLabel(back))
        }
        assertEquals("Recolored kept", ArrayColorMode.RECOLOR, twin(made.getValue("pixels").second).array!!.spec.look!!.colorMode)
        assertEquals(ArrayColorMode.RECOLOR, twin(made.getValue("shape").second).array!!.spec.look!!.colorMode)
        assertEquals("the source's place empty", 0, Color.alpha(twin(made.getValue("pixels").second).bitmap.getPixel(40, 50)))

        // Live after reopening: the same edit gives the same pixels, the source still left out.
        for (ctrl in listOf(c, c2)) {
            for (p in made.values) {
                val l = if (ctrl === c) p.second else twin(p.second)
                val s = l.array!!.spec
                assertTrue(ArrayOps.edit(ctrl, l, s.copy(count = s.count + 1)))
            }
            ctrl.vectors.flushPending()
            ArrayLookKit.settle(ctrl)
        }
        for ((kind, p) in made) {
            val l = p.second
            val back = twin(l)
            assertEquals(kind, l.array!!.spec, back.array!!.spec)
            assertTrue(kind, back.array!!.spec.hideSource)
            assertArrayEquals("$kind: edited after reopening", pixels(l.bitmap), pixels(back.bitmap))
            assertFalse("$kind: the copies show", pixels(back.bitmap).all { it == 0 })
        }
        assertEquals("edited: the source's place still empty", 0, Color.alpha(twin(made.getValue("pixels").second).bitmap.getPixel(40, 50)))
        Smoke.assertQuiet(c2, "reopened copies-only layers")
    }

    private companion object {
        /** A Recolored tint, red (first copy) to blue (last copy) at 70 %. */
        val RECOLORED = ArrayLook(tint = RED_TO_BLUE, tintStrength = 0.7f, colorMode = ArrayColorMode.RECOLOR)
    }
}
