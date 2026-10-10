package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 integration flow (design §6.2, areas G, F and E): a saved selection, the canvas turned 90°
 * clockwise, the selection loaded again (Replace) and made into an array with the selection
 * bar's "Array". The saved selection turns with the canvas (its mask is the turned mask, worked
 * out here pixel by pixel), the loaded selection is that mask, and the array's source pixels are
 * exactly the turned layer's pixels under it, cropped to them; the new "Array 1" layer sits right
 * above the source, whose selected pixels were cut. Each action is one step, undone exactly.
 */
@RunWith(RobolectricTestRunner::class)
class FlowSavedSelectionRotateArrayRobolectricTest {
    private val w = 64
    private val h = 40

    private fun controller(job: Job): EditorController {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("flow-sel", "flow-sel", w, h)
        doc.layers += Layer(doc.newLayerId(), "Paint", BitmapUtils.createLayerBitmap(w, h).also { it.setPixels(PATTERN, 0, w, 0, 0, w, h) })
        doc.activeLayerIndex = 0
        return EditorController(app, doc, CoroutineScope(Dispatchers.Unconfined + job), settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private fun awaitIdle(job: Job) = runBlocking {
        withTimeout(60_000) {
            while (true) {
                val active = job.children.filter { it.isActive }.toList()
                if (active.isEmpty()) break
                active.forEach { it.join() }
            }
        }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun bytes(s: Selection?): ByteArray? = s?.let { BitmapUtils.alpha8ToBytes(it.mask) }

    /** A row-major [w] × [h] grid turned 90° clockwise: [h] × [w], new (x', y') = old (y', h − 1 − x'). */
    private fun <T> turned(get: (Int) -> T, set: (Int, T) -> Unit) {
        for (y2 in 0 until w) for (x2 in 0 until h) set(y2 * h + x2, get((h - 1 - x2) * w + y2))
    }

    @Test
    fun aSavedSelectionLoadedAfterTurningTheCanvasArraysTheTurnedPixels() {
        val job = SupervisorJob()
        val c = controller(job)
        val src = c.doc.layers[0]
        // An L-ish selection, nothing symmetric about it: a bar and a disc, hard-edged.
        val path = Path().apply {
            addRect(6f, 5f, 30f, 17f, Path.Direction.CW)
            addCircle(44f, 26f, 9f, Path.Direction.CW)
        }
        val drawn = Selection.fromPath(path, w, h, antiAlias = false)
        val mask = bytes(drawn)!!
        c.setSelection(drawn, recordUndo = false)
        val steps0 = c.undoManager.undoCount
        assertTrue(c.saveSelection())
        awaitIdle(job)
        assertEquals("Save selection: one step", steps0 + 1, c.undoManager.undoCount)
        val saved = c.doc.savedSelections.single()
        c.setSelection(null, recordUndo = false)

        // 1. The canvas turned 90° clockwise (one step): the saved selection turns with it.
        assertTrue(CanvasOps.applyRotate(c, CanvasRotation.CW_90))
        awaitIdle(job)
        assertEquals(steps0 + 2, c.undoManager.undoCount)
        assertEquals(CanvasRotation.CW_90.label, c.undoManager.undoLabel)
        assertEquals(h, c.doc.width)
        assertEquals(w, c.doc.height)
        val turnedMask = ByteArray(w * h).also { out -> turned({ mask[it] }) { i, v -> out[i] = v } }
        val turnedPixels = IntArray(w * h).also { out -> turned({ PATTERN[it] }) { i, v -> out[i] = v } }
        assertArrayEquals("the layer turned", turnedPixels, pixels(src.bitmap))
        val rotated = c.doc.savedSelections.single()
        assertEquals(saved.id, rotated.id)
        assertArrayEquals("the saved selection turned", turnedMask, bytes(rotated.toSelection(h, w)))

        // 2. Loaded again, Replace (one step): the selection is the turned mask.
        c.loadSavedSelection(saved.id, SelectionMode.REPLACE)
        awaitIdle(job)
        assertEquals(steps0 + 3, c.undoManager.undoCount)
        assertEquals(SavedSelectionLabels.LOAD, c.undoManager.undoLabel)
        assertArrayEquals("the loaded selection", turnedMask, bytes(c.selection))

        // 3. The selection bar's "Array" (one step): a new layer right above, its source the
        //    turned pixels under the turned selection, cropped to them; those pixels cut.
        assertTrue(ArrayOps.fromSelection(c))
        awaitIdle(job)
        assertEquals(steps0 + 4, c.undoManager.undoCount)
        assertEquals(ArrayLabels.BUTTON, c.undoManager.undoLabel)
        assertEquals(2, c.doc.layers.size)
        assertSame(src, c.doc.layers[0])
        val arrayLayer = c.doc.layers[1]
        assertEquals("Array 1", arrayLayer.name)
        val px = arrayLayer.array!!.pixels!!
        // The selected cells' bounds (the pattern is opaque everywhere).
        var l = h; var t = w; var r = -1; var b = -1
        for (y in 0 until w) for (x in 0 until h) if (turnedMask[y * h + x].toInt() != 0) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y)
        }
        assertEquals(l, px.left)
        assertEquals(t, px.top)
        assertEquals(r - l + 1, px.bitmap.width)
        assertEquals(b - t + 1, px.bitmap.height)
        val expected = IntArray(px.bitmap.width * px.bitmap.height) { i ->
            val x = l + i % px.bitmap.width
            val y = t + i / px.bitmap.width
            if (turnedMask[y * h + x].toInt() != 0) turnedPixels[y * h + x] else 0
        }
        assertArrayEquals("the array's source: the turned selection's pixels", expected, pixels(px.bitmap))
        val cut = IntArray(w * h) { i -> if (turnedMask[i].toInt() != 0) 0 else turnedPixels[i] }
        assertArrayEquals("the selected pixels were cut from the source layer", cut, pixels(src.bitmap))
        val shown = pixels(arrayLayer.bitmap)
        for (i in shown.indices) if (turnedMask[i].toInt() != 0) assertEquals("the source copy in place at $i", turnedPixels[i], shown[i])

        // Undo, one step per action.
        c.undo()
        awaitIdle(job)
        assertEquals("undo Array", -1, c.doc.indexOf(arrayLayer))
        assertArrayEquals(turnedPixels, pixels(src.bitmap))
        c.undo()
        awaitIdle(job)
        assertNull("undo Load", c.selection)
        c.undo()
        awaitIdle(job)
        assertEquals("undo the turn", w, c.doc.width)
        assertArrayEquals(PATTERN, pixels(src.bitmap))
        assertArrayEquals("the saved selection as saved", mask, bytes(c.doc.savedSelections.single().toSelection(w, h)))
    }

    private companion object {
        /** Every pixel opaque, the colors varying along both axes. */
        val PATTERN = IntArray(64 * 40) { i -> (0xFF shl 24) or ((i % 64 * 4) shl 16) or ((i / 64 * 6) shl 8) or ((i * 7) and 0xFF) }
    }
}
