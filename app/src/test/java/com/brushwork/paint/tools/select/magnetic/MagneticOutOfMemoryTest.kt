package com.brushwork.paint.tools.select.magnetic

import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.ui.common.MagneticLabels18
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 integration (v18I): the magnetic lasso without the memory for its edge map says
 * [MagneticLabels18.OUT_OF_MEMORY] once, and its segments stay straight: the touches that follow
 * neither try again nor say it again. The next activation tries again (memory may be free by
 * then) and, with memory, builds the map and says nothing.
 */
@RunWith(RobolectricTestRunner::class)
class MagneticOutOfMemoryTest {

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    @Test
    fun noMemoryForTheEdgeMapIsSaidOnceAndTheOutlineStaysStraight() {
        ShadowLog.stream = null
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), Smoke.document(400, 300, whiteBottom = true))
        val tool = c.tools[ToolId.MAGNETIC_LASSO] as MagneticLassoTool
        val real = tool.renderSource
        var tries = 0
        tool.renderSource = { _ ->
            tries++
            throw OutOfMemoryError("test")
        }
        c.message = null
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertTrue(Smoke.pumpUntil { tool.idle })
        assertEquals("the activation tried once", 1, tries)
        assertEquals("said once", MagneticLabels18.OUT_OF_MEMORY, c.message)
        assertFalse(tool.edgeMapReady)

        // Touches on the same pixels: no new try, nothing said again; the anchors go where the
        // finger is and their segment stays straight (never traced).
        c.message = null
        tap(c, 100f, 100f)
        tap(c, 300f, 120f)
        tap(c, 200f, 220f)
        assertTrue(Smoke.pumpUntil { tool.idle })
        assertEquals(3, tool.anchorCount)
        assertEquals(1, tries)
        assertNull("not said again", c.message)
        assertFalse(tool.edgeMapReady)
        assertEquals(300f, tool.points.x(1))
        assertEquals(120f, tool.points.y(1))
        for (i in 0 until 2) assertFalse("segment $i stays straight", tool.isTraced(i))
        // The same Refer once more (a settings change that keeps it) does not try again either.
        assertEquals(SampleSource.LAYER, tool.settings.source)
        tool.updateSettings(tool.settings.copy(source = SampleSource.LAYER))
        assertEquals(1, tries)
        tool.discard()

        // The next activation tries again; with memory now, the map is built and nothing is said.
        tool.renderSource = real
        c.selectTool(ToolId.BRUSH)
        c.message = null
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        assertNull(c.message)
        Smoke.assertQuiet(c, "magnetic lasso out of memory")
    }
}
