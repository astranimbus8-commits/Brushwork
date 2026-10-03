package com.brushwork.paint.ui.layers

import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The layer window's row snapshots (v1.6): layer numbers counted from the bottom, the frame badge
 * of linked text frames (decoded once per text data), the adjustment effect's name, and the
 * transparency squares' setting (a view preference: it redraws the canvas, records nothing).
 */
@RunWith(RobolectricTestRunner::class)
class LayerRowFramesRobolectricTest {

    private fun newController(layers: Int): EditorController {
        val doc = Document("t", "t", 32, 24)
        repeat(layers) { doc.layers += Layer(doc.newLayerId(), "Layer ${it + 1}", BitmapUtils.createLayerBitmap(32, 24)) }
        doc.activeLayerIndex = doc.layers.lastIndex
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = AppSettings(context).also { it.prefs.edit().clear().commit() }
        return EditorController(context, doc, CoroutineScope(Job().apply { cancel() }), settings)
    }

    private fun frameData(story: Long, index: Int, overset: Boolean, text: String = "Hello"): String =
        TextCodec.encode(
            TextItem(
                text = text,
                spec = TextSpec(box = TextBoxSpec(width = 40f, minHeight = 20f)),
                cx = 16f, cy = 12f,
                thread = TextThreadSpec(storyId = story, index = index, story = "Hello world", start = 0, end = text.length, overset = overset, rev = 1),
            ),
        )

    @Test
    fun rowsNumberLayersFromTheBottomAndBadgeFrames() {
        val c = newController(layers = 4)
        val (plain, first, second, text) = c.doc.layers
        first.textData = frameData(story = 5, index = 0, overset = false)
        second.textData = frameData(story = 5, index = 1, overset = true, text = "Hel")
        text.textData = TextCodec.encode(TextItem(text = "Plain", cx = 16f, cy = 12f))
        val cache = FrameInfoCache()
        val rows = LayerRowModel.build(c.doc, c.doc.layers.asReversed().toList(), cache)
        assertEquals("top first, numbered from the bottom", listOf(4, 3, 2, 1), rows.map { it.number })
        assertEquals(listOf(text, second, first, plain), rows.map { it.layer })
        assertNull("plain text is no frame", rows[0].frame)
        assertTrue(rows[0].isText)
        assertEquals(FrameBadge(index = 1, count = 2, overset = true), rows[1].frame)
        assertEquals(FrameBadge(index = 0, count = 2, overset = false), rows[2].frame)
        assertTrue("a frame is edited as text (its story)", rows[1].isFrame && rows[1].isText)
        assertNull(rows[3].frame)
        assertFalse(rows[3].isText)
        assertEquals(3, cache.size)

        // Decoded once per text data: the same string gives the same (cached) thread.
        val thread = cache.threadOf(first)
        assertSame(thread, cache.threadOf(first))
        // New data (a re-flow) is decoded again; removed data drops the entry.
        first.textData = frameData(story = 5, index = 0, overset = true)
        assertTrue(cache.threadOf(first)!!.overset)
        first.textData = null
        assertNull(cache.threadOf(first))
        assertEquals(2, cache.size)
        cache.retain(setOf(second.id))
        assertEquals(1, cache.size)
    }

    @Test
    fun adjustmentRowsNameTheirEffect() {
        val c = newController(layers = 1)
        val adjustment = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), null)!!
        val rows = LayerRowModel.build(c.doc, c.doc.layers.asReversed().toList())
        assertSame(adjustment, rows[0].layer)
        assertTrue(rows[0].isAdjustment)
        assertEquals(AdjustmentEffects.displayName(adjustment.adjustment!!), rows[0].effectName)
        assertNull(rows[1].effectName)
    }

    @Test
    fun transparencySquaresAreAViewSettingThatRedraws() {
        val c = newController(layers = 1)
        assertEquals("default", TransparencyDisplay.LIGHT_CHECKER, c.settings.transparencyDisplay)
        var invalidations = 0
        c.onInvalidate = { invalidations++ }
        val version = c.layersVersion
        LayerOps.setTransparencyDisplay(c, TransparencyDisplay.DARK_CHECKER)
        assertEquals(TransparencyDisplay.DARK_CHECKER, c.settings.transparencyDisplay)
        assertEquals("the canvas redraws", 1, invalidations)
        assertFalse("no undo step", c.undoManager.canUndo)
        assertEquals("not a document change", version, c.layersVersion)
        // The same choice again changes nothing.
        LayerOps.setTransparencyDisplay(c, TransparencyDisplay.DARK_CHECKER)
        assertEquals(1, invalidations)
        // Persisted: a new settings object reads it back.
        assertEquals(TransparencyDisplay.DARK_CHECKER, AppSettings(ApplicationProvider.getApplicationContext()).transparencyDisplay)
    }
}
