package com.brushwork.paint.ui.layers

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.ui.common.FolderLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (§4.4): the layer window's "Merge down" merges into the layer below AT ITS LEVEL. A
 * folder's bottom child has none (the row below it belongs to another level: no lock prompt
 * about it, no step), a folder below is refused with "Can't merge into a folder", and a sibling
 * below merges as in v1.6.
 */
@RunWith(RobolectricTestRunner::class)
class LayerOpsMergeDownFolderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    /** Bottom first: L1, [L2, L3] F1, L4 (F1 holds L2 and L3); L1 is locked. */
    private fun controller(): Pair<EditorController, List<Layer>> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 20, 16)
        fun pixel(name: String, parent: Long = Layer.ROOT_ID) =
            Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(20, 16).also { it.eraseColor(0xFF336699.toInt()) }).also { it.parentId = parent }
        val l1 = pixel("L1").also { it.locked = true }
        val f1 = Layer.newFolder(doc.newLayerId(), "F1")
        val l2 = pixel("L2", f1.id)
        val l3 = pixel("L3", f1.id)
        val l4 = pixel("L4")
        doc.layers += listOf(l1, l2, l3, f1, l4)
        doc.activeLayerIndex = 4
        assertNull(LayerTree.check(doc.layers))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return c to listOf(l1, l2, l3, f1, l4)
    }

    @Test
    fun aFoldersBottomChildHasNoLayerBelow() {
        val (c, l) = controller()
        assertFalse(LayerOps.canMergeDown(c, l[1]))
        LayerOps.mergeDown(c, l[1])
        assertEquals("not a lock prompt about L1, which is not at its level", "There is no layer below to merge into", c.message)
        assertEquals(l, c.doc.layers.toList())
        assertFalse(c.undoManager.canUndo)
    }

    @Test
    fun aFolderBelowIsRefused() {
        val (c, l) = controller()
        assertTrue("the button stays enabled: the refusal explains", LayerOps.canMergeDown(c, l[4]))
        LayerOps.mergeDown(c, l[4])
        assertEquals(FolderLabels.MERGE_INTO_REFUSAL, c.message)
        assertEquals(l, c.doc.layers.toList())
        assertFalse(c.undoManager.canUndo)
    }

    @Test
    fun aSiblingBelowMergesInOneStep() {
        val (c, l) = controller()
        assertTrue(LayerOps.canMergeDown(c, l[2]))
        LayerOps.mergeDown(c, l[2])
        assertEquals(listOf(l[0], l[1], l[3], l[4]), c.doc.layers.toList())
        assertEquals(l[3].id, l[1].parentId)
        assertNull(LayerTree.check(c.doc.layers))
        c.undo()
        assertEquals(l, c.doc.layers.toList())
        assertEquals(l[3].id, l[2].parentId)
    }
}
