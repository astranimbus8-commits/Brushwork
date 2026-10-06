package com.brushwork.paint.exchange.export

import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapePoint
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 F1: the smaller codec additions, each written only when used (I13): a Shape corner's own
 * radius ([ShapePoint.radius], [ShapeCodec.VERSION] 2), the payload's layer tree and arrays
 * ([Payload.VERSION] 2, [PayloadKind.FOLDER]), the Curve tool's remembered stroke kind and the
 * export scene's folder fields. Pure JVM.
 */
class ModelCodecsV17Test {
    private fun square(radius: Float? = null) = ShapeObject(
        type = ShapeType.RECTANGLE, cx = 50f, cy = 50f, w = 40f, h = 40f,
        points = listOf(ShapePoint(-0.5f, -0.5f, radius = radius), ShapePoint(0.5f, -0.5f), ShapePoint(0.5f, 0.5f), ShapePoint(-0.5f, 0.5f)),
    )

    @Test
    fun aCornerRadiusIsWrittenOnlyWhenSet() {
        assertEquals(2, ShapeCodec.VERSION)
        val plain = ShapeCodec.encode(square())
        assertTrue(plain, plain.startsWith("{\"version\":2,"))
        assertFalse(plain, plain.contains("radius"))
        val round = ShapeCodec.encode(square(6f))
        assertTrue(round, round.contains("\"radius\":6.0"))
        assertEquals(6f, ShapeCodec.decode(round)!!.points!![0].radius)
        assertNull(ShapeCodec.decode(round)!!.points!![1].radius)
        // A v1.6 shape (format 1) has no corner radii.
        assertNull(ShapeCodec.decode(plain.replaceFirst("\"version\":2", "\"version\":1"))!!.points!![0].radius)
    }

    @Test
    fun aDamagedCornerRadiusIsSanitized() {
        assertEquals(0f, square(-3f).sanitized()!!.points!![0].radius)
        assertNull(square(Float.NaN).sanitized()!!.points!![0].radius)
        assertEquals(ShapeSettings.MAX_LENGTH, square(1e12f).sanitized()!!.points!![0].radius!!, 1f)
        assertEquals(12f, square(12f).sanitized()!!.points!![0].radius)
    }

    @Test
    fun aCornerRadiusSurvivesTheConversionsToAnchorsAndBack() {
        val o = square(7f)
        val anchors = ShapePoints.docAnchors(o.box, o.points!!)
        assertEquals(7f, anchors[0].radius)
        assertNull(anchors[1].radius)
        val (box, back) = ShapePoints.fit(o.rotation, anchors, closed = true)
        assertEquals(7f, back[0].radius)
        assertEquals(7f, ShapePoints.normalize(box, anchors)[0].radius)
        assertEquals(7f, ShapePoints.setSmooth(anchors, 0, true)[0].radius)
    }

    private val props = LayerProps("Layer", 1f, LayerBlendMode.NORMAL, visible = true, clipping = false, alphaLocked = false, locked = false, maskEnabled = true)

    @Test
    fun aPayloadWritesTheTreeAndArraysOnlyWhenUsed() {
        assertEquals(2, Payload.VERSION)
        val flat = BrushworkPayload(width = 64, height = 64, layers = listOf(PayloadLayer(1, props)))
        val text = String(Payload.toJson(flat), Charsets.UTF_8)
        for (key in listOf("parentId", "folder", "folderOpen", "array")) assertFalse("$key: $text", text.contains("\"$key\""))
        // Without them the payload is v1.6's, version number included (I13).
        assertTrue(text, text.startsWith("{\"version\":1,"))
        assertEquals(1, Payload.writtenVersion(flat))
        assertEquals(flat, Payload.fromJson(Payload.toJson(flat)))

        val tree = BrushworkPayload(
            // (A builder that puts v1.7 data in passes the version it is written with.)
            version = Payload.VERSION, width = 64, height = 64,
            layers = listOf(
                PayloadLayer(2, props, parentId = 5, array = PayloadArray("{\"count\":3}", "QldBUg==")),
                PayloadLayer(5, props, kind = PayloadKind.FOLDER, folder = FolderSpec(passThrough = false), folderOpen = false),
            ),
        )
        val back = Payload.fromBase64(Payload.toBase64(tree))
        assertEquals(tree, back)
        val folderJson = String(Payload.toJson(tree), Charsets.UTF_8)
        assertTrue(folderJson, folderJson.startsWith("{\"version\":2,"))
        // A layer in a folder alone (a payload cut from a bigger one) also needs version 2.
        assertEquals(2, Payload.writtenVersion(flat.copy(layers = listOf(PayloadLayer(1, props, parentId = 3)))))
        assertTrue(folderJson, folderJson.contains("\"kind\":\"FOLDER\""))
        assertTrue(folderJson, folderJson.contains("\"folder\":{\"passThrough\":false}"))
        // An unknown kind (a later version's) reads as a raster layer, as FOLDER does in v1.6.
        val later = Payload.fromJson(folderJson.replace("\"FOLDER\"", "\"GALAXY\"").toByteArray())
        assertEquals(PayloadKind.RASTER, later.layers[1].kind)
    }

    @Test
    fun theCurveToolRemembersAStrokeKindNeverNone() {
        assertEquals(CurveStroke.BRUSH, CurveSettings().lastStroke)
        assertEquals(CurveStroke.PLAIN, CurveSettings(lastStroke = CurveStroke.PLAIN).sanitized().lastStroke)
        assertEquals(CurveStroke.BRUSH, CurveSettings(lastStroke = CurveStroke.NONE).sanitized().lastStroke)
    }

    @Test
    fun aSceneLayerIsALeafAndIsolatedByDefault() {
        val l = SceneLayer("layer-1", "Layer", 1f, LayerBlendMode.NORMAL, hidden = false, mask = null, items = emptyList())
        assertTrue(l.children.isEmpty())
        assertTrue(l.isolated)
        val folder = SceneLayer("layer-2", "Folder", 0.5f, LayerBlendMode.MULTIPLY, false, null, emptyList(), children = listOf(l), isolated = false)
        assertEquals(listOf(l), folder.children)
        assertFalse(folder.isolated)
    }
}
