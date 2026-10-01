package com.brushwork.paint.ui.vector

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5 A3: the eraser's options strip on a vector layer shows the three vector eraser modes on a
 * 392 dp phone; tapping one selects and remembers it; on a raster layer they are not shown.
 * Own sandbox (like the other options UI tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h860dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.erasermodessandbox"])
class VectorEraserOptionsUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(ctx).also { it.prefs.edit().clear().commit() }
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(ctx, doc, scope, settings).also { it.tools }
    }

    @Test
    fun theModesAreChipsOfTheEraserStripOnVectorLayers() {
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = controller()
        c.selectTool(ToolId.ERASER)
        activity.setContent { BrushworkTheme { Surface { ToolOptionsBar(c, Modifier.fillMaxWidth()) } } }
        RobolectricUi.settle(10, 50)
        for (m in VectorEraseMode.entries) assertTrue("${m.label} shown", SmokeUi.has(m.label, exact = true))
        assertEquals(VectorEraseMode.OBJECT, VectorEraserModes.mode(c))
        SmokeUi.click(VectorEraseMode.PARTIAL.label, exact = true)
        assertEquals(VectorEraseMode.PARTIAL, VectorEraserModes.mode(c))
        assertEquals("PARTIAL", c.settings.vectorEraserMode)
        SmokeUi.click(VectorEraseMode.TO_INTERSECTION.label, exact = true)
        assertEquals(VectorEraseMode.TO_INTERSECTION, VectorEraserModes.mode(c))
        // A raster layer: the normal eraser strip only.
        c.selectLayer(c.doc.layers[0])
        RobolectricUi.settle(10, 50)
        assertFalse(SmokeUi.has(VectorEraseMode.PARTIAL.label, exact = true))
        // The brush on a vector layer has no eraser modes either.
        c.selectLayer(c.doc.layers[1])
        c.selectTool(ToolId.BRUSH)
        RobolectricUi.settle(10, 50)
        assertFalse(SmokeUi.has(VectorEraseMode.TO_INTERSECTION.label, exact = true))
    }
}
