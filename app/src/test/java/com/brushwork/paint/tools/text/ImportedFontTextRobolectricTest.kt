package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.fonts.FontImporter
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.fonts.ImportedFont
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Text drawn with imported fonts (real font files, real Skia): the font id is stored with the
 * text layer, a missing font falls back to the built-in family (and the editor says so), text on
 * a path uses the font, and script fonts' swashes are never cut off.
 */
@RunWith(RobolectricTestRunner::class)
class ImportedFontTextRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newController(w: Int = 400, h: Int = 300): Pair<EditorController, TextTool> {
        File(context.filesDir, FontStore.DIR_NAME).deleteRecursively()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun font(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("fonts/$name")).use { it.readBytes() }

    private fun import(store: FontStore, name: String): ImportedFont = runBlocking {
        val bytes = font(name)
        store.import(listOf(FontImporter.Source(name) { bytes.inputStream() }))
    }.added.single()

    private fun inkBounds(b: Bitmap): Rect {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val r = Rect()
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if ((px[y * b.width + x] ushr 24) != 0) {
                if (r.isEmpty) r.set(x, y, x + 1, y + 1) else r.union(x, y)
            }
        }
        return r
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun textUsesTheImportedFontAndFallsBackWhenItIsMissing() {
        val (c, tool) = newController()
        val store = tool.fontStore
        assertSame("the tool resolves fonts through the app's store", store, FontStore.current)
        val arvo = import(store, "Arvo-Regular.ttf")
        val sans = TextSpec(sizePx = 40f)
        val withArvo = sans.copy(fontId = arvo.id, fontName = arvo.name)
        val wSans = TextRenderer.layout("Hello World", sans).contentWidth
        val wArvo = TextRenderer.layout("Hello World", withArvo).contentWidth
        assertNotEquals(wSans, wArvo, 1f)
        assertTrue(!TextRenderer.isFontMissing(withArvo))
        assertSame(store.typeface(arvo.id), TextRenderer.baseTypeface(withArvo))

        // Picked in the tool, stored with the text layer.
        tool.startTextAt(200f, 150f)
        tool.setText("Hello")
        tool.updateSpec { it.copy(sizePx = 40f) }
        tool.setImportedFont(arvo)
        assertEquals(arvo.id, tool.item!!.spec.fontId)
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        assertTrue(layer.textData!!.contains("\"fontId\":\"${arvo.id}\""))
        val stored = TextCodec.decode(layer.textData)!!
        assertEquals(arvo.id, stored.spec.fontId)
        assertEquals(arvo.name, stored.spec.fontName)
        assertEquals(TextFont.SANS, stored.spec.font)
        // The next new text keeps the font.
        tool.startTextAt(50f, 50f)
        assertEquals(arvo.id, tool.item!!.spec.fontId)
        tool.setBuiltInFont(TextFont.SERIF)
        assertEquals(TextFont.SERIF, tool.item!!.spec.font)
        assertEquals(null, tool.item!!.spec.fontId)
        tool.discard()

        // The font is deleted: the text falls back to its built-in family, the editor warns.
        runBlocking { store.delete(arvo.id) }
        assertTrue(TextRenderer.isFontMissing(withArvo))
        assertEquals(wSans, TextRenderer.layout("Hello World", withArvo).contentWidth, 0.01f)
        c.message = null
        assertTrue(tool.editLayer(layer))
        assertTrue(c.message, c.message!!.contains("isn't on this device"))
        assertTrue(tool.fontMissing)
        val fallback = tool.blockFor(tool.item!!).contentWidth
        // Imported again (same file = same id): the text uses it again.
        assertEquals(arvo.id, import(store, "Arvo-Regular.ttf").id)
        tool.onFontsChanged()
        assertFalse(tool.fontMissing)
        assertNotEquals(fallback, tool.blockFor(tool.item!!).contentWidth, 1f)
        tool.discard()
    }

    @Test
    fun textOnAPathUsesTheImportedFont() {
        val (c, tool) = newController(500, 500)
        val coming = import(tool.fontStore, "ComingSoon.ttf")
        val spec = TextSpec(sizePx = 36f, fontId = coming.id, fontName = coming.name)
        assertSame(tool.fontStore.typeface(coming.id), TextRenderer.pathPaints(spec).fill.typeface)

        fun renderOnCircle(font: (TextTool) -> Unit): IntArray {
            tool.startTextAt(250f, 250f)
            tool.setText("Round and round we go")
            tool.updateSpec { it.copy(sizePx = 36f, color = 0xFF000000.toInt()) }
            font(tool)
            tool.setPath(TextPathSpec(type = TextPathType.CIRCLE))
            tool.confirmEditor()
            assertTrue(tool.commitItem())
            return pixels(c.activeLayer.bitmap)
        }
        val imported = renderOnCircle { it.setImportedFont(coming) }
        val builtIn = renderOnCircle { it.setBuiltInFont(TextFont.SANS) }
        assertTrue(imported.any { it != 0 })
        assertFalse("the path text is drawn in the imported font", imported.contentEquals(builtIn))
        val stored = TextCodec.decode(c.doc.layers[1].textData)!!
        assertTrue(stored.path.isActive)
        assertEquals(coming.id, stored.spec.fontId)
    }

    @Test
    fun swashesOfScriptFontsAreNeverCutOff() {
        val (_, tool) = newController()
        val dancing = import(tool.fontStore, "DancingScript-Regular.ttf")
        val base = TextSpec(sizePx = 90f, italic = true, color = 0xFF000000.toInt(), fontId = dancing.id, fontName = dancing.name)
        for (spec in listOf(base, base.copy(vertical = true, verticalStyle = VerticalStyle.MIXED), base.copy(box = TextBoxSpec(width = 260f), align = TextAlign.END))) {
            val item = TextItem("Jfgy Qz\nfly", spec, 400f, 400f, rotationDeg = 10f)
            val prep = TextRenderer.prepare(item)
            val bounds = prep.docBounds(item)
            val bmp = BitmapUtils.createLayerBitmap(800, 800)
            TextRenderer.drawItem(Canvas(bmp), item, prep, null)
            val ink = inkBounds(bmp)
            assertTrue(ink.width() > 50)
            assertTrue("vertical=${spec.vertical}: ink $ink inside $bounds", RectF(bounds).apply { inset(-1f, -1f) }.contains(RectF(ink)))
        }
        // Committed text keeps every pixel (the layer is clipped to the computed bounds).
        tool.startTextAt(200f, 150f)
        tool.setText("Jfgy")
        tool.updateSpec { base.copy(sizePx = 70f) }
        val item = tool.item!!
        tool.confirmEditor()
        val full = BitmapUtils.createLayerBitmap(400, 300).also { TextRenderer.drawItem(Canvas(it), item, TextRenderer.prepare(item), null) }
        assertTrue(tool.commitItem())
        val layer = tool.controller.activeLayer
        assertNotNull(layer.textData)
        assertTrue(pixels(full).contentEquals(pixels(layer.bitmap)))
    }
}
