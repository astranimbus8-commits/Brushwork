package com.brushwork.paint.ui.array

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VectorLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.7 integration pass "arrayrender" (§6.3; area E) on the user's 392 dp phone, by label: the
 * Array sheet's "Increase Count" on a raster array whose cache renders in the background (an
 * injected slow renderer) records nothing at once and keeps the preview up; "Rendering array…"
 * appears in the Array tool's options strip once the render has run 300 ms, not before, and goes
 * when it lands as ONE "Edit array" step. One test: Compose's frame clock serves only the first
 * test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.array.arrayrenderingsandbox"])
class ArrayRenderingStripUiTest {
    private lateinit var h: ChromeHarness

    @Test
    fun renderingArrayInTheStripAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("a long array render shows \"Rendering array…\" past 300 ms") { slowRender() }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    private fun slowRender() {
        val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
        val ui = Qa16Ui(s)
        val c = s.c
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(60f, 60f, 100f, 100f, Paint().apply { color = 0xFFDD2211.toInt() })
        src.markChanged()
        c.selectLayer(src)
        c.setSelection(Selection.fromPath(Path().apply { addRect(50f, 50f, 110f, 110f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        settle(4)
        ui.reach(ArrayLabels.FROM_SELECTION)
        click(ArrayLabels.FROM_SELECTION, exact = true)
        c.setSelection(null, recordUndo = false)
        settle(4)
        val layer = c.activeLayer
        assertNotNull(layer.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertFalse(has(ArrayLabels.RENDERING))

        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        val gate = CountDownLatch(1)
        c.arrayRenders.workerHook = { gate.await(20, TimeUnit.SECONDS) }
        val steps = c.undoManager.undoCount
        try {
            ui.reach("Increase Count")
            click("Increase Count", exact = true, settleAfter = false)
            assertTrue("rendering in the background", c.arrayRenders.isPending)
            assertEquals("no step yet", steps, c.undoManager.undoCount)
            assertEquals(3, layer.array!!.spec.count)
            assertNotNull("the preview shows the new copies", c.renderOverride)
            settle(2, 100)
            assertFalse("not at 200 ms", has(ArrayLabels.RENDERING))
            settle(2, 100)
            assertTrue("\"${ArrayLabels.RENDERING}\" in the strip past 300 ms; shown: ${SmokeUi.shown().take(60)}", has(ArrayLabels.RENDERING))
        } finally {
            gate.countDown()
        }
        assertTrue(Smoke.pumpUntil { !c.arrayRenders.isPending })
        settle(4)
        assertFalse("gone once it landed", has(ArrayLabels.RENDERING))
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        assertEquals(4, layer.array!!.spec.count)
        assertNull(c.renderOverride)
    }
}
