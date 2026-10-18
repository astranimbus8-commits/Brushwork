package com.brushwork.paint.v19.a4

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.v19.a4.A4Kit.Companion.assertClose
import com.brushwork.paint.v19.a4.A4Kit.Companion.premultiplied
import com.brushwork.paint.vector.VPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R19, A4 step 8): "Merge folder" keeps the type when the folder's shown children all merge
 * by type: one vector layer of their objects (a shape converted, an exact opacity folded) or one
 * text layer of their texts, the folder's name, ONE step "Merge folder" that undo restores; its
 * pixels are the folder's composite, as close to a fresh rendering of its data (I1). Anything else
 * (a raster child, two kinds, a blend, a folder inside, faded texts, an opacity that does not fold)
 * merges as pixels, as in v1.8.
 */
@RunWith(RobolectricTestRunner::class)
class MergeFolderKeepsTypeTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    private val red = 0xFFE04020.toInt()
    private val blue = 0xFF3070D0.toInt()

    /** A controller with an open "Folder 1" above the Background; the layers [build] adds go into it. */
    private fun folder(build: (EditorController) -> Unit): Pair<EditorController, Layer> {
        val c = kit.controller()
        val f = c.addFolder()!!
        build(c)
        assertTrue("every layer built is in the folder", c.doc.layers.filter { it !== f && it.name != "Background" }.all { it.parentId != Layer.ROOT_ID })
        kit.settle(c)
        return c to f
    }

    /** Merges [f] as one step "Merge folder" with the picture unchanged; the merged layer. */
    private fun merge(why: String, c: EditorController, f: Layer): Layer {
        val before = kit.picture(c)
        val steps = c.undoManager.undoCount
        assertTrue(why, c.mergeFolder(f))
        kit.settle(c)
        assertEquals("$why: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(FolderLabels.MERGE, c.undoManager.undoLabel)
        assertEquals("$why: the folder's name", listOf("Background", "Folder 1"), c.doc.layers.map { it.name })
        val merged = c.doc.layers[1]
        assertFalse(merged.isFolder)
        assertSame(merged, c.activeLayer)
        assertClose("$why: the picture", before, kit.picture(c))
        return merged
    }

    @Test
    fun objectsAndShapesKeepTheirObjectsInOneVectorLayer() {
        lateinit var a: Layer
        lateinit var s: Layer
        val (c, f) = folder { c ->
            // Two apart objects at 50 % (the opacity folds exactly), a shape over the first one.
            a = kit.vectorLayer(c, listOf(kit.box(20f, 20f, 80f, 70f, red), kit.box(140f, 100f, 210f, 160f, blue)), "A")
            c.setLayerProps(a, a.props().copy(opacity = 0.5f))
            s = kit.shapeLayer(c, kit.rect(70f, 60f, 60f, 40f), "S")
        }
        assertEquals(listOf("Background", "A", "S", "Folder 1"), c.doc.layers.map { it.name })
        val merged = merge("objects", c, f)
        assertTrue(merged.isVectorLayer)
        assertNull(merged.textData)
        assertNull(merged.shapeData)
        val objects = merged.vector!!.objects
        assertEquals("both layers' objects, bottom first", 3, objects.size)
        assertEquals(3, objects.map { it.id }.toSet().size)
        assertEquals("the 50 % folded into A's objects", listOf(0.5f, 0.5f), objects.take(2).map { (it as VPath).opacity })
        assertEquals("the folder's opacity", 1f, merged.opacity)
        // (3 levels, as where an opacity folds in "Merge down": MergeExactTest.)
        assertClose("pixels and data", premultiplied(kit.render(merged.vector!!)), premultiplied(kit.pixels(merged.bitmap)), 3)

        c.undo()
        assertEquals(listOf("Background", "A", "S", "Folder 1"), c.doc.layers.map { it.name })
        assertTrue(f.isFolder)
        assertEquals(0.5f, a.opacity)
        assertTrue(s.isShapeLayer)
        c.redo()
        assertEquals(listOf("Background", "Folder 1"), c.doc.layers.map { it.name })
        assertEquals(3, c.doc.layers[1].vector!!.objects.size)
    }

    @Test
    fun textsKeepTheirTextsInOneTextLayer() {
        val (c, f) = folder { c ->
            kit.textLayer(c, kit.text("One", 60f, 40f), kit.text("Three", 60f, 100f), name = "Lower")
            kit.textLayer(c, kit.text("Two", 160f, 140f, red), name = "Upper")
        }
        val merged = merge("texts", c, f)
        assertTrue(merged.isTextLayer)
        assertNull(merged.vector)
        val items = TextCodec.decodeAll(merged.textData)
        assertEquals("the texts, bottom first", listOf("One", "Three", "Two"), items.map { it.text })
        assertEquals(3, items.map { it.id }.toSet().size)
        assertClose("pixels and data", premultiplied(kit.renderTexts(merged)), premultiplied(kit.pixels(merged.bitmap)))
        c.undo()
        assertEquals(listOf("Background", "Lower", "Upper", "Folder 1"), c.doc.layers.map { it.name })
    }

    @Test
    fun aHiddenChildIsLeftOutAsTheCompositeLeavesItOut() {
        val (c, f) = folder { c ->
            kit.vectorLayer(c, listOf(kit.box(20f, 20f, 80f, 70f, red)), "A")
            val b = kit.vectorLayer(c, listOf(kit.box(60f, 50f, 150f, 130f, blue), kit.box(160f, 40f, 220f, 90f)), "B")
            c.setLayerProps(b, b.props().copy(visible = false))
        }
        val merged = merge("hidden", c, f)
        assertEquals("A's object only", 1, merged.vector!!.objects.size)
        assertClose("pixels and data", premultiplied(kit.render(merged.vector!!)), premultiplied(kit.pixels(merged.bitmap)))
    }

    @Test
    fun anythingElseMergesAsPixelsAsInV18() {
        val cases: List<Pair<String, (EditorController) -> Unit>> = listOf(
            "a raster child" to { c ->
                kit.vectorLayer(c, listOf(kit.box(20f, 20f, 80f, 70f, red)), "A")
                c.addLayer("Paint")!!
            },
            "texts and objects" to { c ->
                kit.textLayer(c, kit.text("One", 60f, 40f), name = "T")
                kit.vectorLayer(c, listOf(kit.box(120f, 90f, 200f, 150f, blue)), "A")
            },
            "a Multiply child" to { c ->
                // (Isolated, so the merged pixels show as the folder did.)
                c.setFolderPassThrough(c.activeLayer, false)
                kit.vectorLayer(c, listOf(kit.box(20f, 20f, 80f, 70f, red)), "A")
                val m = kit.vectorLayer(c, listOf(kit.box(60f, 50f, 150f, 130f, blue)), "M")
                c.setLayerProps(m, m.props().copy(blendMode = LayerBlendMode.MULTIPLY))
            },
            "a folder inside" to { c ->
                kit.vectorLayer(c, listOf(kit.box(20f, 20f, 80f, 70f, red)), "A")
                c.addFolder()!!
                kit.vectorLayer(c, listOf(kit.box(60f, 50f, 150f, 130f, blue)), "B")
            },
            "texts at 50 %" to { c ->
                val t = kit.textLayer(c, kit.text("One", 60f, 40f), name = "T")
                c.setLayerProps(t, t.props().copy(opacity = 0.5f))
                kit.textLayer(c, kit.text("Two", 160f, 140f, red), name = "U")
            },
            "overlapping objects at 50 %" to { c ->
                val a = kit.vectorLayer(c, listOf(kit.box(20f, 20f, 100f, 90f, red), kit.box(60f, 50f, 150f, 130f, blue)), "A")
                c.setLayerProps(a, a.props().copy(opacity = 0.5f))
            },
        )
        for ((why, build) in cases) {
            val (c, f) = folder(build)
            val layers = c.doc.layers.toList()
            val merged = merge(why, c, f)
            assertNull("$why: no texts", merged.textData)
            assertNull("$why: no shape", merged.shapeData)
            assertNull("$why: no objects", merged.vector)
            c.undo()
            assertEquals("$why: undone", layers, c.doc.layers.toList())
        }
    }
}
