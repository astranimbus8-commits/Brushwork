package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F (design §3.8): a cog without a hole turns into points without a visible change: its
 * teeth become sharp points (one per outline vertex) and the outline stays within 0.01 px, with
 * sharp and with rounded corners; it is one in-tool step, and the committed cog keeps its points.
 */
@RunWith(RobolectricTestRunner::class)
class CogEditPointsTest {
    private val rig = CogRig()

    @After
    fun close() = rig.close()

    private fun vertices(p: VectorPath): List<Vec2> = p.flatten(0.05f).single().points.let { pts ->
        if (pts.size > 1 && pts.last().distanceTo(pts.first()) < 1e-3f) pts.dropLast(1) else pts
    }

    private fun assertSameOutline(what: String, expected: VectorPath, actual: VectorPath) {
        val e = vertices(expected)
        val a = vertices(actual)
        assertEquals("$what: vertices", e.size, a.size)
        for (i in e.indices) assertTrue("$what: vertex $i ${a[i]} vs ${e[i]}", a[i].distanceTo(e[i]) <= 0.01f)
    }

    @Test
    fun aCogWithoutAHoleBecomesItsTeethAsSharpPoints() {
        for (corner in listOf(CornerStyle.SHARP, CornerStyle.ROUND, CornerStyle.BEVEL)) {
            val c = rig.controller()
            val cog = CogParams(teeth = 9, depth = 0.3f, toothWidth = 0.45f, taper = 0.4f)
            val tool = rig.cogTool(c, cog) { it.copy(corner = corner, cornerRadius = 3f) }
            rig.drawCog(c)
            val before = tool.pendingObject()!!
            assertNull(before.points)
            val outline = ShapeOutlines.outline(before)
            assertFalse(tool.canUndoStep)

            tool.setPointEditing(true)
            assertTrue("$corner: points mode", tool.pointsMode)
            val pts = tool.points!!
            assertEquals(CogGeometry.vertexCount(cog), pts.size)
            assertTrue("every point is sharp", pts.all { !it.smooth && it.handleIn == null && it.handleOut == null && it.radius == null })
            val after = tool.pendingObject()!!
            assertEquals(ShapeType.COG, after.type)
            assertTrue(ShapeOutlines.isCustom(after))
            assertEquals(before.box, after.box)
            assertSameOutline("$corner", outline, ShapeOutlines.outline(after))
            // The teeth as sharp points for the brush too (one contour).
            assertEquals(1, ShapeOutlines.brushContours(after).size)

            // One in-tool step: undo gives the cog drawn from its fields back.
            assertTrue(tool.canUndoStep)
            assertTrue(tool.undoStep())
            assertNull(tool.points)
            assertFalse(tool.pointsMode)
            assertEquals(outline.ops, ShapeOutlines.outline(tool.pendingObject()!!).ops)
            tool.discard()
        }
    }

    @Test
    fun theCommittedCogKeepsItsPoints() {
        val c = rig.controller()
        val tool = rig.cogTool(c, CogParams(teeth = 14, depth = 0.15f))
        rig.drawCog(c)
        tool.setPointEditing(true)
        // A tooth tip moved outwards: the cog has a shape of its own now.
        val pts = tool.points!!
        tool.movePoint(1, tool.docAnchors()!![1].pos + Vec2(0f, -12f))
        val edited = tool.pendingObject()!!
        assertTrue(edited.points != pts)
        tool.commit()
        val layer = c.doc.activeLayer
        assertTrue(layer.isShapeLayer)
        val stored = ShapeCodec.decode(layer.shapeData)!!
        assertEquals(ShapeType.COG, stored.type)
        assertNotNull(stored.points)
        assertEquals(edited.points, stored.points)
        assertFalse("custom points are not a fallback", stored.cogFallback)
        assertFalse(layer.shapeData!!.contains("cogFallback\":true"))
        // Opened again: the same points, in points mode on request.
        assertTrue(tool.editLayer(layer))
        assertEquals(edited.points, tool.points)
        tool.setPointEditing(true)
        assertTrue(tool.pointsMode)
        tool.discard()
    }
}
