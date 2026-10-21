package com.brushwork.paint.v110.lead

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LayerStructure
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.smoke.Smoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 F0 (R4, design 3.2 "engine/LayerStructure.kt"; lead, frozen): [LayerStructure.below] puts
 * a new layer directly below a layer at its level, in its folder, so the layer stays right above
 * it ("Array behind"). Without folders (the v1.6 add step), as the bottom child of a folder, right
 * above a folder block (not into the folder), below a clip base (the clipping group keeps its base)
 * and below a clipped layer with its clipping copied (both clip onto the same base). Each insert
 * is one step, leaves a valid tree and makes the new layer active; undo takes it back.
 */
@RunWith(RobolectricTestRunner::class)
class BelowInsertionTest {
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        controller?.dispose()
        scope?.cancel()
    }

    private fun layer(doc: Document, name: String, parent: Layer? = null): Layer =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(W, H)).also { if (parent != null) it.parentId = parent.id }

    private fun editor(build: (Document) -> Unit): EditorController {
        val doc = Document("below", "below", W, H)
        build(doc)
        assertNull("the fixture is a valid tree", LayerTree.check(doc.layers))
        val sc = Smoke.newScope().also { scope = it }
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
    }

    private fun names(c: EditorController): List<String> = c.doc.layers.map { it.name }

    /** Inserts a new layer "New" below [source] (its clipping copied when [copyClipping]); checks the step and returns it. */
    private fun insertBelow(c: EditorController, source: Layer, copyClipping: Boolean = false): Layer {
        val steps = c.undoManager.undoCount
        val at = c.structure.below(source)
        assertEquals("below takes the source's place", c.doc.indexOf(source), at.index)
        assertEquals("in the source's folder", source.parentId, at.parentId)
        val added = layer(c.doc, "New").also { if (copyClipping) it.clipping = source.clipping }
        assertTrue(c.structure.insert(added, at, LABEL))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("the source stays right above it", c.doc.indexOf(added) + 1, c.doc.indexOf(source))
        assertEquals(source.parentId, added.parentId)
        assertSame("the new layer is active", added, c.doc.activeLayer)
        assertNull("a valid tree", LayerTree.check(c.doc.layers))
        return added
    }

    private fun undo(c: EditorController, before: List<String>, added: Layer) {
        c.undo()
        assertEquals("undo takes it back", before, names(c))
        assertEquals(-1, c.doc.indexOf(added))
        assertNull(LayerTree.check(c.doc.layers))
    }

    @Test
    fun withoutFolders() {
        lateinit var a: Layer
        val c = editor { d -> d.layers += layer(d, "Background"); a = layer(d, "A"); d.layers += a; d.layers += layer(d, "B") }
        val before = names(c)
        val added = insertBelow(c, a)
        assertEquals(listOf("Background", "New", "A", "B"), names(c))
        assertEquals(Layer.ROOT_ID, added.parentId)
        undo(c, before, added)
    }

    @Test
    fun belowTheBottomChildOfAFolder() {
        lateinit var bottom: Layer
        lateinit var folder: Layer
        val c = editor { d ->
            d.layers += layer(d, "Background")
            folder = Layer.newFolder(d.newLayerId(), "Folder")
            bottom = layer(d, "Child 1", folder)
            d.layers += bottom
            d.layers += layer(d, "Child 2", folder)
            d.layers += folder
        }
        val before = names(c)
        val added = insertBelow(c, bottom)
        assertEquals(listOf("Background", "New", "Child 1", "Child 2", "Folder"), names(c))
        assertEquals("inside the folder", folder.id, added.parentId)
        assertEquals(listOf("New", "Child 1", "Child 2"), c.doc.layers.filter { it.parentId == folder.id }.map { it.name })
        undo(c, before, added)
    }

    @Test
    fun rightAboveAFolderBlockNotIntoTheFolder() {
        lateinit var top: Layer
        lateinit var folder: Layer
        val c = editor { d ->
            d.layers += layer(d, "Background")
            folder = Layer.newFolder(d.newLayerId(), "Folder")
            d.layers += layer(d, "Child", folder)
            d.layers += folder
            top = layer(d, "Top")
            d.layers += top
        }
        val before = names(c)
        val added = insertBelow(c, top)
        assertEquals(listOf("Background", "Child", "Folder", "New", "Top"), names(c))
        assertEquals("at the top level", Layer.ROOT_ID, added.parentId)
        assertEquals("the folder keeps its one child", listOf("Child"), c.doc.layers.filter { it.parentId == folder.id }.map { it.name })
        undo(c, before, added)
    }

    @Test
    fun belowAClipBaseTheGroupKeepsItsBase() {
        lateinit var base: Layer
        lateinit var clipped: Layer
        val c = editor { d ->
            d.layers += layer(d, "Background")
            base = layer(d, "Base"); d.layers += base
            clipped = layer(d, "Clipped").also { it.clipping = true }; d.layers += clipped
        }
        val l = c.doc.layers
        assertTrue(LayerTree.isClipBase(l, l.indexOf(base)))
        val before = names(c)
        val added = insertBelow(c, base, copyClipping = true)
        assertEquals(listOf("Background", "New", "Base", "Clipped"), names(c))
        assertFalse("the base's copy does not clip", added.clipping)
        assertFalse(LayerTree.isClipped(l, l.indexOf(added)))
        assertFalse(LayerTree.isClipBase(l, l.indexOf(added)))
        assertTrue("the base is still the base", LayerTree.isClipBase(l, l.indexOf(base)))
        assertTrue(LayerTree.isClipped(l, l.indexOf(clipped)))
        undo(c, before, added)
        assertTrue(LayerTree.isClipBase(c.doc.layers, c.doc.layers.indexOf(base)))
    }

    @Test
    fun belowAClippedLayerBothClipOntoTheSameBase() {
        lateinit var base: Layer
        lateinit var clipped: Layer
        val c = editor { d ->
            d.layers += layer(d, "Background")
            base = layer(d, "Base"); d.layers += base
            clipped = layer(d, "Clipped").also { it.clipping = true }; d.layers += clipped
        }
        val before = names(c)
        val added = insertBelow(c, clipped, copyClipping = true)
        val l = c.doc.layers
        assertEquals(listOf("Background", "Base", "New", "Clipped"), names(c))
        assertTrue("the copy clips", LayerTree.isClipped(l, l.indexOf(added)))
        assertTrue("the source still clips", LayerTree.isClipped(l, l.indexOf(clipped)))
        assertTrue("onto the same base", LayerTree.isClipBase(l, l.indexOf(base)))
        assertFalse(LayerTree.isClipBase(l, l.indexOf(added)))
        undo(c, before, added)
        assertTrue(LayerTree.isClipped(c.doc.layers, c.doc.layers.indexOf(clipped)))
    }

    private companion object {
        const val W = 64
        const val H = 48
        const val LABEL = "Below"
    }
}
