package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.vector.CopyMaps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * v1.8 F1 (§4.3): every [ShapeObject] constructor property survives [ShapeObject.sanitized] and a
 * [ShapeCodec] round trip. The fixture sets every property to a usable value other than its
 * default (checked per serial element, so a new property fails here until the fixture sets it),
 * except [ShapeObject.cogFallback]: only [ShapeObject.forWriting] sets it, with the fallback
 * points, and reading drops both (D23); that pair is checked on its own. JVM.
 */
class ShapeObjectFieldsTest {
    private val json = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }

    private fun obj(s: ShapeObject): JsonObject = json.encodeToJsonElement(ShapeObject.serializer(), s).jsonObject

    private val names: List<String> = ShapeObject.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }

    /** Set by forWriting() only (with the fallback points); never kept by a reader. */
    private val writtenOnly = setOf("cogFallback")

    private val preset = BrushPreset("calli", "Calligraphy", BrushTip.CALLIGRAPHY, size = 17.5f, opacity = 0.8f, angle = 30f)
    private val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)

    private val full = ShapeObject(
        type = ShapeType.COG, cx = 120f, cy = 80.5f, w = 60f, h = 44f, rotation = 33f, style = ShapeStyle.STROKE_FILL,
        strokeWidth = 6f, strokeWith = ShapeStroke.BRUSH, strokeColor = 0xFF102030.toInt(), fillColor = 0x80FF8000.toInt(),
        fillFollowsColor = false, lineCap = LineCapStyle.SQUARE, corner = CornerStyle.ROUND, cornerRadius = 3f, sides = 7,
        starPoints = 6, innerRatio = 0.3f, arrowHeads = ArrowHeads.BOTH, arrowHeadStyle = ArrowHeadStyle.OPEN, arrowHeadScale = 6f,
        brushTool = ToolId.BRUSH.name, brushPreset = preset,
        points = listOf(
            ShapePoint(-0.5f, -0.5f, radius = 4f),
            ShapePoint(0.5f, -0.5f, smooth = true, handleIn = ShapeHandle(-0.1f, 0f), handleOut = ShapeHandle(0.1f, 0.02f)),
            ShapePoint(0.5f, 0.5f),
            ShapePoint(-0.5f, 0.5f),
        ),
        cogTeeth = 20, cogDepth = 0.3f, cogToothWidth = 0.6f, cogTaper = 0.5f, cogHole = 0.4f,
        copies = CopyMaps.of(listOf(mirror)),
    )

    @Test
    fun theFixtureSetsEveryProperty() {
        val fields = ShapeObject::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
        assertEquals("the serial elements are the constructor properties", fields.toSet(), names.toSet())
        val def = obj(ShapeObject())
        val f = obj(full)
        for (n in names) {
            if (n in writtenOnly) continue
            assertNotEquals("$n is set in the fixture", def[n], f[n])
        }
        assertEquals(setOf("cogFallback"), writtenOnly.filter { it in names }.toSet())
    }

    @Test
    fun sanitizedKeepsEveryProperty() {
        assertSame(full, full.sanitized())
        // Also when sanitized() has to rebuild the shape (another value out of range).
        val f = obj(full)
        val rebuilt = obj(full.copy(cogTeeth = 500).sanitized()!!)
        for (n in names) if (n != "cogTeeth") assertEquals("$n survives a rebuild", f[n], rebuilt[n])
    }

    @Test
    fun theShapeCodecKeepsEveryProperty() {
        // Custom points: the cog is written as it is (no fallback outline).
        assertSame(full, full.forWriting())
        val back = ShapeCodec.decode(ShapeCodec.encode(full))!!
        val f = obj(full)
        val b = obj(back)
        for (n in names) assertEquals("$n survives the shape codec", f[n], b[n])
        assertEquals(full, back)
    }

    @Test
    fun theFallbackOutlineIsWrittenAndDroppedWithItsFlag() {
        val cog = full.copy(points = null)
        val written = cog.forWriting()
        assertEquals(true, written.cogFallback)
        assertEquals(ShapeGeometry.vertices(ShapeType.COG, 1f, 1f, cog.outlineParams).size, written.points!!.size)
        val read = written.sanitized()!!
        assertNull(read.points)
        assertFalse(read.cogFallback)
        val c = obj(cog)
        val r = obj(read)
        for (n in names) assertEquals("$n after the fallback", c[n], r[n])
        assertEquals(cog, ShapeCodec.decode(ShapeCodec.encode(cog)))
    }
}
