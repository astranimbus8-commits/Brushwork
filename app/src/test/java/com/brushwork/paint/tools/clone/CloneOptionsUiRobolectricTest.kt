package com.brushwork.paint.tools.clone

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.clone.CloneToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The clone stamp's options strip on a 392dp-wide phone: the hint without a source (it arms
 * Set source), Set source, Aligned, Sample and Show source drive the tool and the settings, and
 * the brush part is the brush tool's.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h860dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.tools.clone.uisandbox"])
class CloneOptionsUiRobolectricTest {

    @Test
    fun theStripDrivesTheTool() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.selectTool(ToolId.CLONE)
        val tool = c.tools.getValue(ToolId.CLONE) as CloneTool
        activity.setContent {
            BrushworkTheme {
                Row(Modifier.horizontalScroll(rememberScrollState())) { CloneToolOptions(tool) }
            }
        }
        Smoke.pump(100)
        SmokeUi.settle()
        assertTrue("hint without a source", SmokeUi.has(CloneTool.HINT, exact = true))
        for (label in listOf("Set source", "Aligned", "Sample: This layer", "Show source")) {
            assertTrue("\"$label\" in the strip", SmokeUi.has(label, exact = true))
        }
        assertTrue("the brush part of the strip", SmokeUi.has(c.cloneBrush.name, exact = true))

        // The hint arms Set source; tapping the chip again disarms it.
        SmokeUi.click(CloneTool.HINT, exact = true)
        assertTrue(tool.armed)
        assertTrue(SmokeUi.has("Tap the source", exact = true))
        assertFalse(SmokeUi.has(CloneTool.HINT, exact = true))
        SmokeUi.click("Tap the source", exact = true)
        assertFalse(tool.armed)
        SmokeUi.click("Set source", exact = true)
        assertTrue(tool.armed)
        SmokeUi.click("Tap the source", exact = true)

        // Toggles: the tool and the stored settings follow.
        assertTrue(tool.aligned)
        SmokeUi.click("Aligned", exact = true)
        assertFalse(tool.aligned)
        assertFalse(c.settings.cloneAligned)
        SmokeUi.click("Aligned", exact = true)
        assertTrue(c.settings.cloneAligned)
        SmokeUi.click("Show source", exact = true)
        assertFalse(tool.showSource)
        assertFalse(c.settings.cloneShowSource)

        // Sample: a menu with This layer / All layers.
        SmokeUi.click("Sample: This layer", exact = true)
        SmokeUi.settle(20, 50)
        SmokeUi.click("All layers", exact = true)
        SmokeUi.settle(20, 50)
        assertTrue(tool.sampleAllLayers)
        assertTrue(c.settings.cloneSampleAllLayers)
        assertTrue(SmokeUi.has("Sample: All layers", exact = true))

        // With a source the hint is gone.
        tool.setSource(Vec2(50f, 50f))
        SmokeUi.settle()
        assertFalse(SmokeUi.has(CloneTool.HINT, exact = true))
        assertTrue(SmokeUi.has("Set source", exact = true))
        Smoke.assertQuiet(c, "clone strip")
        c.dispose()
    }
}
