package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import kotlin.math.hypot

/** Shared set-up of the text wrap tests: a white background, a picture layer, the Text tool. */
internal object WrapFixtures {
    const val LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore " +
        "et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea " +
        "commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur."

    class Setup(val c: EditorController, val background: Layer, val picture: Layer, val tool: TextTool)

    fun setup(context: Context, w: Int = 400, h: Int = 300, scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)): Setup {
        val doc = Document("wrap", "wrap", w, h)
        val bg = Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(WHITE) }
        val pic = Layer(doc.newLayerId(), "Picture", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += bg
        doc.layers += pic
        doc.activeLayerIndex = 1
        val c = EditorController(context, doc, scope, AppSettings(context))
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return Setup(c, bg, pic, c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    fun disc(layer: Layer, cx: Float, cy: Float, r: Float, color: Int = 0xFF2266CC.toInt()) {
        Canvas(layer.bitmap).drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        layer.markChanged()
    }

    /**
     * Places [text] centered at ([cx], [cy]) in a [width] px box, wraps it around [source] and
     * commits it (one step); returns the new text layer.
     */
    fun wrappedText(
        s: Setup,
        source: Layer = s.picture,
        text: String = LOREM,
        cx: Float = 200f,
        cy: Float = 150f,
        width: Float = 360f,
        size: Float = 16f,
        gap: Float = 6f,
        sides: WrapSides = WrapSides.LARGEST,
        rotation: Float = 0f,
    ): Layer {
        val tool = s.tool
        tool.startTextAt(cx, cy)
        tool.setText(text)
        tool.updateSpec { it.copy(sizePx = size, color = BLACK, box = it.box.copy(width = width)) }
        if (rotation != 0f) tool.setRotation(rotation)
        tool.confirmEditor()
        tool.setWrapSource(source)
        tool.setWrapGap(gap)
        tool.setWrapSides(sides)
        assertTrue("wraps", tool.item!!.wrapActive)
        assertTrue(tool.commitItem())
        return s.c.activeLayer.also { assertTrue(it.isTextLayer) }
    }

    fun itemOf(layer: Layer): TextItem = TextCodec.decode(layer.textData)!!

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** [item] rendered alone, as the text tool renders a text layer. */
    fun render(item: TextItem, w: Int, h: Int): Bitmap =
        BitmapUtils.createLayerBitmap(w, h).also { TextRenderer.drawItem(Canvas(it), item, TextRenderer.prepare(item), null) }

    /** Pixels of [b] with alpha above [min], as (x, y) pairs. */
    fun inked(b: Bitmap, min: Int = 0): List<Pair<Int, Int>> {
        val px = pixels(b)
        val out = ArrayList<Pair<Int, Int>>()
        for (y in 0 until b.height) for (x in 0 until b.width) if ((px[y * b.width + x] ushr 24) > min) out += x to y
        return out
    }

    /** Closest distance of [b]'s ink to ([cx], [cy]). */
    fun closestInk(b: Bitmap, cx: Float, cy: Float): Float =
        inked(b).minOfOrNull { (x, y) -> hypot(x + 0.5f - cx, y + 0.5f - cy) } ?: Float.MAX_VALUE

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
}
