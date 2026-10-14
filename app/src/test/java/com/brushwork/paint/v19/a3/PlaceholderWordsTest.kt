package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.PlaceholderAmount
import com.brushwork.paint.tools.text.PlaceholderFit
import com.brushwork.paint.tools.text.PlaceholderKind
import com.brushwork.paint.tools.text.PlaceholderText
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.common.TextLabels19
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 1 (R6): the "3 words" placeholder. The exact text per kind, the chip order, the
 * enum's ordinals (WORDS appended last), no wrap box for three words, and the tools' defaults
 * unchanged ("Paragraph" for the Text tool, "Fill the box" for frames).
 */
@RunWith(RobolectricTestRunner::class)
class PlaceholderWordsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun threeWordsPerKind() {
        assertEquals("Lorem ipsum dolor", PlaceholderText.text(PlaceholderKind.LOREM, PlaceholderAmount.WORDS))
        assertEquals("Your text here", PlaceholderText.text(PlaceholderKind.ENGLISH, PlaceholderAmount.WORDS))
        assertEquals("テキストを入力", PlaceholderText.text(PlaceholderKind.JAPANESE, PlaceholderAmount.WORDS))
        assertEquals("あいう", PlaceholderText.text(PlaceholderKind.KANA, PlaceholderAmount.WORDS))
        for (kind in listOf(PlaceholderKind.LOREM, PlaceholderKind.ENGLISH)) {
            assertEquals("$kind", 3, PlaceholderText.text(kind, PlaceholderAmount.WORDS).split(' ').size)
        }
        // Inserted after text: a space before Latin words, nothing before Japanese.
        assertEquals(" ", PlaceholderText.separator("Title", PlaceholderKind.LOREM, PlaceholderAmount.WORDS))
        assertEquals("", PlaceholderText.separator("見出し", PlaceholderKind.KANA, PlaceholderAmount.WORDS))
        assertEquals("", PlaceholderText.separator("Title\n", PlaceholderKind.ENGLISH, PlaceholderAmount.WORDS))
    }

    @Test
    fun chipOrderAndOrdinals() {
        assertEquals(
            listOf(TextLabels19.WORDS, "Short", "Paragraph", "3 paragraphs", "Fill the box"),
            PlaceholderAmount.CHIP_ORDER.map { it.label },
        )
        assertEquals(PlaceholderAmount.entries.toSet(), PlaceholderAmount.CHIP_ORDER.toSet())
        // WORDS is appended: the v1.8 entries keep their ordinals.
        assertEquals(
            listOf("FILL", "SHORT", "PARAGRAPH", "THREE_PARAGRAPHS", "WORDS"),
            PlaceholderAmount.entries.map { it.name },
        )
        assertEquals(PlaceholderAmount.WORDS, PlaceholderAmount.entries.last())
    }

    @Test
    fun threeWordsGetNoWrapBoxButAParagraphDoes() {
        val item = TextItem(text = "", spec = TextSpec(sizePx = 40f), cx = 200f, cy = 200f)
        val words = PlaceholderFit.edit(item, PlaceholderKind.LOREM, PlaceholderAmount.WORDS, true, 800, 800, 3200f)
        assertNotNull(words)
        assertEquals("Lorem ipsum dolor", words!!.text)
        assertEquals("one line, the box as it was", item.spec.box, words.spec.box)
        val short = PlaceholderFit.edit(item, PlaceholderKind.LOREM, PlaceholderAmount.SHORT, true, 800, 800, 3200f)!!
        assertEquals(item.spec.box, short.spec.box)
        val para = PlaceholderFit.edit(item, PlaceholderKind.LOREM, PlaceholderAmount.PARAGRAPH, true, 800, 800, 3200f)!!
        assertNotEquals("a paragraph wraps", item.spec.box, para.spec.box)
        // Added after existing text.
        val after = PlaceholderFit.edit(item.copy(text = "Title"), PlaceholderKind.ENGLISH, PlaceholderAmount.WORDS, false, 800, 800, 3200f)!!
        assertEquals("Title Your text here", after.text)
    }

    @Test
    fun defaultsUnchanged() {
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        assertEquals(PlaceholderAmount.PARAGRAPH, text.placeholderAmount)
        assertEquals(PlaceholderKind.LOREM, text.placeholderKind)
        val frames = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        assertEquals(PlaceholderAmount.FILL, frames.story.placeholderAmount)
    }
}
