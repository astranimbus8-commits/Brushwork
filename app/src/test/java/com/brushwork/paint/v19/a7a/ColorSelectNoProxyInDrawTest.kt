package com.brushwork.paint.v19.a7a

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.colorrange.ColorMasks
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.ui.common.StabilityLabels19
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.Collections

/**
 * v1.9 A7a step 6 (design 3.2 A7a, R9; the user's crash "while using ... the color selection
 * tool"): Color select never builds its proxy in a draw.
 * - The proxy's pixels are rendered off the main thread (activation included).
 * - A draw that finds the pixels changed (an undo, a redo) renders nothing: it draws the last
 *   preview (not recycled) and asks for a new proxy once, built after the draw, off the main
 *   thread; its preview then shows the new pixels.
 * - A render that throws is tried once more, then kept as a quiet report and said
 *   ([StabilityLabels19.ERROR_TOAST]); nothing reaches the scope's handler.
 * - The masks use at most [ColorMasks.WORKERS] threads.
 */
@RunWith(RobolectricTestRunner::class)
class ColorSelectNoProxyInDrawTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val red = 0xFFD02020.toInt()

    @Before
    fun clean() {
        ShadowLog.stream = null
        CrashLog.deleteAll(app)
        CrashLog.resetRepeats()
        Smoke.scopeErrors.clear()
    }

    @After
    fun drain() {
        CrashLog.drain()
        CrashLog.deleteAll(app)
    }

    /** 256 x 192, its left half red (the right half transparent). */
    private fun editor(): Triple<EditorController, ColorSelectTool, Bitmap> {
        val doc = Smoke.document(256, 192, layers = 1)
        val bmp = doc.layers[0].bitmap
        Canvas(bmp).drawRect(0f, 0f, 128f, 192f, Paint().apply { color = red })
        doc.activeLayerIndex = 0
        val c = Smoke.controller(app, doc)
        return Triple(c, c.tools.getValue(ToolId.COLOR_SELECT) as ColorSelectTool, bmp)
    }

    @Test
    fun aDrawAfterAPixelChangeDrawsTheLastPreviewAndAsksForANewProxyBuiltOffTheMainThread() {
        assertEquals(4, ColorMasks.WORKERS)
        val (c, tool, bmp) = editor()
        val main = Looper.getMainLooper().thread
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val real = tool.renderSource
        tool.renderSource = { threads += Thread.currentThread(); real(it) }
        c.selectTool(ToolId.COLOR_SELECT)
        tool.addSample(red)
        assertTrue("the proxy and a preview", Smoke.pumpUntil { tool.proxyReady && tool.idle && tool.preview != null })
        assertEquals("rendered once", 1, threads.size)
        assertNotSame("off the main thread", main, threads[0])
        val before = tool.preview!!
        assertTrue("the left half shows", before.mask[96 * 256 + 40].toInt() != 0)
        assertTrue("the right half does not", before.mask[96 * 256 + 200].toInt() == 0)

        // The pixels change under the tool (as an undo does: the layer's content version moves).
        c.message = null
        Canvas(bmp).drawRect(128f, 0f, 256f, 192f, Paint().apply { color = red })
        c.doc.layers[0].markChanged()

        val out = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        tool.drawOverlay(Canvas(out), c.viewTransform)
        assertEquals("nothing is rendered in the draw", 1, threads.size)
        assertSame("the last preview is drawn", before, tool.preview)
        assertFalse(before.bitmap.isRecycled)
        assertFalse("a new proxy is asked for", tool.idle)
        // Another draw before it is built asks nothing more.
        tool.drawOverlay(Canvas(out), c.viewTransform)
        assertEquals(1, threads.size)

        assertTrue("the new proxy and its preview", Smoke.pumpUntil { tool.proxyReady && tool.idle && (tool.preview?.version ?: 0) > before.version })
        assertEquals("rendered once more", 2, threads.size)
        assertNotSame("off the main thread", main, threads[1])
        assertTrue("the right half shows now", tool.preview!!.mask[96 * 256 + 200].toInt() != 0)
        // The draws that follow ask for nothing.
        tool.drawOverlay(Canvas(out), c.viewTransform)
        assertTrue(tool.idle)
        Smoke.pump(100)
        assertEquals(2, threads.size)
        assertNull(c.message)
        Smoke.assertQuiet(c, "color select: no proxy in a draw")
        c.selectTool(ToolId.BRUSH)
    }

    @Test
    fun aRenderThatThrowsIsTriedOnceMoreThenKeptAndSaid() {
        val (c, tool, _) = editor()
        var tries = 0
        tool.renderSource = { tries++; throw IllegalStateException("render test") }
        c.message = null
        c.selectTool(ToolId.COLOR_SELECT)
        assertTrue(Smoke.pumpUntil { tool.idle && c.message != null })
        assertEquals("tried once more", 2, tries)
        assertFalse(tool.proxyReady)
        assertNull(tool.preview)
        assertEquals(StabilityLabels19.ERROR_TOAST.format(ToolId.COLOR_SELECT.label), c.message)
        CrashLog.drain()
        val r = CrashLog.reports(app).single()
        assertTrue(r.id, r.id.endsWith("-crash-quiet"))
        assertEquals("Handled error: IllegalStateException in color select: proxy", r.summary)
        // A draw on the same pixels asks nothing again.
        tool.drawOverlay(Canvas(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)), c.viewTransform)
        Smoke.pump(100)
        assertTrue(tool.idle)
        assertEquals(2, tries)
        Smoke.assertQuiet(c, "color select: a render that throws")
        c.selectTool(ToolId.BRUSH)
    }
}
