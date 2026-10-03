package com.brushwork.paint.qa16

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.theme.IbisColors
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders of the ibisPaint states on the user's phone (392 × 873 dp), one per class (one sandbox
 * each, see [IbisShots]): the tool menu, the layer window over four layers of different kinds,
 * Transform with the X / Y pill, the More menu, a panel, and the 360 dp and landscape phones.
 * Each also samples the colours the design measured on the reference.
 */
internal object IbisDocs {
    /** A portrait page like the reference's: a white bottom layer and a red blob on layer 2. */
    fun page(): Document = Smoke.document(300, 430, layers = 2, whiteBottom = true).also { d ->
        Canvas(d.layers[1].bitmap).drawCircle(150f, 200f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE02020.toInt() })
    }

    /** Above the page's two layers: a text layer, a vector layer and a masked Tone adjustment layer (layers 3–5). */
    fun fourKinds(c: EditorController) {
        c.selectLayer(c.doc.layers.last())
        val item = TextItem("Hello", spec = TextSpec(sizePx = 64f), cx = 150f, cy = 90f)
        c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { canvas ->
            TextRenderer.drawItem(canvas, item, TextRenderer.prepare(item), null)
        }!!
        c.selectLayer(c.doc.layers.last())
        c.addVectorLayer()!!
        c.selectLayer(c.doc.layers.last())
        c.addAdjustmentLayer(
            AdjustmentEffects.defaultSpec(),
            MaskSpec(components = listOf(LinearMask(1, x0 = 40f, y0 = 0f, x1 = 260f, y1 = 0f)), nextId = 2),
        )!!
        c.notifyLayersChanged()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shottoolmenusandbox"])
class IbisShotToolMenuTest {
    @Test
    fun toolMenuLooksLikeIbisPaint() = IbisShots.shoot(
        "tool-menu", "ibis-tool-menu.png", IbisDocs.page(),
        drive = { click("Tools (current: Brush)") },
        check = { s, _ -> assertNotNull(s.tagged(ChromeTags.TOOL_MENU)) },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shotlayerwindowsandbox"])
class IbisShotLayerWindowTest {
    @Test
    fun layerWindowLooksLikeIbisPaint() = IbisShots.shoot(
        "layer-window", "ibis-layer-window.png", IbisDocs.page(), setup = IbisDocs::fourKinds,
        drive = { s ->
            assertTrue("5 layers", s.c.doc.layers.size == 5)
            click("Open layers (active layer 5)")
            Smoke.pump(1_200)
            settle()
        },
        check = { s, shot ->
            val lw = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
            // The header's panel left of the page: black α 0.5 over the surround (#5F5F5F).
            val header = IbisShots.at(shot, s.density, lw.left + 5f, lw.top + 20f)
            assertTrue("header ${IbisShots.hex(header)}", IbisShots.close(0xFF5F5F5F.toInt(), header, 10))
        },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shottransformsandbox"])
class IbisShotTransformPillTest {
    @Test
    fun transformPillLooksLikeIbisPaint() = IbisShots.shoot(
        "xy-pill", "ibis-xy-pill.png", IbisDocs.page(),
        drive = { s ->
            s.c.selectLayer(1)
            settle()
            click("Tools (current: Brush)")
            click("Transform", exact = true)
            assertTrue(Smoke.pumpUntil { settle(1); (s.c.currentTool as TransformTool).transformState != null })
            settle()
        },
        check = { s, _ -> assertTrue("the X cell", SmokeUi.has("X slider")) },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shotmoresandbox"])
class IbisShotMoreMenuTest {
    @Test
    fun moreMenuRenders() = IbisShots.shoot(
        "more-menu", null, IbisDocs.page(),
        drive = { click("More options"); Smoke.pump(300); settle() },
        check = { _, _ -> assertTrue(SmokeUi.windows().size >= 2) },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shotpanelsandbox"])
class IbisShotPanelTest {
    @Test
    fun brushPanelRenders() = IbisShots.shoot(
        "panel-brush", null, IbisDocs.page(),
        drive = { click("Open brush settings") },
        check = { _, _ -> SmokeUi.assertPanelShown() },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shotnarrowsandbox"])
class IbisShotNarrowTest {
    @Test
    fun narrowPhoneRenders() = IbisShots.shoot(
        "narrow-360", "ibis-main-screen.png", IbisDocs.page(), setup = IbisDocs::fourKinds,
        drive = { click("Open layers (active layer 5)"); Smoke.pump(1_200); settle() },
        check = { s, shot ->
            val bar = IbisShots.at(shot, s.density, 56f * 2 + 4f, s.heightDp - 3f)
            assertTrue("bottom bar ${IbisShots.hex(bar)}", IbisShots.close(IbisColors.BottomBar.toArgb(), bar))
        },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w873dp-h392dp-land-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.shotlandsandbox"])
class IbisShotLandscapeTest {
    @Test
    fun landscapeRenders() = IbisShots.shoot(
        "landscape", null, IbisDocs.page(), setup = IbisDocs::fourKinds,
        drive = { click("Open layers (active layer 5)"); Smoke.pump(1_200); settle() },
    )
}
