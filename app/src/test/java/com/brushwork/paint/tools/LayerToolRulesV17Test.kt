package com.brushwork.paint.tools

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 F5 (design §4.4, "`LayerToolRules.refusal` (F5)"): the folder table for every [ToolId]
 * (the tools that write into the active layer are refused on a folder with "Choose a layer
 * inside the folder to paint"; the rest, Pathfinder and Symmetry included, are allowed); a child
 * of a locked folder is refused like a locked layer and a child of a hidden folder like a hidden
 * one (the gate is `checkUsable`, rule L; the per-tool gesture outcomes are `FolderAuditTest`'s);
 * Array accepts raster, text, shape and vector layers; Pathfinder has no layer refusal at all.
 */
@RunWith(RobolectricTestRunner::class)
class LayerToolRulesV17Test {
    private val w = 64
    private val h = 48

    /** Refused on a folder (§4.4). */
    private val refusedOnFolder = setOf(
        ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR, ToolId.FILL, ToolId.CLONE, ToolId.REMOVE,
        ToolId.FRAME_DIVIDER, ToolId.MASK, ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.ARRAY,
    )

    /** Allowed on a folder (§4.4), each for its reason. */
    private val allowedOnFolder = setOf(
        ToolId.TRANSFORM, ToolId.SYMMETRY, ToolId.RULER, ToolId.LASSO, ToolId.MARQUEE, ToolId.EYEDROPPER,
        ToolId.MAGIC_WAND, ToolId.OBJECT_SELECT, ToolId.TEXT, ToolId.TEXT_FRAMES, ToolId.SHAPE, ToolId.PATHFINDER,
    )

    private fun raster(doc: Document, name: String, parent: Long = Layer.ROOT_ID) =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { it.parentId = parent }

    /** Bottom first: Background, [Child] Folder, Top; Child active. */
    private fun withFolder(): Pair<EditorController, Document> {
        val doc = Document("rules", "rules", w, h)
        val bg = raster(doc, "Background")
        val folder = Layer.newFolder(doc.newLayerId(), "Folder")
        val child = raster(doc, "Child", folder.id)
        val top = raster(doc, "Top")
        doc.layers += listOf(bg, child, folder, top)
        doc.activeLayerIndex = 1
        assertNull(LayerTree.check(doc.layers))
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc) to doc
    }

    private fun Document.byName(name: String) = layers.first { it.name == name }

    @Test
    fun everyToolIsClassifiedOnAFolder() {
        assertTrue("disjoint", (refusedOnFolder intersect allowedOnFolder).isEmpty())
        assertEquals("every ToolId is in the table", ToolId.entries.toSet(), refusedOnFolder + allowedOnFolder)
        assertEquals(refusedOnFolder, LayerToolRules.FOLDER_REFUSED)
        val (_, doc) = withFolder()
        val folder = doc.byName("Folder")
        for (id in ToolId.entries) {
            val expected = if (id in refusedOnFolder) FolderLabels.PAINT_REFUSAL else null
            assertEquals("$id on a folder", expected, LayerToolRules.refusal(id, folder))
        }
        assertEquals("Choose a layer inside the folder to paint", FolderLabels.PAINT_REFUSAL)
    }

    @Test
    fun aLockedFoldersChildIsRefusedLikeALockedLayer() {
        val (c, doc) = withFolder()
        val child = doc.byName("Child")
        doc.byName("Folder").locked = true
        for (id in ToolId.entries) {
            // The tool table itself sees the child as the raster layer it is...
            assertEquals("$id on a locked folder's child", LayerToolRules.refusal(id, doc.byName("Top")), LayerToolRules.refusal(id, child))
        }
        // ...and the lock gate refuses it as it refuses a locked layer, naming the folder.
        c.message = null
        assertFalse(c.checkUsable(child))
        assertEquals(FolderLabels.locked("Folder"), c.message)
        assertFalse(c.checkEditable(child))
        val top = doc.byName("Top").also { it.locked = true }
        assertFalse(c.checkUsable(top))
        assertEquals("Layer \"Top\" is locked", c.message)
        // Unlocked again, the child is usable.
        doc.byName("Folder").locked = false
        assertTrue(c.checkUsable(child))
    }

    @Test
    fun aHiddenFoldersChildIsRefusedLikeAHiddenLayer() {
        val (c, doc) = withFolder()
        val child = doc.byName("Child")
        doc.byName("Folder").visible = false
        for (id in ToolId.entries) {
            assertEquals("$id on a hidden folder's child", LayerToolRules.refusal(id, doc.byName("Top")), LayerToolRules.refusal(id, child))
        }
        c.message = null
        assertFalse(c.checkUsable(child))
        assertEquals(FolderLabels.hidden("Folder"), c.message)
        // As a hidden layer, it may still be used where hidden layers may.
        assertTrue(c.checkUsable(child, allowHidden = true))
        val top = doc.byName("Top").also { it.visible = false }
        assertFalse(c.checkUsable(top))
        assertEquals("Layer \"Top\" is hidden", c.message)
    }

    @Test
    fun arrayAcceptsEveryContentLayerAndPathfinderAndSymmetryEveryLayer() {
        val doc = Document("kinds", "kinds", w, h)
        val plain = raster(doc, "Raster")
        val text = raster(doc, "Text").also { it.textData = "{}" }
        val shape = raster(doc, "Shape").also { it.shapeData = "{}" }
        val vector = raster(doc, "Vector").also { it.vector = VectorContent.EMPTY }
        val adjustment = raster(doc, "Adjustment").also { it.adjustment = AdjustmentSpec() }
        val folder = Layer.newFolder(doc.newLayerId(), "Folder")
        for (l in listOf(plain, text, shape, vector)) {
            assertNull("Array on ${l.name}", LayerToolRules.refusal(ToolId.ARRAY, l))
        }
        assertEquals(FolderLabels.PAINT_REFUSAL, LayerToolRules.refusal(ToolId.ARRAY, folder))
        for (l in listOf(plain, text, shape, vector, adjustment, folder)) {
            assertNull("Pathfinder on ${l.name}", LayerToolRules.refusal(ToolId.PATHFINDER, l))
            assertNull("Symmetry on ${l.name}", LayerToolRules.refusal(ToolId.SYMMETRY, l))
        }
        assertFalse(ToolId.ARRAY in LayerToolRules.PIXEL_ONLY)
        assertFalse(ToolId.PATHFINDER in LayerToolRules.FOLDER_REFUSED)
        assertFalse(ToolId.SYMMETRY in LayerToolRules.FOLDER_REFUSED)
    }
}
