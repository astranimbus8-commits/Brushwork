package com.brushwork.paint.ui.layers

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 8, §3.8): the folder rows' pictures ([FolderThumbnails]) go out of date exactly when
 * something drawn inside the folder changes, render at most [FOLDER_THUMB_PX] on their longest
 * side, and are dropped with their folder.
 */
@RunWith(RobolectricTestRunner::class)
class FolderThumbnailsRobolectricTest {
    private val doc = Document("t", "t", 200, 120)
    private val compositor = Compositor(doc) { null }

    private fun pixel(name: String, parent: Layer?, color: Int): Layer =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(doc.width, doc.height)).also { l ->
            if (parent != null) l.parentId = parent.id
            Canvas(l.bitmap).drawCircle(60f, 60f, 40f, Paint().apply { this.color = color })
        }

    private fun folder(name: String, parent: Layer? = null): Layer =
        Layer.newFolder(doc.newLayerId(), name, FolderSpec()).also { if (parent != null) it.parentId = parent.id }

    @Test
    fun aPictureGoesStaleOnlyWhenSomethingInsideChanges() {
        val outer = folder("Outer")
        val inner = folder("Inner", outer)
        val a = pixel("A", inner, 0xFF3060C0.toInt())
        val b = pixel("B", outer, 0xFFC06030.toInt())
        val below = pixel("Below", null, 0xFF000000.toInt())
        doc.layers += listOf(below, a, inner, b, outer)
        assertNull(LayerTree.check(doc.layers))
        val thumbs = FolderThumbnails()
        val listed = doc.layers.mapTo(HashSet()) { it.id }

        assertEquals("top first, the listed folders only", listOf(outer, inner), thumbs.stale(doc.layers, listed))
        assertEquals(listOf(inner), thumbs.stale(doc.layers, setOf(inner.id)))
        assertTrue(thumbs.render(compositor, doc, outer))
        assertTrue(thumbs.render(compositor, doc, inner))
        assertNotNull(thumbs.image(outer.id))
        val picture = thumbs.image(outer.id)!!
        assertEquals(FOLDER_THUMB_PX, picture.width)
        assertEquals("96 × 120 / 200, rounded", 58, picture.height)
        assertEquals(emptyList<Layer>(), thumbs.stale(doc.layers, listed))

        // Outside the folders: nothing to redraw.
        below.markChanged()
        below.opacity = 0.5f
        assertEquals(emptyList<Layer>(), thumbs.stale(doc.layers, listed))

        // Pixels deep inside: both folders.
        a.markChanged()
        assertEquals(listOf(outer, inner), thumbs.stale(doc.layers, listed))
        thumbs.render(compositor, doc, outer)
        thumbs.render(compositor, doc, inner)

        // A property of a child of Outer only.
        b.blendMode = LayerBlendMode.MULTIPLY
        assertEquals(listOf(outer), thumbs.stale(doc.layers, listed))
        thumbs.render(compositor, doc, outer)
        inner.folder = FolderSpec(passThrough = false)
        assertEquals("Inner's pass-through shows in both", listOf(outer, inner), thumbs.stale(doc.layers, listed))
    }

    @Test
    fun aDeletedFolderLosesItsPicture() {
        val f = folder("F")
        val a = pixel("A", f, 0xFF3060C0.toInt())
        doc.layers += listOf(a, f)
        val thumbs = FolderThumbnails()
        assertTrue(thumbs.render(compositor, doc, f))
        assertEquals(1, thumbs.size)
        doc.layers.remove(f)
        a.parentId = Layer.ROOT_ID
        assertFalse("not a folder of the document any more", thumbs.render(compositor, doc, f))
        thumbs.retain(doc.layers.mapTo(HashSet()) { it.id })
        assertEquals(0, thumbs.size)
        assertNull(thumbs.image(f.id))
    }
}
