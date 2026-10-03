package com.brushwork.paint.ui.layers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * The layer window's opacity on an adjustment layer (design §3.1 C2 / §3.7.7) and its list as a
 * finger uses it, on the user's phone size. One test per class: each needs its own sandbox (the
 * test recomposer policy and the paused Choreographer are global to a sandbox).
 */

/** The window at ibisPaint's 382 × 520 over a fresh activity, with [doc] open. */
private fun openWindow(doc: Document): Pair<ComponentActivity, EditorController> {
    SmokeUi.installTestRecomposer()
    SmokeUi.markBaseline()
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val c = Smoke.controller(activity, doc)
    return activity to c
}

private fun host(activity: ComponentActivity, c: EditorController) {
    activity.setContent {
        BrushworkTheme {
            Box(Modifier.fillMaxSize()) {
                LayersPanel(
                    c, onDismiss = {}, onImportPicture = {},
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 92.dp).size(382.dp, 520.dp),
                )
            }
        }
    }
    SmokeUi.settle()
}

/** An inverting adjustment layer whose radial mask lies inside the left 512 px tile. */
private fun maskedInvert(c: EditorController): Layer {
    val layer = c.addAdjustmentLayer(
        AdjustmentEffects.defaultSpec(),
        MaskSpec(components = listOf(RadialMask(1, cx = 200f, cy = 256f, rx = 120f, ry = 120f)), nextId = 2),
    )!!
    assertTrue(c.updateLayerData(layer, layer.dataSnapshot().copy(adjustment = AdjustmentSpec(filterId = "adjust.invert")), "Edit adjustment", null, draw = null))
    return layer
}

/** The display tiles as the canvas shows them, as one document-sized bitmap. */
private fun shown(tiles: DisplayTiles): IntArray {
    val w = tiles.docWidth
    val h = tiles.docHeight
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    tiles.draw(Canvas(out), null, smooth = false)
    return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
}

/** What a fresh, never-invalidated set of tiles shows for the document now. */
private fun exact(c: EditorController): IntArray {
    val fresh = DisplayTiles(c.doc.width, c.doc.height)
    fresh.update(c.compositor, null)
    return shown(fresh).also { fresh.release() }
}

