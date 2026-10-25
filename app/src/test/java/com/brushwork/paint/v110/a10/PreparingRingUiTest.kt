package com.brushwork.paint.v110.a10

import android.os.SystemClock
import com.brushwork.paint.EditorController
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.engine.PREPARING_TAG
import com.brushwork.paint.ui.engine.RING_DELAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 A10 (design 2.7, step 4; I27): the activation ring in the real editor
 * (`EngineEditorChrome`), at 392 and 360 dp and at each font scale. While
 * `EditorController.activating` holds a layer, nothing shows for the first [RING_DELAY_MS]
 * (250 ms); after that the ring (tagged [PREPARING_TAG]) shows [EngineLabels110.PREPARING_LAYER],
 * whole and inside the screen, until the layer is ready. An activation that ends sooner shows
 * nothing at all.
 *
 * One UI test (the frame clock serves the first test of a sandbox); its cases are sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a10.preparingringsandbox"])
class PreparingRingUiTest {
    @get:Rule
    val engine = EngineRule()

    @Test
    fun theRingAfterAQuarterSecond() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                val where = "at ${width.substringBefore('-')} and font scale $scale"
                h.section(where) {
                    RuntimeEnvironment.setQualifiers(width)
                    ring(h, scale, if (width == Qa19Ui.Q360) 360 else 392, where)
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun shown() = Qa19Ui.tagged(PREPARING_TAG) != null

    /** Wall and clock time since ([wall], [uptime]): the ring's delay runs on one or the other. */
    private fun waited(wall: Long, uptime: Long): Long =
        maxOf(System.currentTimeMillis() - wall, SystemClock.uptimeMillis() - uptime)

    private fun ring(h: Qa19Harness, scale: Float, widthDp: Int, where: String) {
        val screen = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), fontScale = scale)
        assertEquals(where, widthDp, screen.activity.resources.configuration.screenWidthDp)
        val c: EditorController = screen.c
        val layer = c.doc.layers.last()
        assertFalse("$where: no ring while nothing activates", shown())

        // A quick activation: nothing.
        c.activating = layer
        Qa19Ui.settle(1, 10)
        assertFalse("$where: no ring at once", shown())
        c.activating = null
        val n0 = System.currentTimeMillis()
        val u0 = SystemClock.uptimeMillis()
        A10Kit.pumpUntil(5_000) { Qa19Ui.settle(1); waited(n0, u0) > RING_DELAY_MS * 2 }
        assertFalse("$where: a quick activation never shows the ring", shown())

        // A slow one: the ring after the delay, until the layer is ready.
        val wall = System.currentTimeMillis()
        val uptime = SystemClock.uptimeMillis()
        c.activating = layer
        assertTrue("$where: the ring shows", A10Kit.pumpUntil(10_000) { Qa19Ui.settle(1); shown() })
        assertTrue("$where: not before ${RING_DELAY_MS} ms (${waited(wall, uptime)} ms)", waited(wall, uptime) >= RING_DELAY_MS)
        assertTrue("$where: its text; shown ${Qa19Ui.shown()}", Qa19Ui.has(EngineLabels110.PREPARING_LAYER, exact = true))
        val ring = Qa19Ui.tagged(PREPARING_TAG)
        assertNotNull(where, ring)
        val root = screen.root
        val box = ring!!.boundsInWindow
        assertTrue("$where: inside the screen ($box in $root)", box.left >= root.left - 1f && box.right <= root.right + 1f && box.top >= root.top)
        Qa19Ui.assertNoTextOverflow("$where, the ring")

        c.activating = null
        assertTrue("$where: gone once the layer is ready", A10Kit.pumpUntil(5_000) { Qa19Ui.settle(1); !shown() })
        assertNull(where, Qa19Ui.tagged(PREPARING_TAG))
    }
}
