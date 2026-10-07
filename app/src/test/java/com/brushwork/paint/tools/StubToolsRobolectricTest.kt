package com.brushwork.paint.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.symmetry.SymmetryTool
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 F5 (design §4.6, §4.9): the three tools registered before their areas fill them (Array,
 * Symmetry, Pathfinder) are registered as their own classes, and each opens, takes a gesture,
 * draws its overlays and closes on every layer kind (raster, text, shape, vector, adjustment,
 * folder) without an exception, with its option strip composed. This holds for the stubs and
 * for the areas' real tools alike: what only the stubs do (no step, no change) is pinned by the
 * owners' stub tests (see [StubToolFixtures]), which the owners retire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.brushwork.paint.tools.stubtoolssandbox"])
class StubToolsRobolectricTest {
    private val stubs = listOf(ToolId.ARRAY, ToolId.SYMMETRY, ToolId.PATHFINDER)

    @Test
    fun theStubToolsOpenAndCloseOnEveryLayerKind() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity)
        val kinds = StubToolFixtures.everyKind(c)

        // Registered as their own classes.
        assertTrue(c.tools.getValue(ToolId.ARRAY) is ArrayTool)
        assertTrue(c.tools.getValue(ToolId.SYMMETRY) is SymmetryTool)
        assertTrue(c.tools.getValue(ToolId.PATHFINDER) is PathfinderTool)
        for (id in stubs) assertEquals(id, c.tools.getValue(id).id)

        var shown by mutableIntStateOf(0)
        activity.setContent { BrushworkTheme { shown; ToolOptionsBar(c) } }
        SmokeUi.settle()
        val overlay = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
        for ((kind, layer) in kinds) {
            for (id in stubs) {
                val where = "$id on the $kind layer"
                c.selectLayer(layer)
                c.selectTool(id)
                shown++
                SmokeUi.settle()
                assertEquals(where, id, c.activeToolId)
                c.pointerDown(ToolPoint(60f, 60f))
                c.pointerMove(ToolPoint(90f, 80f))
                c.pointerUp(ToolPoint(90f, 80f))
                c.drawOverlays(Canvas(overlay), 0f)
                SmokeUi.settle()
                assertNull("$where: the tree stays valid", LayerTree.check(c.doc.layers))
                Smoke.assertQuiet(c, where)
                c.selectTool(ToolId.BRUSH)
                shown++
                SmokeUi.settle()
                Smoke.assertQuiet(c, "$where, put away")
            }
        }
        assertTrue(Smoke.errorLogs().isEmpty())
    }
}
