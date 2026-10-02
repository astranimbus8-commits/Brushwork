package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Matrix
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.5 review (B-g): a mask drawn again from its mapped spec after a canvas operation is not only
 * "the rendering of its spec" (CanvasOpsMaskSpecRobolectricTest) but also the old mask moved
 * where the operation moves the layer: exactly for moves, quarter turns and flips (pixel centres
 * map to pixel centres), and within resampling for a 2x resize. A wrong placement of the spec
 * (offset, axis, scale) fails here.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasOpsMaskGeometryRobolectricTest {
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

    private fun gray(b: Bitmap, x: Int, y: Int) = b.getPixel(x, y) and 0xFF

    /** Runs [op] and returns (old mask, new mask). */
    private fun run(op: (CanvasSnapshot) -> CanvasResult): Pair<Bitmap, Bitmap> {
        val c = setup()
        val old = c.doc.layers[0].mask!!.copy(Bitmap.Config.ARGB_8888, false)
        val snap = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, "op", snap, op(snap))
        return old to c.doc.layers[0].mask!!
    }

    /** Every new pixel equals the old one [from] maps it back to, within [tol] levels. */
    private fun assertMoved(what: String, tol: Int, nw: Int, nh: Int, pair: Pair<Bitmap, Bitmap>, from: (Int, Int) -> Pair<Int, Int>?) {
        val (old, new) = pair
        assertTrue("$what: size", new.width == nw && new.height == nh)
        var worst = 0
        var compared = 0
        for (y in 0 until nh) for (x in 0 until nw) {
            val (ox, oy) = from(x, y) ?: continue
            worst = max(worst, abs(gray(new, x, y) - gray(old, ox, oy)))
            compared++
        }
        assertTrue("$what: compared $compared", compared > 1000)
        assertTrue("$what: off by up to $worst", worst <= tol)
    }

    @Test
    fun theRedrawnMaskIsTheOldOneMovedWithTheLayer() {
        assertMoved("canvas size (+70, +40)", 2, 260, 200, run { s -> CanvasOps.resizeCanvas(s, 260, 200, 70, 40) }) { x, y ->
            (x - 70 to y - 40).takeIf { (ox, oy) -> ox in 0 until w && oy in 0 until h }
        }
        assertMoved("crop", 2, 110, 90, run { s -> CanvasOps.cropTo(s, android.graphics.Rect(30, 20, 140, 110)) }) { x, y -> x + 30 to y + 20 }
        val cw = CanvasRotation.entries.first { it.quarterTurnsCw == 1 }
        assertMoved("quarter turn clockwise", 2, h, w, run { s -> CanvasOps.rotate(s, cw) }) { x, y -> y to h - 1 - x }
        assertMoved("flip", 2, w, h, run { s -> CanvasOps.flip(s, horizontal = true) }) { x, y -> w - 1 - x to y }
    }

    @Test
    fun aResizedMaskIsTheOldOneScaledWithinResampling() {
        val (old, new) = run { s -> CanvasOps.resizeImage(s, 2 * w, 2 * h, Resample.BILINEAR) }
        // Each old pixel against the mean of the 2x2 new pixels it became.
        var sum = 0L
        var worst = 0
        for (y in 0 until h) for (x in 0 until w) {
            val m = (gray(new, 2 * x, 2 * y) + gray(new, 2 * x + 1, 2 * y) + gray(new, 2 * x, 2 * y + 1) + gray(new, 2 * x + 1, 2 * y + 1) + 2) / 4
            val d = abs(m - gray(old, x, y))
            sum += d
            worst = max(worst, d)
        }
        val mean = sum.toDouble() / (w * h)
        assertTrue("mean difference $mean (max $worst)", mean <= 1.5 && worst <= 48)
    }
}
