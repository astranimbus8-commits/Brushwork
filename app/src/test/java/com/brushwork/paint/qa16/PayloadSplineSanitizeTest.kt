package com.brushwork.paint.qa16

import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.ImportTarget
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.6 final QA (found here): the two readers of Path-tool control points must agree. A `.vec`
 * file's splines are reduced to usable numbers when read (`VectorCodec.decode`: order 2..6,
 * weights 0.1..10, widths 0..3, finite points), but a Brushwork SVG / PDF's payload brought its
 * splines in as they were: a damaged or hand-made file put an order 9, weight 50 spline with a NaN
 * point into the artwork, and the next save and reload changed the document (the curve the user
 * sees in Path is the sanitized one all along). Imported splines are now sanitized as a reload
 * would: import, save, reload gives the same document.
 */
@RunWith(RobolectricTestRunner::class)
class PayloadSplineSanitizeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private val crafted = VSpline(
        listOf(
            VSplinePoint(40f, 60f, weight = 50f, width = 7f),
            VSplinePoint(Float.NaN, 80f),
            VSplinePoint(160f, 200f),
            VSplinePoint(300f, 90f, weight = 0f),
        ),
        order = 9,
    )
    private val path = VPath(
        1,
        subpaths = listOf(VSubpath(listOf(VAnchor(40f, 60f), VAnchor(160f, 200f, inX = -20f, inY = 0f), VAnchor(300f, 90f)))),
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
        spline = crafted,
    )

    @Test
    fun importedSplinesAreTheOnesAReloadGives() {
        val payload = BrushworkPayload(
            width = 400, height = 300,
            layers = listOf(
                PayloadLayer(
                    1, LayerProps("Vector 1", 1f, LayerBlendMode.NORMAL, true, false, false, false, true),
                    kind = PayloadKind.VECTOR, vector = VectorContent.EMPTY.plus(listOf(path)).first,
                ),
            ),
        )
        // Through the payload's JSON, as a file brings it (NaN is allowed there).
        val read = Payload.fromJson(Payload.toJson(payload))
        assertEquals("the JSON keeps the crafted numbers", crafted.points.size, (read.layers.single().vector!!.objects.single() as VPath).spline!!.points.size)
        val c = Smoke.controller(app, Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val target = ImportTarget(400, 300, c.doc.dpi, c.doc.colorMode, ImportLayers.room(c), c.maxLayers)
        PayloadImport.apply(c, PayloadImport.prepare(read, { null }, target))
        val layer = c.doc.layers.single { it.isVectorLayer }
        val imported = (layer.vector!!.objects.single() as VPath).spline!!
        val sanitized = crafted.sanitized()
        assertNotEquals("the crafted spline needs sanitizing", crafted, sanitized)
        assertEquals("imported as a reload reads it", sanitized, imported)
        assertEquals(6, imported.order)
        assertEquals(10f, imported.points[0].weight, 0f)
        assertEquals(3f, imported.points[0].width, 0f)
        // The .vec reader agrees, and so does a save and reload of the artwork.
        assertEquals(layer.vector, VectorCodec.decode(VectorCodec.encode(layer.vector!!)))
        val repo = ProjectRepository(app)
        runBlocking { repo.save(c.doc, null) }
        val loaded = runBlocking { repo.load(c.doc.id) }
        assertEquals("save and reload give the imported document", layer.vector, loaded.layers.single { it.isVectorLayer }.vector)
    }
}
