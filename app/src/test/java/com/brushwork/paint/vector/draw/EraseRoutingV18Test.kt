package com.brushwork.paint.vector.draw

import android.graphics.Bitmap
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R11, design 2.4.12): F1b's [EraseRouting] reproduces v1.8. The Eraser brush erases objects
 * exactly where v1.8's `BrushTool.start` did (`layer.isVectorLayer && !maskTarget && !isPath`),
 * over every kind of layer, target and stroke; the four tools are refused; CONVERT_THEN_OBJECTS is
 * never the route.
 */
@RunWith(RobolectricTestRunner::class)
class EraseRoutingV18Test {

    private fun layer(kind: String): Layer = Layer(1L, kind, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)).also { l ->
        when (kind) {
            "text" -> l.textData = "{}"
            "shape" -> l.shapeData = "{}"
            "vector" -> l.vector = VectorContent.EMPTY
            "adjustment" -> l.adjustment = AdjustmentSpec(filterId = "adjust.invert")
            "folder" -> l.folder = FolderSpec()
        }
    }

    private val kinds = listOf("raster", "text", "shape", "vector", "adjustment", "folder")

    @Test
    fun theEraserErasesObjectsExactlyWhereV18DidAndNeverConvertsFirst() {
        for (kind in kinds) for (mask in listOf(false, true)) for (path in listOf(false, true)) {
            val l = layer(kind)
            val route = EraseRouting.route(l, EraseSource.ERASER_BRUSH, mask, path)
            val v18 = l.isVectorLayer && !mask && !path
            assertEquals("$kind, mask $mask, path $path: $route", v18, route == EraseRoute.OBJECTS)
            assertNotEquals("$kind, mask $mask, path $path", EraseRoute.CONVERT_THEN_OBJECTS, route)
        }
    }

    @Test
    fun theTableOfF1b() {
        fun r(kind: String, mask: Boolean = false, path: Boolean = false) = EraseRouting.route(layer(kind), EraseSource.ERASER_BRUSH, mask, path)
        assertEquals(EraseRoute.RASTER, r("raster"))
        assertEquals(EraseRoute.RASTER, r("raster", path = true))
        for (kind in listOf("raster", "text", "shape", "vector", "adjustment")) assertEquals(kind, EraseRoute.RASTER, r(kind, mask = true))
        assertEquals(EraseRoute.OBJECTS, r("vector"))
        assertEquals(EraseRoute.RASTERIZE_DATA, r("vector", path = true))
        assertEquals(EraseRoute.RASTERIZE_DATA, r("text"))
        assertEquals(EraseRoute.RASTERIZE_DATA, r("shape"))
        assertEquals(EraseRoute.RASTERIZE_DATA, r("shape", path = true))
        assertEquals(EraseRoute.REFUSE, r("adjustment"))
        assertEquals(EraseRoute.REFUSE, r("folder"))
        assertEquals(EraseRoute.REFUSE, r("folder", mask = true))
    }

    @Test
    fun theFourToolsAreRefusedInF1b() {
        for (source in EraseSource.entries.filter { it != EraseSource.ERASER_BRUSH }) {
            for (kind in kinds) for (mask in listOf(false, true)) {
                assertEquals("$source on $kind, mask $mask", EraseRoute.REFUSE, EraseRouting.route(layer(kind), source, mask, false))
            }
        }
    }
}
