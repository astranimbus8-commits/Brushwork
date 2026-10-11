package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (items 8 and 9) at the design's narrowest phone (360 dp), where AUTO items leave
 * row 1 for the More row: every tool of the tool menu on a raster and on a vector layer, its More
 * row opened by a finger ([Qa18MenusShots.walkStrips]); rendered as `menus-360-*.png`. Then the
 * busiest strip, Transform with a lifted picture (its two-line hint and eight items), in box and
 * in Free deform mode: every item whole on screen within three lines, nothing scrolled away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.menusstripshots360sandbox"])
class Qa18MenusStripShots360Test {

    @Test
    fun everyToolsStripOpensItsMoreRowWholeOnScreenAt360() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        for (vector in listOf(false, true)) {
            h.section(if (vector) "every strip on a vector layer at 360 dp" else "every strip on a raster layer at 360 dp") {
                Qa18MenusShots.walkStrips(h, vector, widthDp = 360f)
            }
        }
        h.section("Transform with a lifted picture at 360 dp") { liftedTransform(h) }
        dog.interrupt()
        h.finish()
    }

    private fun more(s: ChromeScreen, what: String, name: String): Int {
        val (items, lines) = Qa18MenusShots.openMore(s, what)
        Qa18MenusShots.save(Qa18MenusShots.capture(), s.density, "360-busy-$name")
        System.err.println("[menus] 360 $what | More ($lines lines): ${Qa18MenusShots.names(items)}")
        Qa18MenusShots.closeMore(s, what)
        return items.size
    }

    private fun liftedTransform(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c ->
            check(c.editWholeLayer(c.doc.layers[1], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        }
        Qa18MenusShots.pick(s, ToolId.TRANSFORM)
        val t = s.c.currentTool as TransformTool
        assertTrue("the picture is lifted", Smoke.pumpUntil(10_000) { SmokeUi.settle(); t.transformState != null })
        // The eight items the 392 dp run shows (Qa18MenusBusyShotsTest): all whole on screen here too.
        assertEquals("Transform (box) at 360 dp: every More item whole on screen", 8, more(s, "Transform (box)", "transform"))
        Finger.tap(s, TransformTool.Mode.FREE.label)
        Smoke.pump(300); SmokeUi.settle()
        SmokeUi.tap(TransformTool.Mode.MESH.label, exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals(TransformTool.Mode.MESH, t.mode)
        assertEquals("Transform (Free deform) at 360 dp: every More item whole on screen", 4, more(s, "Transform (Free deform)", "transform-mesh"))
        Smoke.assertQuiet(s.c, "Transform at 360 dp")
    }
}
