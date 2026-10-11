package com.brushwork.paint.qa18

import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_R
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_X
import com.brushwork.paint.qa18.Qa18SelectPictures.FRUIT_Y
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.8 final QA, item 3 (design §3.4 "a tap snaps to the strongest edge within Edge reach"): in a
 * photo an edge is stronger in one place than another (the orange of [Qa18SelectPictures.fruit]
 * is lit from the top left, its cloth is blotchy). A tap a little off the edge lands on the
 * edge's NEAREST point, across it. Before the fix the anchor slid along the edge to its single
 * strongest pixel within the reach (up to 11° on the orange at the phone's reach), and a point
 * dragged along the edge was pulled back toward the stronger part.
 */
@RunWith(RobolectricTestRunner::class)
class Qa18SelectMagneticSnapTest {

    @Test
    fun aTapLandsOnTheNearestPointOfAnEdgeWhoseStrengthVaries() {
        ShadowLog.stream = null
        val doc = Smoke.document(Qa18SelectPictures.SIZE, Qa18SelectPictures.SIZE, layers = 1)
        Qa18SelectPictures.fruit(doc.layers[0].bitmap)
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        c.viewTransform.set(Matrix())
        // The reach the phone has at its fit zoom (24 dp = 35 doc px): 24 × 1.45 view px at zoom 1.
        c.viewTransform.density = 1.45f
        c.selectTool(ToolId.MAGNETIC_LASSO)
        val tool = c.currentTool as MagneticLassoTool
        assertTrue(Smoke.pumpUntil(20_000L) { tool.edgeMapReady && tool.idle })
        val slips = ArrayList<Double>()
        val offs = ArrayList<Double>()
        // Taps 8 px outside the orange, every 10°.
        for (k in 0 until 36) {
            val deg = 10.0 * k
            val q = tool.snapped(FRUIT_X + (FRUIT_R + 8f) * cos(deg * PI / 180).toFloat(), FRUIT_Y + (FRUIT_R + 8f) * sin(deg * PI / 180).toFloat())
            val at = Math.toDegrees(atan2((q.y - FRUIT_Y).toDouble(), (q.x - FRUIT_X).toDouble()))
            slips += ((((at - deg) % 360.0 + 540.0) % 360.0 - 180.0) * 10).roundToInt() / 10.0
            offs += (Qa18SelectPictures.fromFruitRim(q.x, q.y) * 10).roundToInt() / 10.0
        }
        println("Qa18 magnetic snap, every 10°: angle from the tap's (degrees) $slips, px off the rim $offs")
        assertTrue("every tap lands across the rim from it, not along it: $slips", slips.all { abs(it) <= 1.5 })
        assertTrue("on the rim's centre line, not its flank: $offs px", offs.all { it <= 1.0 })
        Smoke.assertQuiet(c, "magnetic snap")
    }
}
