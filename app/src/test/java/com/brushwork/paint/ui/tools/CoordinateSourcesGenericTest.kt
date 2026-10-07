package com.brushwork.paint.ui.tools

import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.PillLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 F5 (design §4.6): `coordinateSourceOf` routes a [com.brushwork.paint.tools.PillPositionTool]
 * generically, FIRST, whatever the tool's class: the strip edits the tool's own `pillPosition` in
 * its `pillUnit`, so setting X moves the fake's selected group. The pill's Scale row and trash
 * cell reach a tool only through [ScaledTool] / [DeletingTool]; [FakePillTool] (the fixture area
 * I's tests use) behaves as §4.6 says a point editor does.
 */
@RunWith(RobolectricTestRunner::class)
class CoordinateSourcesGenericTest {
    private val c = Smoke.controller(RuntimeEnvironment.getApplication())

    @Test
    fun aPillPositionToolIsRoutedByInterfaceToItsOwnStablePosition() {
        // A CURVE id (whose v1.6 adapter is CurvePointPosition) and a tool class the strip has never seen.
        val fake = FakePillTool(c, id = ToolId.CURVE, pillUnit = LengthUnit.MM)
        val source = requireNotNull(coordinateSourceOf(fake)) { "a PillPositionTool has a source" }
        assertSame("the tool's own pill position, not a v1.6 adapter", fake.pillPosition, source.target)
        assertEquals(LengthUnit.MM, source.unit())
        // The same object on every call (the tool's lifetime), whatever the selection.
        fake.select(2)
        assertSame(fake.pillPosition, coordinateSourceOf(fake)!!.target)
    }

    @Test
    fun settingXMovesTheFakesSelectedGroup() {
        val fake = FakePillTool(c)
        val target = coordinateSourceOf(fake)!!.target
        // Nothing selected: the object's centre.
        assertEquals("Center", target.label)
        assertEquals(Vec2(30f, 20f), target.position)
        // One point: that point.
        fake.select(1)
        assertEquals("Point 2", target.label)
        assertEquals(Vec2(50f, 10f), target.position)
        // Two points: their box centre; setting X moves the group, Y unchanged.
        fake.select(1, 2)
        assertEquals("Selected points", target.label)
        assertEquals(Vec2(50f, 20f), target.position)
        target.beginPositionEdit()
        target.setPosition(80f, null)
        target.endPositionEdit()
        assertEquals(Vec2(80f, 20f), target.position)
        assertEquals(listOf(Vec2(10f, 10f), Vec2(80f, 10f), Vec2(80f, 30f), Vec2(10f, 30f)), fake.points)
        assertEquals(1, fake.setPositionCalls)
        assertEquals(1, fake.beginPositionCalls)
        assertEquals(1, fake.endPositionCalls)
        assertEquals("one in-tool step", 1, fake.steps)
        // No object open: the pill hides.
        fake.open = false
        assertNull(target.position)
    }

    @Test
    fun scaleRowAndTrashCellAreReachedThroughTheirInterfacesOnly() {
        val fake = FakePillTool(c)
        val tool: Tool = fake
        val scale = requireNotNull((tool as? ScaledTool)?.objectScale) { "row 2 through ScaledTool" }
        assertEquals(Vec2(100f, 100f), scale.scalePercent)
        scale.beginScaleEdit()
        scale.setScale(200f, null)
        scale.endScaleEdit()
        assertEquals(Vec2(200f, 100f), scale.scalePercent)
        // About the box centre (30, 20): x 10..50 becomes -10..70.
        assertEquals(Vec2(-10f, 10f), fake.points[0])
        assertEquals(Vec2(70f, 30f), fake.points[2])
        assertEquals(1, fake.setScaleCalls)

        val deletion = requireNotNull((tool as? DeletingTool)?.objectDeletion) { "the trash cell through DeletingTool" }
        fake.select(0)
        assertEquals(PillLabels.DELETE_POINTS, deletion.deleteLabel)
        // Every point selected deletes the object (§4.6: "otherwise, ALSO with every point selected").
        fake.select(0, 1, 2, 3)
        assertEquals("Delete curve", deletion.deleteLabel)
        assertEquals(PillLabels.deleteObject("curve"), deletion.deleteLabel)
        deletion.delete()
        assertEquals(1, fake.deleteCalls)
        assertNull("no object open: the trash cell hides", fake.objectDeletion)
        assertNull("and so does row 2", fake.objectScale)
        assertNull(fake.pillPosition.position)
    }

    @Test
    fun rollbackHistoryDropsTheStepsAfterTheMark() {
        val fake = FakePillTool(c)
        fake.select(0)
        val before = fake.points
        val mark = fake.historyMark()
        fake.pillPosition.setPosition(0f, 0f)
        fake.objectDeletion!!.delete()
        assertEquals(2, fake.steps)
        fake.rollbackHistory(mark)
        assertEquals(0, fake.steps)
        assertEquals(before, fake.points)
        assertEquals(setOf(0), fake.selected)
    }
}
