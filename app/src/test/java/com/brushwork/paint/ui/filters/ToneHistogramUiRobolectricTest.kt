package com.brushwork.paint.ui.filters

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.adjust.ToneFilter
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.fxsmoke.RecordingScope
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.color.RobolectricUi.hasText
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5 integration (A6 Tone x A5 filter UI, §4.8a): the Filters panel shows the luminance
 * histogram above Tone's sliders (and only for Tone), and Exposure reads signed with two
 * decimals ("+0.50 EV"), like Lightroom. 392 dp phone; its own sandbox (Compose frames stall in
 * later tests of a shared one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h851dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.filters.tonehistogramsandbox"])
class ToneHistogramUiRobolectricTest {
    private val rs = RecordingScope()

    @After
    fun tearDown() = rs.close()

    private fun histogramShown(): Boolean = RobolectricUi.elements().any { e ->
        e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "Luminance histogram" } == true
    }

    @Test
    fun theTonePanelShowsTheHistogramAndSignedExposure() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val d = Fx.newDoc(activity.applicationContext, rs.scope, 96, 72)
        val c = d.controller
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    val s = c.filterSession
                    if (s != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { FilterSessionPanel(s, Modifier.fillMaxWidth()) }
                }
            }
        }
        settle(3)
        c.startFilter(FilterRegistry.byId(ToneFilter.ID)!!)
        val s = c.filterSession!!
        Fx.awaitPreview(s, "tone")
        Fx.waitUntil("the histogram") { s.histogram != null }
        settle(3)
        assertTrue("Tone shows the luminance histogram", histogramShown())
        assertTrue("exposure at 0 reads 0.00 EV", hasText("0.00 EV"))
        s.update(ToneFilter.EXPOSURE, 0.5f)
        Fx.awaitPreview(s, "exposure")
        settle(3)
        assertTrue("signed exposure", hasText("+0.50 EV"))
        s.cancel()
        settle(3)

        // Another filter: no histogram above its sliders.
        c.startFilter(FilterRegistry.byId("adjust.invert")!!)
        val inv = c.filterSession!!
        Fx.awaitPreview(inv, "invert")
        Fx.waitUntil("the histogram") { inv.histogram != null }
        settle(3)
        assertFalse("only Tone shows it", histogramShown())
        inv.cancel()
        rs.assertNoErrors("tone histogram")
    }

    @Test
    fun exposureIsSignedWithTwoDecimalsAndOtherSlidersAreUnchanged() {
        val tone = FilterRegistry.byId(ToneFilter.ID)!!
        val ev = tone.params.first { it.key == ToneFilter.EXPOSURE } as FilterParam.Slider
        assertEquals("+0.50 EV", SliderFormat.format(ev, 0.5f))
        assertEquals("+5.00 EV", SliderFormat.format(ev, 5f))
        assertEquals("-1.25 EV", SliderFormat.format(ev, -1.25f))
        assertEquals("0.00 EV", SliderFormat.format(ev, 0f))
        assertEquals("0.00 EV", SliderFormat.format(ev, -0.001f))
        assertEquals("+0.01 EV", SliderFormat.format(ev, 0.01f))
        val contrast = tone.params.first { it.key == ToneFilter.CONTRAST } as FilterParam.Slider
        assertEquals("25", SliderFormat.format(contrast, 25f))
        assertEquals("-40", SliderFormat.format(contrast, -40f))
        assertTrue(showsHistogram(ToneFilter.ID))
        assertFalse(showsHistogram("adjust.invert"))
    }
}
