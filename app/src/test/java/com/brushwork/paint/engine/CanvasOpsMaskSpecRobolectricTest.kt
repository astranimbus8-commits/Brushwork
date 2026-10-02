package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.MaskStroke
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 integration (A1 canvas operations x A5 editable masks, I1): after resize image, canvas
 * size / crop, rotate and flip, a layer's editable mask is exactly the rendering of its mapped
 * spec — scaled masks are drawn again from the spec rather than resampled, and a canvas grown
 * past an editable mask shows what the spec renders there (not a white margin). Undo restores
 * both.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasOpsMaskSpecRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 160
    private val h = 120

    private val spec = MaskSpec(
        components = listOf(
            RadialMask(1, cx = 70f, cy = 55f, rx = 45f, ry = 30f, rotationDeg = 25f, feather = 0.6f),
            LinearMask(2, mode = MaskMode.SUBTRACT, amount = 0.7f, x0 = 120f, y0 = 10f, x1 = 150f, y1 = 100f),
            BrushMask(3, strokes = listOf(MaskStroke(false, 18f, 0.5f, 0.8f, PackedPoints(floatArrayOf(20f, 60f, 110f), floatArrayOf(100f, 90f, 105f), floatArrayOf(1f, 1f, 1f))))),
        ),
        nextId = 4,
    )

    private fun setup(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            it.bitmap.eraseColor(0xFF3366CC.toInt())
            it.mask = MaskSpecs.newMask(spec, w, h)
            it.maskSpec = spec
        }
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** I1 for the mask: its pixels are exactly the rendering of its spec at the document's size. */
    private fun assertMaskIsItsSpec(what: String, c: EditorController) {
        val l = c.doc.layers[0]
        val s = l.maskSpec
        assertNotNull("$what: the spec is kept", s)
        val m = l.mask!!
        assertEquals(c.doc.width, m.width)
        assertArrayEquals("$what: mask = render(spec)", px(MaskSpecs.newMask(s!!, c.doc.width, c.doc.height)), px(m))
    }

    private fun commit(c: EditorController, label: String, op: (CanvasSnapshot) -> CanvasResult) {
        val snap = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, label, snap, op(snap))
    }

    @Test
    fun everyGeometryOperationKeepsTheMaskTheRenderingOfItsMappedSpec() {
        val ops: List<Pair<String, (CanvasSnapshot) -> CanvasResult>> = listOf(
            "Resize image x1.37" to { s -> CanvasOps.resizeImage(s, 219, 164, Resample.BILINEAR) },
            "Resize image /2" to { s -> CanvasOps.resizeImage(s, 80, 60, Resample.HIGH_QUALITY) },
            "Canvas size" to { s -> CanvasOps.resizeCanvas(s, 260, 200, 70, 40) },
            "Crop" to { s -> CanvasOps.cropTo(s, Rect(30, 20, 140, 110)) },
            "Rotate" to { s -> CanvasOps.rotate(s, CanvasRotation.entries.first { it.quarterTurnsCw == 1 }) },
            "Flip" to { s -> CanvasOps.flip(s, horizontal = true) },
        )
        for ((label, op) in ops) {
            val c = setup()
            val before = px(c.doc.layers[0].mask!!)
            commit(c, label, op)
            assertMaskIsItsSpec(label, c)
            assertNotEquals("$label: the spec was mapped", spec, c.doc.layers[0].maskSpec)
            c.undo()
            assertSame("$label: undo restores the spec", spec, c.doc.layers[0].maskSpec)
            assertArrayEquals("$label: undo restores the mask", before, px(c.doc.layers[0].mask!!))
            c.redo()
            assertMaskIsItsSpec("$label (redo)", c)
        }
    }

    @Test
    fun aGrownCanvasShowsWhatTheSpecRendersInTheNewMargin() {
        val c = setup()
        commit(c, "Canvas size") { s -> CanvasOps.resizeCanvas(s, 260, 200, 70, 40) }
        val m = c.doc.layers[0].mask!!
        // Far outside the radial part: the spec hides the layer there (black), it isn't white.
        assertEquals(0xFF000000.toInt(), m.getPixel(5, 195))
        assertEquals(0xFF000000.toInt(), m.getPixel(255, 5))
        // A mask without a spec still gets the white margin (painted masks keep v1.4's behaviour).
        val c2 = setup()
        c2.doc.layers[0].maskSpec = null
        commit(c2, "Canvas size") { s -> CanvasOps.resizeCanvas(s, 260, 200, 70, 40) }
        assertEquals(-1, c2.doc.layers[0].mask!!.getPixel(5, 195))
    }
}
