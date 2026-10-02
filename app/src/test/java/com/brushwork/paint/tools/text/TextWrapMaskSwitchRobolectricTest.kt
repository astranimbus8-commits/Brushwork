package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.WrapFixtures.disc
import com.brushwork.paint.tools.text.WrapFixtures.itemOf
import com.brushwork.paint.tools.text.WrapFixtures.pixels
import com.brushwork.paint.tools.text.WrapFixtures.render
import com.brushwork.paint.tools.text.WrapFixtures.setup
import com.brushwork.paint.tools.text.WrapFixtures.wrappedText
import com.brushwork.paint.ui.layers.LayerOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 integration (lead): switching the picture's mask off or on (layers window) is reported as
 * an edit of the mask, so text wrapped around the picture re-flows at once, inside that one step;
 * undo and redo restore both without re-flowing.
 */
@RunWith(RobolectricTestRunner::class)
class TextWrapMaskSwitchRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun topOf(item: TextItem): Float = item.wrap.polygons.minOf { p -> p.ys.min() }

    @Test
    fun switchingThePicturesMaskReflowsTheTextInThatStep() {
        val s = setup(RuntimeEnvironment.getApplication(), scope = scope)
        disc(s.picture, 110f, 150f, 50f)
        // A mask hiding the picture above y = 120.
        s.picture.mask = BitmapUtils.createMaskBitmap(400, 300).also { m ->
            Canvas(m).drawRect(0f, 0f, 400f, 120f, Paint().apply { color = WrapFixtures.BLACK })
        }
        val text = wrappedText(s)
        val masked = itemOf(text)
        assertTrue("the hidden top doesn't block: ${topOf(masked)}", topOf(masked) >= 118f)
        val steps = s.c.undoManager.undoCount
        val reflows = s.c.textWrap.reflowCount

        LayerOps.setMaskEnabled(s.c, s.picture, false)
        assertFalse(s.picture.maskEnabled)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals("Disable mask", s.c.undoManager.undoLabel)
        assertEquals(reflows + 1, s.c.textWrap.reflowCount)
        val whole = itemOf(text)
        assertTrue("the whole disc blocks now: ${topOf(whole)}", topOf(whole) < 105f)
        assertArrayEquals(pixels(render(whole, 400, 300)), pixels(text.bitmap))

        s.c.undo()
        assertTrue(s.picture.maskEnabled)
        assertEquals(masked, itemOf(text))
        s.c.redo()
        assertEquals(whole, itemOf(text))
        assertEquals("undo / redo never re-flow", reflows + 1, s.c.textWrap.reflowCount)

        LayerOps.setMaskEnabled(s.c, s.picture, true)
        assertEquals("Enable mask", s.c.undoManager.undoLabel)
        assertEquals(masked.wrap.polygons, itemOf(text).wrap.polygons)
        assertArrayEquals(pixels(render(itemOf(text), 400, 300)), pixels(text.bitmap))
        // Switching it to what it already is records nothing.
        val n = s.c.undoManager.undoCount
        s.c.setMaskEnabled(s.picture, true)
        assertEquals(n, s.c.undoManager.undoCount)
    }
}
