package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.PaintModeGlyph
import com.brushwork.paint.ui.theme.BrushworkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 F0 (R3; lead, frozen): [PaintModeGlyph] draws exactly what v1.9's Shape `StyleGlyph`
 * drew, pixel for pixel (Robolectric NATIVE graphics), for the three modes and opaque, translucent
 * and white fills. The v1.9 drawing is kept here ([V19StyleGlyph], a verbatim copy of
 * `ui/vector/VectorWidgets.kt` StyleGlyph at v1.9), so the check holds after A2 moves its callers to
 * the new glyph. Both are drawn at the same place of one activity, one after the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.lead.glyphsandbox"])
class PaintModeGlyphParityTest {

    @Before
    fun restartCompose() = SmokeUi.restartUiDispatcher()

    /** v1.9's StyleGlyph (VectorWidgets.kt, ce5b7550), with ShapeStyle's two flags as parameters. */
    @Composable
    private fun V19StyleGlyph(fillOn: Boolean, strokeOn: Boolean, fillColor: Int, modifier: Modifier = Modifier) {
        Canvas(modifier.size(22.dp)) {
            val inset = 3.dp.toPx()
            val r = CornerRadius(4.dp.toPx())
            val topLeft = Offset(inset, inset)
            val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
            if (fillOn) {
                drawRoundRect(Color(fillColor), topLeft, sz, r)
                if (!strokeOn) drawRoundRect(BrushworkColors.OnChromeDim, topLeft, sz, r, style = Stroke(1.dp.toPx()))
            }
            if (strokeOn) drawRoundRect(BrushworkColors.OnChrome, topLeft, sz, r, style = Stroke(2.5.dp.toPx()))
        }
    }

    @Test
    fun paintModeGlyphIsPixelEqualToV19StyleGlyph() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var show by mutableStateOf(false)
        var useNew by mutableStateOf(false)
        var mode by mutableStateOf(PaintMode.STROKE)
        var fill by mutableIntStateOf(0xFF3366CC.toInt())
        var newTint by mutableStateOf<Int?>(null)
        activity.setContent {
            Box(Modifier.size(40.dp).background(Color(0xFF202124)), contentAlignment = Alignment.Center) {
                if (show) {
                    if (useNew) PaintModeGlyph(mode, Color(newTint ?: fill))
                    else V19StyleGlyph(fillOn = mode != PaintMode.STROKE, strokeOn = mode != PaintMode.FILL, fillColor = fill)
                }
            }
        }
        RobolectricUi.settle()
        val background = capture(activity)
        show = true
        var compared = 0
        for (m in PaintMode.entries) for (f in intArrayOf(0xFF3366CC.toInt(), 0x80FF0000.toInt(), 0xFFFFFFFF.toInt())) {
            mode = m
            fill = f
            useNew = false
            val old = capture(activity)
            useNew = true
            val new = capture(activity)
            assertTrue("$m: the v1.9 glyph drew something", !old.contentEquals(background))
            val diff = old.indices.count { old[it] != new[it] }
            assertEquals("$m fill %08X: pixels that differ from v1.9's StyleGlyph".format(f), 0, diff)
            compared++
        }
        assertEquals(9, compared)
        // Control: the capture follows a switch to the new glyph (a stale capture would hide a
        // difference): only the new glyph gets another tint here.
        useNew = false
        mode = PaintMode.FILL
        fill = 0xFF3366CC.toInt()
        val oldBlue = capture(activity)
        useNew = true
        newTint = 0xFFCC6633.toInt()
        assertTrue("the new glyph is drawn after the switch", !capture(activity).contentEquals(oldBlue))
    }

    /** The activity's content drawn into a bitmap through software layers, as ARGB pixels. */
    private fun capture(activity: ComponentActivity): IntArray {
        RobolectricUi.settle(steps = 6)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val views = allViews(root)
        views.forEach { it.setLayerType(View.LAYER_TYPE_SOFTWARE, null); it.invalidate() }
        RobolectricUi.settle(steps = 4)
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(AndroidCanvas(b))
        val out = IntArray(b.width * b.height)
        b.getPixels(out, 0, b.width, 0, 0, b.width, b.height)
        return out
    }

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } + v else listOf(v)
}
