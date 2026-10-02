package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextWrapSpec
import com.brushwork.paint.tools.text.VerticalStyle
import com.brushwork.paint.tools.text.frames.FrameFixtures.STORY
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.drag
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Linked frames and v1.5 (v1.6, §3.6b/d "ThreadV15DecodeTest", I4): a v1.5 reader (a test-only
 * frozen copy of v1.5's `TextItem`) sees each frame as an ordinary text of its own slice, in its
 * box; and a frame edited by v1.5 (which drops its thread) is plain text to v1.6 while the other
 * frames still hold the story, which heals into them on the next edit.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadV15DecodeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------ frozen v1.5 shapes (test-only copies)

    @Serializable
    private data class TextSpecV15(
        val font: TextFont = TextFont.SANS,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val sizePx: Float = 48f,
        val color: Int = 0xFF000000.toInt(),
        val align: TextAlign = TextAlign.START,
        val letterSpacing: Float = 0f,
        val lineSpacing: Float = 1.2f,
        val vertical: Boolean = false,
        val strokeWidthPx: Float = 0f,
        val strokeColor: Int = 0xFFFFFFFF.toInt(),
        val antiAlias: Boolean = true,
        val verticalStyle: VerticalStyle = VerticalStyle.UPRIGHT,
        val columnsLeftToRight: Boolean = false,
        val box: TextBoxSpec = TextBoxSpec(),
        val fontId: String? = null,
        val fontName: String? = null,
    )

    @Serializable
    private data class TextItemV15(
        val text: String = "",
        val spec: TextSpecV15 = TextSpecV15(),
        val cx: Float = 0f,
        val cy: Float = 0f,
        val rotationDeg: Float = 0f,
        val path: TextPathSpec = TextPathSpec(),
        val wrap: TextWrapSpec = TextWrapSpec(),
    )

    @Serializable
    private data class TextLayerDataV15(val version: Int = 3, val item: TextItemV15 = TextItemV15())

    /** v1.5's TextCodec settings. */
    private val v15Json = Json { ignoreUnknownKeys = true; coerceInputValues = true; allowSpecialFloatingPointValues = true; encodeDefaults = true }

    @Test
    fun v15SeesEachFrameAsAPlainTextOfItsSlice() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 140f)
        for (f in listOf(f1, f2)) {
            val v16 = itemOf(f)
            val old = v15Json.decodeFromString(TextLayerDataV15.serializer(), f.textData!!).item
            assertEquals("v1.5 reads the frame's own slice", v16.text, old.text)
            assertEquals(v16.cx, old.cx, 0f)
            assertEquals(v16.cy, old.cy, 0f)
            assertEquals(0f, old.rotationDeg, 0f)
            assertFalse(old.spec.vertical)
            assertEquals("in the frame's box", v16.spec.box.width, old.spec.box.width, 0f)
            assertEquals(v16.spec.box.minHeight, old.spec.box.minHeight, 0f)
            assertEquals(v16.spec.sizePx, old.spec.sizePx, 0f)
            assertTrue("v1.5 never sees the whole story", old.text.length < STORY.length)
        }
    }

    @Test
    fun aFrameEditedByV15IsPlainTextAndTheStoryHealsOnTheNextEdit() {
        val s = setup(context)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 170f, 180f, 270f)
        val id = itemOf(f1).thread.storyId
        val story = s.c.textThreads.story(id)!!.text

        // v1.5 edits frame 2 (it changes its words) and writes it back without the thread.
        val old = v15Json.decodeFromString(TextLayerDataV15.serializer(), f2.textData!!)
        val edited = old.copy(item = old.item.copy(text = "Edited in v1.5"))
        val json = v15Json.encodeToString(TextLayerDataV15.serializer(), edited)
        val plain = TextCodec.decode(json)!!
        assertFalse("v1.6 reads it as an ordinary text", plain.threaded)
        assertEquals("Edited in v1.5", plain.text)
        f2.textData = json
        f2.bitmap.eraseColor(0)
        TextRenderer.drawItem(Canvas(f2.bitmap), plain, TextRenderer.prepare(plain), null)

        // The project is opened again in v1.6: frames 1 and 3 still hold the story.
        val c2 = EditorController(context, s.doc, s.c.scope, AppSettings(context))
        assertFalse(c2.textThreads.isFrame(f2))
        assertEquals(listOf(f1, f3), c2.textThreads.framesOf(id).map { it.layer })
        assertEquals("no text is lost", story, c2.textThreads.story(id)!!.text)
        // A damaged story is left alone until it is touched...
        val d3 = f3.textData
        c2.editWholeLayer(s.background, "Fill") { b -> b.eraseColor(0xFFEEEEEE.toInt()) }
        assertTrue(d3 === f3.textData)
        // ...and heals with the next edit of one of its frames.
        c2.viewTransform.set(android.graphics.Matrix())
        c2.snapping.enabled = false
        c2.selectTool(com.brushwork.paint.tools.ToolId.TEXT_FRAMES)
        val tool2 = c2.tools.getValue(com.brushwork.paint.tools.ToolId.TEXT_FRAMES) as TextFrameTool
        tool2.dragPreviewMs = 0L
        val box = FrameGeometry.outerRect(itemOf(f1))
        drag(c2, box.centerX(), box.centerY(), box.centerX() + 10f, box.centerY())
        assertEquals(listOf(f1, f3), chainOf(c2, f1))
        assertEquals(itemOf(f1).thread.end, itemOf(f3).thread.start)
        assertWhole(c2, id)
        // The plain text stays as v1.5 left it.
        assertEquals("Edited in v1.5", itemOf(f2).text)
        c2.dispose()
    }

    @Test
    fun v15DataOfAPlainTextIsNoFrame() {
        val s = setup(context)
        val json = v15Json.encodeToString(TextLayerDataV15.serializer(), TextLayerDataV15(item = TextItemV15(text = "storyId", cx = 50f, cy = 50f)))
        assertFalse(TextThreads.maybeThreaded(json))
        val item: TextItem = TextCodec.decode(json)!!
        assertFalse(item.threaded)
        s.layer2.textData = json
        assertFalse(s.c.textThreads.isFrame(s.layer2))
        assertTrue(s.c.textThreads.allFrames().isEmpty())
        assertEquals(TextSpec().sizePx, TextCodec.decode(json)!!.spec.sizePx, 0f)
    }
}
