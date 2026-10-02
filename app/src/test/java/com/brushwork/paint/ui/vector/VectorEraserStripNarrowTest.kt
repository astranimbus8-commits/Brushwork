package com.brushwork.paint.ui.vector

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5 integration (lead): on a vector layer the eraser's strip starts with its three modes
 * (Object / Partial / To intersection), right after the VECTOR chip, so all three are on the
 * strip's first screen of a 360 dp phone (and so of the user's 392 dp one) without scrolling.
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.erasernarrowsandbox"])
class VectorEraserStripNarrowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun theEraserModesAreOnTheFirstScreenOfA360dpStrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(ctx).also { it.prefs.edit().clear().commit() }
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        val c = EditorController(ctx, doc, scope, settings).also { it.tools }
        c.selectTool(ToolId.ERASER)

        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BrushworkTheme { Surface { ToolOptionsBar(c) } } }
        RobolectricUi.settle(10, 50)
        val width = activity.window.decorView.width
        assertTrue(width > 0)
        var lastRight = 0f
        for (m in VectorEraseMode.entries) {
            val e = SmokeUi.find(m.label, exact = true) ?: throw AssertionError("\"${m.label}\" is not shown; shown: ${SmokeUi.shown()}")
            // The whole chip (the clickable node around the label), unclipped: bounds in the
            // window are cut at its edge, which would hide a chip running off the screen.
            var chip: SemanticsNode = e.node
            while (chip.config.getOrNull(SemanticsActions.OnClick) == null) chip = chip.parent ?: break
            val left = chip.positionInWindow.x
            val right = left + chip.size.width
            assertTrue("the \"${m.label}\" chip ($left..$right) fits the ${width}px wide strip", left >= 0f && right <= width)
            assertTrue("\"${m.label}\" comes after the previous mode", left >= lastRight)
            lastRight = right
        }
        // Only the VECTOR chip comes before them.
        val vectorChip = SmokeUi.find("Vector mode is on", exact = true)
        if (vectorChip != null) assertTrue(vectorChip.bounds.right <= SmokeUi.find(VectorEraseMode.OBJECT.label, exact = true)!!.bounds.left)
        // They still work from there.
        SmokeUi.click(VectorEraseMode.TO_INTERSECTION.label, exact = true)
        assertEquals(VectorEraseMode.TO_INTERSECTION, VectorEraserModes.mode(c))
    }
}
