package com.brushwork.paint.masks

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 8, §3.8: "adjustment layers inside an isolated folder affect only that folder's
 * content"): what an adjustment layer's sheet and mask page read follows the folder it is in.
 * The Tone histogram measures what the effect works on, and "Apply a filter through this mask…"
 * never picks a layer the effect does not reach.
 */
@RunWith(RobolectricTestRunner::class)
class MaskScopesInFoldersRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 40
    private val h = 30

    private class Scene(val c: EditorController, val black: Layer, val white: Layer?, val folder: Layer, val adjustment: Layer)

    /**
     * An opaque black layer, then folder "F" holding (with [withWhite]) a layer white on its left
     * half and, above it, an Invert adjustment layer with a mask that shows everywhere.
     */
    private fun scene(passThrough: Boolean, withWhite: Boolean = true): Scene {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("m", "m", w, h)
        val black = Layer(doc.newLayerId(), "Black", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0xFF000000.toInt()) })
        val f = Layer.newFolder(doc.newLayerId(), "F", FolderSpec(passThrough = passThrough))
        val white = if (withWhite) {
            Layer(doc.newLayerId(), "White", BitmapUtils.createLayerBitmap(w, h)).also { l ->
                Canvas(l.bitmap).drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = 0xFFFFFFFF.toInt() })
                l.parentId = f.id
            }
        } else null
        val adj = Layer(doc.newLayerId(), "Invert", BitmapUtils.createLayerBitmap(w, h)).also { l ->
            l.adjustment = AdjustmentSpec(filterId = "adjust.invert")
            l.mask = BitmapUtils.createMaskBitmap(w, h)
            l.parentId = f.id
        }
        doc.layers += listOfNotNull(black, white, adj, f)
        assertNull(LayerTree.check(doc.layers))
        doc.activeLayerIndex = doc.indexOf(adj)
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return Scene(c, black, white, f, adj)
    }

    @Test
    fun theHistogramMeasuresWhatTheEffectWorksOn() {
        val iso = scene(passThrough = false)
        val inside = assertHistogram(iso)
        assertEquals("inside an isolated folder the black layer below is not reached", 0, inside[0])
        assertTrue(inside[255] > 0)

        val pass = scene(passThrough = true)
        val through = assertHistogram(pass)
        assertTrue("through a pass-through folder it is", through[0] > 0)
        assertTrue(through[255] > 0)

        val bare = scene(passThrough = false, withWhite = false)
        assertNull("nothing below inside an isolated folder", AdjustmentHistogram.below(bare.c, bare.adjustment))
        val bareThrough = scene(passThrough = true, withWhite = false)
        assertNotNull(AdjustmentHistogram.below(bareThrough.c, bareThrough.adjustment))
    }

    private fun assertHistogram(s: Scene): IntArray {
        val hist = AdjustmentHistogram.below(s.c, s.adjustment)
        assertNotNull(hist)
        return hist!!
    }

    @Test
    fun aFilterThroughTheMaskPicksOnlyALayerTheEffectReaches() {
        val bare = scene(passThrough = false, withWhite = false)
        val steps = bare.c.undoManager.undoCount
        assertFalse(MaskLayerOps.prepareFilterThroughMask(bare.c, bare.adjustment))
        assertEquals("There is no layer below to apply a filter to", bare.c.message)
        assertEquals(steps, bare.c.undoManager.undoCount)
        assertNull(bare.c.selection)

        val through = scene(passThrough = true, withWhite = false)
        assertTrue(MaskLayerOps.prepareFilterThroughMask(through.c, through.adjustment))
        assertSame("below the pass-through folder", through.black, through.c.doc.activeLayer)
        assertNotNull(through.c.selection)

        val iso = scene(passThrough = false)
        assertTrue(MaskLayerOps.prepareFilterThroughMask(iso.c, iso.adjustment))
        assertSame(iso.white, iso.c.doc.activeLayer)
    }
}
