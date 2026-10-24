package com.brushwork.paint.v110.a2

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.PillColorCell
import com.brushwork.paint.ui.vector.FRAME_DARK
import com.brushwork.paint.ui.vector.FRAME_LIGHT
import com.brushwork.paint.ui.vector.SLASH
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas

/**
 * v1.10 (R2 (a), finding 36; design 4.4 A2 step 6): the pill's Object colors cell stays visible on
 * the dark pill whatever the object's colors. Drawn on the pill's colors (Robolectric NATIVE
 * graphics, software layers), its face is framed by a 1 dp light outer ring ([FRAME_LIGHT]) and a
 * 1 dp dark inner ring ([FRAME_DARK]):
 * - a black and a dark-navy object: the disc is the fill, and the light ring stands out from both
 *   the disc and the pill's cell around it on every side;
 * - a white object: the dark ring parts it from the light ring;
 * - the stroke is the 3 dp ring around the fill disc;
 * - no fill (paint mode Stroke): the checker with the [SLASH].
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.swatchsandbox"])
class PillColorSwatchOnDarkTest {

    /** One look of the object: its fill, its stroke and its paint mode. */
    private data class Look(val name: String, val fill: Int, val stroke: Int, val mode: PaintMode)

    @Test
    fun blackAndDarkNavyStayVisibleOnThePill() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val looks = listOf(
            Look("black", BLACK, BLACK, PaintMode.BOTH),
            Look("dark navy", NAVY, NAVY, PaintMode.BOTH),
            Look("dark navy, fill only", NAVY, BLUE, PaintMode.FILL),
            Look("white", WHITE, WHITE, PaintMode.BOTH),
            Look("black fill, blue stroke", BLACK, BLUE, PaintMode.BOTH),
            Look("no fill", NAVY, BLACK, PaintMode.STROKE),
        )
        var shown by mutableIntStateOf(0)
        activity.setContent {
            BrushworkTheme {
                // The pill (translucent black) over a dark canvas, the cell in it as in the pill's rows.
                Box(Modifier.background(CANVAS).padding(16.dp).background(IbisColors.Pill).padding(8.dp)) {
                    val l = looks[shown]
                    key(shown) { PillColorCell(A2FakeColorEditor(l.fill, l.stroke, l.mode)) }
                }
            }
        }
        SmokeUi.settle()
        val px = { d: Float -> d * density }
        for ((i, l) in looks.withIndex()) {
            shown = i
            val img = capture(activity)
            val cell = requireNotNull(
                RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == V110Tags.PILL_COLOR },
            ) { "${l.name}: no cell" }.bounds
            val origin = IntArray(2).also { activity.findViewById<View>(android.R.id.content).getLocationInWindow(it) }
            val cx = cell.center.x - origin[0]
            val cy = cell.center.y - origin[1]
            /** The pixel [d] dp from the centre along the direction (dx, dy). */
            fun at(d: Float, dx: Int, dy: Int): Int = img.pixel((cx + dx * px(d)).roundToInt(), (cy + dy * px(d)).roundToInt())
            val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
            val bg = IbisColors.PillCell.toArgb()

            // The two frame rings, on every side: light in the outermost dp, dark in the next.
            for ((dx, dy) in dirs) {
                val outer = (0..2).map { at(R - 0.25f - it * 0.25f, dx, dy) }.maxBy { lum(it) }
                val inner = (0..2).map { at(R - 1.25f - it * 0.25f, dx, dy) }.minBy { lum(it) }
                assertNear("${l.name} ($dx, $dy): the light outer ring", FRAME_LIGHT.toArgb(), outer)
                assertNear("${l.name} ($dx, $dy): the dark inner ring", FRAME_DARK.toArgb(), inner)
                // Between the face and the cell's 1 dp bevel (its edge is 14 dp from the centre).
                assertEquals("${l.name} ($dx, $dy): the pill's cell around it", hex(bg), hex(at(R + 0.75f, dx, dy)))
                assertTrue("${l.name}: the light ring stands out from the cell", lum(outer) - lum(bg) >= 100)
            }
            val centre = img.pixel(cx.roundToInt(), cy.roundToInt())
            when (l.mode) {
                PaintMode.STROKE -> {
                    assertEquals("${l.name}: the slash crosses the disc", hex(SLASH.toArgb()), hex(centre))
                    // Across the slash: the checker (white or light grey), not the fill.
                    val checker = at(3f, 1, 1)
                    assertTrue("${l.name}: the checker shows (${hex(checker)})", lum(checker) >= 180 && checker != l.fill)
                }
                else -> {
                    assertEquals("${l.name}: the disc is the fill", hex(l.fill), hex(centre))
                    // A dark object: the light ring stands out from it too.
                    if (lum(l.fill) < 60) {
                        for ((dx, dy) in dirs) assertTrue("${l.name}: the light ring stands out from the object", lum(at(R - 0.5f, dx, dy)) - lum(l.fill) >= 150)
                    }
                    // The stroke ring (none in Fill: the checker shows there).
                    val ring = at(R - 2f - 1.5f, 1, 0)
                    if (l.mode == PaintMode.FILL) {
                        assertTrue("${l.name}: no stroke ring (${hex(ring)})", lum(ring) >= 180)
                    } else {
                        assertEquals("${l.name}: the stroke ring", hex(l.stroke), hex(ring))
                    }
                }
            }
        }
    }

    /** The activity's content drawn into a bitmap through software layers (as `PaintModeGlyphParityTest`). */
    private fun capture(activity: ComponentActivity): Bitmap {
        SmokeUi.settle()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        allViews(root).forEach { it.setLayerType(View.LAYER_TYPE_SOFTWARE, null); it.invalidate() }
        SmokeUi.settle()
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(AndroidCanvas(b))
        return b
    }

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } + v else listOf(v)

    private fun Bitmap.pixel(x: Int, y: Int): Int = getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))

    /** Luminance 0-255 (Rec. 601), alpha ignored. */
    private fun lum(c: Int): Int = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** [actual] within 24 of [expected] in each channel (the rings are 1 dp: anti-aliased at their edges). */
    private fun assertNear(what: String, expected: Int, actual: Int) {
        val d = listOf(16, 8, 0).maxOf { s -> kotlin.math.abs((expected shr s and 0xFF) - (actual shr s and 0xFF)) }
        assertTrue("$what: ${hex(actual)}, not near ${hex(expected)}", d <= 24)
    }

    private companion object {
        /** The cell face's radius (dp): half of its 24 dp. */
        const val R = 12f
        const val NAVY = 0xFF001F3F.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** A dark canvas under the translucent pill. */
        val CANVAS = androidx.compose.ui.graphics.Color(0xFF101010)
    }
}
