package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * v1.8 F2 (design §4.7): [ShapeOutlines] moved out of ShapeObject.kt into its own file without a
 * change. Every query it answers (outline, arrow parts, brush outline, join, reach, hit testing on
 * a grid, feature points) is hashed on fixture shapes of every type, corner style, arrowhead
 * combination and custom points; the hashes were taken on the code before the move (v1.8 F1,
 * 580fcc35). Coordinates are rounded to 1e-3 px so the hash only moves with the geometry. (The
 * COG entry is the interim zigzag: the cog's own geometry, when it lands, updates that one hash.)
 */
class ShapeOutlinesParityTest {

    @Test
    fun everyQueryHashesAsBeforeTheMove() {
        val actual = LinkedHashMap<String, String>()
        for ((name, shapes) in fixtures()) actual[name] = digest(shapes)
        val report = actual.entries.joinToString("\n") { (k, v) -> "        \"$k\" to \"$v\"," }
        assertEquals("ShapeOutlines answers moved:\n$report", EXPECTED, actual)
    }

    @Test
    fun shapeOutlinesLivesInItsOwnFile() {
        val dir = mainSources() ?: return // (the sources are not on disk: nothing to check)
        val own = File(dir, "tools/vector/ShapeOutlines.kt")
        assertTrue("tools/vector/ShapeOutlines.kt exists", own.isFile)
        assertTrue(own.readText().contains("object ShapeOutlines {"))
        assertFalse("ShapeObject.kt no longer declares it", File(dir, "tools/vector/ShapeObject.kt").readText().contains("object ShapeOutlines"))
        assertTrue("tools/vector/CogGeometry.kt exists", File(dir, "tools/vector/CogGeometry.kt").isFile)
    }

    @Test
    fun cogGeometryStubIsTheInterimOutlineWithoutAHole() {
        val box = ShapeBox(80f, 60f, 120f, 100f, 30f)
        for (hole in floatArrayOf(0f, 0.5f)) for (corner in CornerStyle.entries) {
            val p = OutlineParams(corner = corner, cornerRadius = 6f, cog = CogParams(teeth = 9, depth = 0.3f, hole = hole))
            val contours = CogGeometry.contours(box, p)
            assertEquals("$corner hole=$hole", ShapeGeometry.outline(ShapeType.COG, box, p).ops, contours.ops)
            assertEquals("one contour (no hole yet)", 1, contours.ops.count { it is PathOp.MoveTo })
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun fixtures(): Map<String, List<ShapeObject>> {
        val out = LinkedHashMap<String, List<ShapeObject>>()
        val brush = BrushLibrary.defaultBrush.copy(size = 14f)
        for (type in ShapeType.entries) {
            val list = ArrayList<ShapeObject>()
            val line = type.isLineLike
            for (corner in CornerStyle.entries) for (style in ShapeStyle.entries) {
                list += ShapeObject(
                    type = type, cx = 140.5f, cy = 96.25f, w = 180f, h = if (line) 0f else 110f, rotation = 23f,
                    style = style, strokeWidth = 9f, corner = corner, cornerRadius = 16f, sides = 7, starPoints = 6,
                    innerRatio = 0.4f, cogTeeth = 10, cogDepth = 0.25f,
                )
            }
            if (type == ShapeType.ARROW) for (heads in ArrowHeads.entries) for (hs in ArrowHeadStyle.entries) {
                list += ShapeObject(type = type, cx = 60f, cy = 70f, w = 150f, h = 0f, rotation = -40f, strokeWidth = 6f,
                    arrowHeads = heads, arrowHeadStyle = hs, arrowHeadScale = 5f)
                // A custom (curved) arrow.
                list += ShapeObject(type = type, cx = 60f, cy = 70f, w = 150f, h = 40f, rotation = 12f, strokeWidth = 6f,
                    arrowHeads = heads, arrowHeadStyle = hs, arrowHeadScale = 5f,
                    points = listOf(ShapePoint(-0.5f, 0f), ShapePoint(0f, 0.4f, smooth = true), ShapePoint(0.5f, -0.2f)))
            }
            if (type.hasCorners || type == ShapeType.ELLIPSE) for (corner in CornerStyle.entries) {
                val params = OutlineParams(sides = 6, starPoints = 5, innerRatio = 0.4f, corner = corner, cornerRadius = 11f)
                list += ShapeObject(type = type, cx = 30f, cy = 40f, w = 90f, h = 70f, rotation = -15f, style = ShapeStyle.entries.last(),
                    corner = corner, cornerRadius = 11f, sides = 6, starPoints = 5, innerRatio = 0.4f,
                    points = ShapePoints.fromRegular(if (type == ShapeType.COG) ShapeType.STAR else type, params))
            }
            if (line) {
                list += ShapeObject(type = type, cx = 10f, cy = 20f, w = 120f, h = 30f, rotation = 70f, strokeWidth = 5f,
                    points = listOf(ShapePoint(-0.5f, 0.5f), ShapePoint(0.1f, -0.5f, smooth = true), ShapePoint(0.5f, 0.3f)))
            }
            // A brush outline.
            list += ShapeObject(type = type, cx = 50f, cy = 50f, w = 80f, h = if (line) 0f else 60f, rotation = 5f,
                strokeWith = ShapeStroke.BRUSH, brushPreset = brush, brushTool = ToolId.BRUSH.name, strokeWidth = 4f)
            out[type.name] = list
        }
        return out
    }

    // ------------------------------------------------------------------ hashing

    private fun digest(shapes: List<ShapeObject>): String {
        val sb = StringBuilder()
        for (o in shapes) {
            sb.append("outline:"); path(sb, ShapeOutlines.outline(o))
            sb.append("brush:"); path(sb, ShapeOutlines.brushOutline(o))
            if (o.type == ShapeType.ARROW) {
                val a = ShapeOutlines.arrow(o)
                sb.append("arrow:"); path(sb, a.stroke); path(sb, a.fill)
            }
            sb.append("custom:").append(ShapeOutlines.isCustom(o))
            sb.append(" join:").append(ShapeOutlines.join(o))
            sb.append(" reach:").append(q(ShapeOutlines.reach(o)))
            sb.append(" features:"); for (p in ShapeOutlines.featurePoints(o)) vec(sb, p)
            sb.append(" hits:")
            val r = maxOf(o.w, o.h) * 0.75f + 20f
            for (j in 0 until 24) for (i in 0 until 24) {
                val p = Vec2(o.cx - r + 2f * r * i / 23f, o.cy - r + 2f * r * j / 23f)
                sb.append(if (ShapeOutlines.hits(o, p, 2.5f)) '1' else '0')
            }
            sb.append('\n')
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun path(sb: StringBuilder, p: VectorPath) {
        for (op in p.ops) when (op) {
            is PathOp.MoveTo -> { sb.append('M'); vec(sb, op.p) }
            is PathOp.LineTo -> { sb.append('L'); vec(sb, op.p) }
            is PathOp.CubicTo -> { sb.append('C'); vec(sb, op.c1); vec(sb, op.c2); vec(sb, op.p) }
            PathOp.Close -> sb.append('Z')
        }
        sb.append(';')
    }

    private fun vec(sb: StringBuilder, v: Vec2) {
        sb.append(q(v.x)).append(',').append(q(v.y)).append(' ')
    }

    private fun q(f: Float): Long = Math.round(f * 1000.0)

    /** The module's `src/main/java/com/brushwork/paint`, searched upwards from the working directory; null when absent. */
    private fun mainSources(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null).absoluteFile
        while (dir != null) {
            for (c in listOf(File(dir, "src/main/java/com/brushwork/paint"), File(dir, "app/src/main/java/com/brushwork/paint"))) {
                if (c.isDirectory) return c
            }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        val EXPECTED: Map<String, String> = linkedMapOf(
            "LINE" to "e1b56c228d89cbb9af5261c9d39a20ba",
            "RECTANGLE" to "a9303896d99cf4acf757e653931682fa",
            "ELLIPSE" to "298a21252a4744ec233bc8d5ac6467a3",
            "POLYGON" to "e01b798429624487c0b6481750797cdc",
            "STAR" to "5214b1603e6e4678e698e661c44909fe",
            "ARROW" to "7c66a923b669adef8a992b246b383688",
            "COG" to "539d4b39c5c7538e4ec3a144165ea25f",
        )
    }
}