/**
 * −/+ and typed values on an adjustment layer take the drag's path: only the display tiles under
 * the effect are redrawn (the tile it never reaches keeps its pixels), the canvas then equals a
 * fresh render bit for bit (no stale pixels, I7), and each change is one "Opacity" step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.opacityregionsandbox"])
class LayerWindowAdjustmentOpacityRegionTest {
    @Test
    fun discreteChangesRedrawOnlyWhereTheEffectShowsAndRecordOneStep() {
        val (activity, c) = openWindow(Smoke.document(1024, 512, layers = 2, whiteBottom = true))
        val adjustment = maskedInvert(c)
        host(activity, c)
        val tiles = c.tiles
        assertEquals("two tiles side by side", 2, tiles.tileCount)
        tiles.update(c.compositor, null)
        assertFalse(tiles.isDirty(0) || tiles.isDirty(1))
        val n = c.doc.indexOf(adjustment) + 1
        val effect = AdjustmentEffects.displayName(adjustment.adjustment!!)

        val steps = c.undoManager.undoCount
        SmokeUi.click(LayerLabels.LESS_OPACITY, exact = true)
        assertEquals(0.99f, adjustment.opacity, 1e-4f)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("Opacity", c.undoManager.undoLabel)
        assertTrue("the tile under the effect redraws", tiles.isDirty(0))
        assertFalse("the tile the effect never reaches keeps its pixels", tiles.isDirty(1))
        assertTrue("the row shows the new value", SmokeUi.has(LayerLabels.rowState(n, 99, adjustment.blendMode.label, effect), exact = true))
        assertTrue("so does the opacity row", SmokeUi.has("99%", exact = true))
        tiles.update(c.compositor, null)
        assertTrue("the canvas equals a fresh render", shown(tiles).contentEquals(exact(c)))

        // A typed value: the same path, exact on screen.
        SmokeUi.click(LayerLabels.TYPE_OPACITY, exact = true)
        SmokeUi.typeAndDone(LayerLabels.OPACITY, "40")
        assertEquals(0.4f, adjustment.opacity, 1e-4f)
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertFalse(tiles.isDirty(1))
        tiles.update(c.compositor, null)
        assertTrue("typed: the canvas equals a fresh render", shown(tiles).contentEquals(exact(c)))

        // Undo goes back through both values.
        c.undoManager.undo(c)
        assertEquals(0.99f, adjustment.opacity, 1e-4f)
        c.undoManager.undo(c)
        assertEquals(1f, adjustment.opacity, 1e-4f)
        tiles.update(c.compositor, null)
        assertTrue("undone: the canvas equals a fresh render", shown(tiles).contentEquals(exact(c)))
        Smoke.assertQuiet(c, "discrete opacity")
    }
}

/**
 * A pending Adjust-sheet edit (its Amount IS the adjustment layer's opacity) is recorded before
 * the window's −/+ or drag changes that opacity, so undo and redo walk back through the sheet's
 * value (I2): redo of the sheet's step never jumps to the window's value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.opacitysheetsandbox"])
class LayerWindowAdjustmentOpacitySheetTest {
    @Test
    fun aPendingAdjustSheetEditIsRecordedBeforeTheWindowsChange() {
        val (activity, c) = openWindow(Smoke.document(400, 300, layers = 2, whiteBottom = true))
        val adjustment = maskedInvert(c)
        host(activity, c)
        val steps = c.undoManager.undoCount

        // The Adjust sheet's Amount at 50 %, not yet recorded; then the window's −.
        val sheet = AdjustmentEdit(c, adjustment)
        sheet.preview(opacity = 0.5f)
        SmokeUi.settle()
        SmokeUi.click(LayerLabels.LESS_OPACITY, exact = true)
        assertEquals(0.49f, adjustment.opacity, 1e-4f)
        assertEquals("the sheet's step, then the window's", steps + 2, c.undoManager.undoCount)
        assertEquals("Opacity", c.undoManager.undoLabel)
        c.undoManager.undo(c)
        assertEquals("back to the sheet's value", 0.5f, adjustment.opacity, 1e-4f)
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)
        c.undoManager.undo(c)
        assertEquals(1f, adjustment.opacity, 1e-4f)
        c.undoManager.redo(c)
        assertEquals("redo of the sheet's step gives the sheet's value", 0.5f, adjustment.opacity, 1e-4f)
        c.undoManager.redo(c)
        assertEquals(0.49f, adjustment.opacity, 1e-4f)

        // The same with a drag of the slider.
        sheet.preview(opacity = 0.3f)
        SmokeUi.settle()
        val before = c.undoManager.undoCount
        val slider = SmokeUi.find(LayerLabels.OPACITY, exact = true)!!
        val b = slider.bounds
        val r = 11f * activity.resources.displayMetrics.density
        fun x(f: Float) = b.left + r + f * (b.width - 2f * r)
        val touch = Smoke.Touch(slider.window)
        touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, x(0.3f), b.center.y))
        for (i in 1..8) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, x(0.3f + 0.05f * i), b.center.y))
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, Smoke.P(0, x(0.7f), b.center.y))
        SmokeUi.settle()
        assertEquals(0.7f, adjustment.opacity, 0.03f)
        assertEquals("the sheet's step, then the drag's", before + 2, c.undoManager.undoCount)
        val dragged = adjustment.opacity
        c.undoManager.undo(c)
        assertEquals(0.3f, adjustment.opacity, 1e-4f)
        c.undoManager.undo(c)
        assertEquals(0.49f, adjustment.opacity, 1e-4f)
        c.undoManager.redo(c)
        assertEquals("redo of the sheet's step gives the sheet's value", 0.3f, adjustment.opacity, 1e-4f)
        c.undoManager.redo(c)
        assertEquals(dragged, adjustment.opacity, 1e-6f)
        Smoke.assertQuiet(c, "pending sheet edit")
    }
}

/** A drag on a row's body (not its ≡) scrolls a long list, without selecting, reordering or recording. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.listscrollsandbox"])
class LayerWindowListScrollTest {
    @Test
    fun aDragOnARowsBodyScrollsALongList() {
        val (activity, c) = openWindow(Smoke.document(64, 48, layers = 8))
        host(activity, c)
        val density = activity.resources.displayMetrics.density
        val order = c.doc.layers.toList()
        val active = c.activeLayer
        assertSame("the top layer is active", order.last(), active)
        val steps = c.undoManager.undoCount
        val rows = LayerWindowProbe(density).tagged(LayerWindowTags.ROWS)
        fun bottomRowShown(): Boolean = SmokeUi.find(LayerLabels.selectRow(1), exact = true)?.let { rows.contains(it.bounds.center) } == true
        assertFalse("the bottom layer starts below the fold", bottomRowShown())

        // A finger drags a row's body up by four rows.
        val x = rows.left + 120f * density
        val y0 = rows.bottom - 30f * density
        val t = Smoke.Touch(activity.window.decorView)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, x, y0))
        for (i in 1..16) {
            t.idle(16)
            t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, x, y0 - i * 20f * density))
        }
        t.idle(16)
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, x, y0 - 320f * density))
        SmokeUi.settle()
        assertTrue("the list scrolled to the bottom layer", bottomRowShown())
        assertEquals("nothing reordered", order, c.doc.layers)
        assertSame("nothing selected", active, c.activeLayer)
        assertEquals("nothing recorded", steps, c.undoManager.undoCount)
        assertFalse("no menu opened", SmokeUi.has("Rename…", exact = true))
        Smoke.assertQuiet(c, "list scroll")
    }
}
