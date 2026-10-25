package com.brushwork.paint.v110.a4

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.ObjectColorTargets
import com.brushwork.paint.filters.adjust.ToneFilter
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.CdcLabels110
import com.brushwork.paint.ui.filters.FilterBrowser
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 (R6, A4 step 5): the rasterize warning covers every layer with editable data. The Filters
 * sheet's banner names the layer's kind (text, shape, vector; a raster layer with a live array)
 * and, where Change Drawing Color recolors the objects instead, says it keeps the layer editable;
 * a plain raster layer shows none. In the filter's panel a pixel filter on a text layer says
 * " · applying rasterizes it", while Change Drawing Color in object mode shows
 * [CdcLabels110.OBJECTS] instead (plus [CdcLabels110.SELECTION_WHOLE] with a pixel selection);
 * on a raster layer it shows neither. None of these texts is cut at font scale 2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a4.rasterizewarningsandbox"])
class RasterizeWarningTextLayerTest {
    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    private fun keeps(kind: String) = "Applying rasterizes this $kind layer; ${cdc.name} keeps it editable"

    /** The panel's texts new in 1.10 (I27: the ones checked at font scale 2; the header's lines ellipsize by design). */
    private val mine = listOf(CdcLabels110.OBJECTS, CdcLabels110.SELECTION_WHOLE)

    private fun textLayer(kit: ObjectTestKit, c: EditorController) {
        val spec = TextSpec(sizePx = 32f, color = 0xFF202020.toInt(), strokeWidthPx = 2f, strokeColor = 0xFFFFD000.toInt())
        val text = TextCodec.encodeAll(listOf(TextItem("Brush", spec, cx = 90f, cy = 70f, id = 1)))
        assertTrue(DataRender.apply(c, c.vec, LayerData(text = text), "Text", Rect(0, 0, kit.w, kit.h), allowHidden = false))
        assertTrue(c.vec.isTextLayer)
    }

    private fun shapeLayer(kit: ObjectTestKit, c: EditorController) {
        val ring = ShapeObject(ShapeType.ELLIPSE, cx = 128f, cy = 96f, w = 120f, h = 90f, style = ShapeStyle.STROKE_FILL, strokeWidth = 5f,
            strokeColor = 0xFF102030.toInt(), fillColor = 0xFFF0E0D0.toInt())
        assertTrue(DataRender.apply(c, c.vec, LayerData(shape = ShapeCodec.encode(ring)), "Shape", Rect(0, 0, kit.w, kit.h), allowHidden = false))
        assertTrue(c.vec.isShapeLayer)
    }

    /** The Filters sheet over [c]'s active layer; whether its banner reads [banner] exactly (or shows no warning when null). */
    private fun assertBanner(h: Qa19Harness, c: EditorController, banner: String?, fontScale: Float = 1f) {
        h.show(fontScale) { FilterBrowser(c, onDismiss = {}) }
        Qa19Ui.settle()
        assertTrue("the Filters sheet", Qa19Ui.has("Search filters"))
        if (banner == null) assertFalse("no rasterize warning", Qa19Ui.has("Applying rasterizes"))
        else assertTrue("the banner \"$banner\"", Qa19Ui.has(banner, exact = true))
    }

