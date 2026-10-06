package com.brushwork.paint.model

import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 F1 (§4.2): [LayerData] carries the folder and the array. A folder-only and an array-only
 * snapshot are not empty; a pixel edit drops the array (unless "Edit source pixels" is on) and
 * keeps the folder; a layer's snapshot and restore carry both.
 */
@RunWith(RobolectricTestRunner::class)
class LayerDataTest {
    private val array = LayerArray(ArraySpec(count = 4))

    @Test
    fun folderOnlyAndArrayOnlySnapshotsAreNotEmpty() {
        assertTrue(LayerData().isEmpty)
        assertFalse(LayerData(folder = FolderSpec()).isEmpty)
        assertFalse(LayerData(array = array).isEmpty)
        // The v1.6 positional form still means the same.
        val v16 = LayerData("t", null, null, null, null)
        assertEquals(LayerData(text = "t"), v16)
        assertNull(v16.folder)
        assertNull(v16.array)
    }

    @Test
    fun aPixelEditDropsTheArrayAndKeepsTheFolder() {
        val d = LayerData(text = "t", folder = FolderSpec(passThrough = false), array = array)
        val r = d.rasterizedContent()
        assertNull(r.text)
        assertNull(r.array)
        assertEquals(FolderSpec(passThrough = false), r.folder)
        // "Edit source pixels": the raster array stays.
        val editing = LayerArray(ArraySpec(editingSource = true))
        val e = LayerData(array = editing).rasterizedContent()
        assertSame(editing, e.array)
        // A mask edit keeps both.
        assertSame(array, LayerData(array = array, folder = FolderSpec()).rasterizedMask().array)
    }

    @Test
    fun approxBytesCountsTheSourcePixels() {
        val base = LayerData(array = array).approxBytes()
        val px = ArrayPixels(BitmapUtils.createLayerBitmap(10, 5), 0, 0)
        assertEquals(200L, px.bytes)
        assertEquals(base + 200L, LayerData(array = LayerArray(ArraySpec(), px)).approxBytes())
        assertEquals(LayerData().approxBytes(), LayerData(folder = FolderSpec()).approxBytes())
    }

    @Test
    fun layerSnapshotAndRestoreCarryFolderAndArray() {
        val l = Layer(1, "L", BitmapUtils.createLayerBitmap(2, 2))
        assertFalse(l.hasEditableData)
        l.array = array
        assertTrue(l.hasEditableData)
        val snap = l.dataSnapshot()
        assertSame(array, snap.array)
        l.restoreData(LayerData(folder = FolderSpec()))
        assertNull(l.array)
        assertTrue(l.isFolder)
        l.restoreData(snap)
        assertSame(array, l.array)
        assertFalse(l.isFolder)
        // An adjustment snapshot is untouched by the new fields.
        val adj = LayerData(adjustment = AdjustmentSpec(filterId = "adjust.tone"))
        l.restoreData(adj)
        assertEquals(adj, l.dataSnapshot())
    }
}
