package com.brushwork.paint.v19.a6

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.clone.CloneTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sin

/**
 * v1.9 A6 step 2 (I25): wherever a lift tells the commit that the canvas already shows its result
 * (`displayCurrent`, [BrushTool.lastLiftShowedCommit]), the canvas after the lift, with only what
 * the lift itself redraws, equals a full redraw of the committed document, bit for bit; and so it
 * does where the lift redraws as v1.8 did.
 *
 * Each case draws a wave across a 512 x 300 canvas (Background, the stroke's "Paint" layer, a
 * translucent "Top"), rendering the canvas every few moves as the view does; its last point
 * comes with the lift, so the lift adds dabs of its own.
 */
@RunWith(RobolectricTestRunner::class)
class PreviewEqualsCommitParityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newController(colorMode: ColorMode = ColorMode.RGB): EditorController {
        val doc = Document("t", "t", W, H)
        doc.colorMode = colorMode
        val bg = BitmapUtils.createLayerBitmap(W, H)
        Canvas(bg).drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), 0xFFF0E0B0.toInt(), 0xFF3050A0.toInt(), Shader.TileMode.CLAMP)
        })
        val paint = BitmapUtils.createLayerBitmap(W, H)
        Canvas(paint).drawRect(20f, 60f, 260f, 240f, Paint().apply { color = 0x9040C060.toInt() })
        val top = BitmapUtils.createLayerBitmap(W, H)
        Canvas(top).drawCircle(380f, 150f, 110f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x60D04020 })
        doc.layers += Layer(doc.newLayerId(), "Background", bg)
        doc.layers += Layer(doc.newLayerId(), "Paint", paint)
        doc.layers += Layer(doc.newLayerId(), "Top", top)
        doc.activeLayerIndex = 1
        // A document in another color mode holds only its colors (as after converting it).
        if (colorMode != ColorMode.RGB) for (l in doc.layers) ColorModeOps.constrain(l.bitmap, doc.bounds, colorMode)
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.tools
        c.color = 0xFF2060C0.toInt()
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 24f, pressureSize = false)
        return c
    }

    private val EditorController.paintLayer: Layer get() = doc.layers.first { it.name == "Paint" }

    private fun DisplayTiles.pixels(): IntArray {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), null, smooth = false)
        return IntArray(W * H).also { bmp.getPixels(it, 0, W, 0, 0, W, H) }
    }

    /**
     * Draws the wave with [id] ([tool]: the BrushTool doing it), a frame every third move and one
     * before the lift; checks the canvas after the lift against a full redraw and the lift's
     * decision against [shown].
     */
    private fun parity(c: EditorController, id: ToolId, shown: Boolean, tool: BrushTool = c.tools.getValue(id) as BrushTool) {
        c.selectTool(id)
        val pts = List(N) { k ->
            val t = k / (N - 1f)
            ToolPoint(40f + 430f * t, 150f + 70f * sin(t * 7f), 1f, k * 8L)
        }
        c.pointerDown(pts[0])
        for (k in 1 until N - 1) {
            c.pointerMove(pts[k])
            if (k % 3 == 0) c.tiles.update(c.compositor, null)
        }
        c.tiles.update(c.compositor, null)
        val steps = c.undoManager.undoCount
        c.pointerUp(pts[N - 1])
        assertEquals("the stroke is one step", steps + 1, c.undoManager.undoCount)
        c.tiles.update(c.compositor, null)
        val fresh = DisplayTiles(W, H).also { it.update(c.compositor, null) }.pixels()
        val shownNow = c.tiles.pixels()
        var diff = 0
        var first = -1
        for (i in fresh.indices) if (fresh[i] != shownNow[i]) { diff++; if (first < 0) first = i }
        val where = if (first < 0) "" else " first at (${first % W}, ${first / W}): ${Integer.toHexString(shownNow[first])} shown, ${Integer.toHexString(fresh[first])} committed"
        assertEquals("pixels the canvas shows unlike the commit$where", 0, diff)
        assertEquals("the lift's decision (displayCurrent)", shown, tool.lastLiftShowedCommit)
    }

    @Test
    fun brush() = parity(newController(), ToolId.BRUSH, shown = true)

    @Test
    fun brushWithStrokeOpacity() {
        val c = newController()
        c.brush = c.brush.copy(opacity = 0.45f)
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun layerOpacityAndBlendModes() {
        for (mode in listOf(LayerBlendMode.MULTIPLY, LayerBlendMode.SCREEN, LayerBlendMode.OVERLAY, LayerBlendMode.DIFFERENCE, LayerBlendMode.HUE, LayerBlendMode.ADD)) {
            val c = newController()
            c.paintLayer.blendMode = mode
            c.paintLayer.opacity = 0.7f
            parity(c, ToolId.BRUSH, shown = true)
        }
    }

    @Test
    fun alphaLock() {
        val c = newController()
        c.paintLayer.alphaLocked = true
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun rasterEraser() {
        val c = newController()
        c.eraser = BrushLibrary.byId("softeraser")!!.copy(size = 40f)
        parity(c, ToolId.ERASER, shown = true)
    }

    @Test
    fun selection() {
        val c = newController()
        val bytes = ByteArray(W * H) { i -> if ((i % W) in 100 until 400) -1 else 0 }
        c.setSelection(Selection.fromBytes(bytes, W, H))
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun grain() {
        val c = newController()
        c.brush = BrushLibrary.byId("pencil")!!.copy(size = 18f)
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun taperedBrush() {
        val c = newController()
        c.brush = BrushLibrary.byId("gpen")!!.copy(size = 26f)
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun layerMaskTarget() {
        val c = newController()
        c.addMask(c.paintLayer, fromSelection = false)
        c.color = 0xFF000000.toInt()
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun maskedLayerContent() {
        val c = newController()
        c.addMask(c.paintLayer, fromSelection = false)
        c.paintLayer.editingMask = false
        c.paintLayer.mask!!.eraseColor(0xFF808080.toInt())
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun clippingBaseAndClippedLayer() {
        val base = newController()
        base.doc.layers.first { it.name == "Top" }.clipping = true
        parity(base, ToolId.BRUSH, shown = true)
        val clipped = newController()
        clipped.paintLayer.clipping = true
        parity(clipped, ToolId.BRUSH, shown = true)
    }

    @Test
    fun adjustmentLayerAbove() {
        val c = newController()
        c.addAdjustmentLayer(AdjustmentSpec(filterId = "adjust.invert"), null)!!
        c.selectLayer(c.paintLayer)
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun insideAFolder() {
        val c = newController()
        val folder = Layer.newFolder(c.doc.newLayerId(), "Group", FolderSpec(passThrough = false)).also { it.opacity = 0.8f }
        c.paintLayer.parentId = folder.id
        c.doc.layers.add(2, folder)
        c.selectLayer(c.paintLayer)
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun symmetry() {
        val c = newController()
        c.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        parity(c, ToolId.BRUSH, shown = true)
    }

    @Test
    fun smudgeAndBlur() {
        parity(newController(), ToolId.SMUDGE, shown = true)
        parity(newController(), ToolId.BLUR, shown = true)
    }

    @Test
    fun cloneStampRedrawsAsBefore() {
        val c = newController()
        val clone = c.tools.getValue(ToolId.CLONE) as CloneTool
        c.selectTool(ToolId.CLONE)
        clone.setSource(Vec2(60f, 100f))
        parity(c, ToolId.CLONE, shown = false, tool = clone.brush)
    }

    @Test
    fun otherColorModesRedrawAsBefore() {
        parity(newController(ColorMode.MONOCHROME), ToolId.BRUSH, shown = false)
        parity(newController(ColorMode.MONOCHROME), ToolId.SMUDGE, shown = false)
        parity(newController(ColorMode.GRAYSCALE), ToolId.BRUSH, shown = false)
    }

    private companion object {
        const val W = 512
        const val H = 300
        const val N = 40
    }
}