    /** [c]'s filter panel, the way the editor shows it at the bottom. */
    private fun showPanel(h: Qa19Harness, c: EditorController, fontScale: Float = 1f) {
        h.show(fontScale) {
            Box(Modifier.fillMaxSize()) {
                val s = c.filterSession
                if (s != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { FilterSessionPanel(s, Modifier.fillMaxWidth()) }
            }
        }
    }

    private fun start(kit: ObjectTestKit, c: EditorController, id: String): FilterSession {
        c.startFilter(FilterRegistry.byId(id)!!)
        val s = c.filterSession!!
        s.debounceMs = 0L
        assertTrue("the preview of $id", kit.idleUntil { s.hasPreview && !s.isRendering && !s.previewStale })
        Qa19Ui.settle()
        return s
    }

    private fun stop(c: EditorController) {
        c.filterSession?.cancel()
        Qa19Ui.settle()
    }

    private fun withKit(block: (ObjectTestKit) -> Unit) {
        val kit = ObjectTestKit(256, 192)
        try {
            block(kit)
        } finally {
            kit.close()
        }
    }

    @Test
    fun theRasterizeWarningCoversEveryLayerWithEditableData() = Qa19Ui.uiTest { h ->
        h.section("the banner on a text layer") {
            withKit { kit -> val c = kit.controller(); textLayer(kit, c); assertBanner(h, c, keeps("text")) }
        }
        h.section("the banner on a shape layer") {
            withKit { kit -> val c = kit.controller(); shapeLayer(kit, c); assertBanner(h, c, keeps("shape")) }
        }
        h.section("the banner on a vector layer (v1.9's text kept, with what CDC keeps)") {
            withKit { kit ->
                val c = kit.controller()
                c.vectors.addObjects(c.vec, listOf(kit.box(30f, 30f, 120f, 110f)), "Add")
                assertBanner(h, c, keeps("vector"))
            }
        }
        h.section("the banner on a raster layer with an array, and on a plain raster layer") {
            withKit { kit ->
                val c = kit.controller()
                c.selectLayer(0)
                assertBanner(h, c, null)
                c.doc.layers[0].array = LayerArray(ArraySpec(mode = ArrayMode.LINE, count = 3, relativeX = 0f, constantX = 60f, constantY = 0f))
                assertBanner(h, c, "Applying rasterizes this array layer")
            }
        }
        h.section("the panel on a text layer: Tone warns, CDC keeps the texts") {
            withKit { kit ->
                val c = kit.controller()
                textLayer(kit, c)
                showPanel(h, c)
                start(kit, c, ToneFilter.ID)
                assertTrue("Tone rasterizes the text layer", Qa19Ui.has(" · applying rasterizes it"))
                assertFalse(Qa19Ui.has(CdcLabels110.OBJECTS, exact = true))
                stop(c)

                val s = start(kit, c, cdc.id)
                assertTrue(s.objectMode)
                assertTrue("object mode's subtitle", Qa19Ui.has(CdcLabels110.OBJECTS, exact = true))
                assertFalse("CDC does not rasterize it", Qa19Ui.has(" · applying rasterizes it"))
                assertFalse("no pixel selection", Qa19Ui.has(CdcLabels110.SELECTION_WHOLE, exact = true))
                stop(c)

                c.setSelection(kit.rectSelection(40, 40, 140, 100), recordUndo = false)
                val sel = start(kit, c, cdc.id)
                assertEquals(ObjectColorTargets.Rule.SELECTION, sel.objectRule)
                assertTrue(Qa19Ui.has(CdcLabels110.OBJECTS, exact = true))
                assertTrue("the second subtitle line", Qa19Ui.has(CdcLabels110.SELECTION_WHOLE, exact = true))
                stop(c)
            }
        }
        h.section("the panel on a shape layer and on a raster layer") {
            withKit { kit ->
                val c = kit.controller()
                shapeLayer(kit, c)
                showPanel(h, c)
                start(kit, c, cdc.id)
                assertTrue(Qa19Ui.has(CdcLabels110.OBJECTS, exact = true))
                assertFalse(Qa19Ui.has(" · applying rasterizes it"))
                stop(c)
                start(kit, c, ToneFilter.ID)
                assertTrue("Tone rasterizes the shape layer", Qa19Ui.has(" · applying rasterizes it"))
                stop(c)

                c.selectLayer(0)
                Qa19Ui.settle()
                val s = start(kit, c, cdc.id)
                assertFalse("CDC filters a raster layer's pixels", s.objectMode)
                assertFalse(Qa19Ui.has(CdcLabels110.OBJECTS, exact = true))
                assertFalse(Qa19Ui.has(" · applying rasterizes it"))
                stop(c)
            }
        }
        h.section("at font scale 2") {
            withKit { kit ->
                val c = kit.controller()
                textLayer(kit, c)
                assertBanner(h, c, keeps("text"), fontScale = 2f)
                Qa19Ui.assertNoTextOverflow("the banner at font scale 2", except = { it != keeps("text") })
            }
        }
        h.section("the panel at font scale 2") {
            withKit { kit ->
                val c = kit.controller()
                textLayer(kit, c)
                c.setSelection(kit.rectSelection(40, 40, 140, 100), recordUndo = false)
                showPanel(h, c, fontScale = 2f)
                start(kit, c, cdc.id)
                assertTrue(Qa19Ui.has(CdcLabels110.SELECTION_WHOLE, exact = true))
                Qa19Ui.assertNoTextOverflow("the object-mode panel at font scale 2", except = { it !in mine })
                stop(c)
            }
        }
    }
}
