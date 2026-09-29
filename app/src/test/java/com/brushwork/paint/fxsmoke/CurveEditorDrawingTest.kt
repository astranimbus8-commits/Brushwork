package com.brushwork.paint.fxsmoke

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.adjust.AdjustMath
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.filters.CurveEditor
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The tone-curve editor draws exactly the curve the Tone Curve filter applies
 * ([AdjustMath.sampleCurve]), not another monotone interpolation: the editor is rendered through
 * the real window and the drawn stroke is located column by column.
 *
 * Own sandbox (see ColorPickerUiSmokeTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.fxsmoke.curvesandbox"])
class CurveEditorDrawingTest {

    @Test
    fun theDrawnCurveIsTheToneCurveFiltersCurve() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // A long first segment with a steep PCHIP end slope: the applied curve bows well above the
        // straight segment near x = 0.17.
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.9f), CurvePoint(0.6f, 0.95f), CurvePoint(1f, 1f))
        activity.setContent { BrushworkTheme { Box(Modifier.fillMaxSize().background(Color.Black)) { CurveEditor(points, onChange = {}) } } }
        RobolectricUi.settle(3, 50)
        val b = RobolectricUi.byDescription("Tone curve editor").bounds
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))

        val density = activity.resources.displayMetrics.density
        val pad = 12f * density
        val l = b.left + pad; val t = b.top + pad
        val w = b.width - 2 * pad; val h = b.height - 2 * pad
        val applied = AdjustMath.sampleCurve(points, 1001)
        // Straight segments between the points: what a naive editor would draw.
        val other = { x: Float ->
            val i = points.indexOfLast { it.x <= x }.coerceIn(0, points.size - 2)
            val a = points[i]; val b = points[i + 1]
            a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
        }
        var checked = 0
        var discriminating = 0
        for (i in 1..39) {
            val x = i / 40f
            // Skip columns under a control-point handle.
            if (points.any { abs(it.x - x) * w < 10f * density }) continue
            // Where the curve is steep a column crosses a long stretch of the stroke: allow for it.
            val k = (x * 1000).roundToInt()
            val slope = abs(applied[minOf(1000, k + 5)] - applied[maxOf(0, k - 5)]) / 0.01f * h / w
            val tolerancePx = 2.5f + 1.5f * slope
            val col = (l + x * w).roundToInt()
            var sum = 0.0; var weight = 0.0
            for (y in (t - 4).toInt()..(t + h + 4).toInt()) {
                val p = bmp.getPixel(col, y)
                val r = (p shr 16) and 0xFF; val bl = p and 0xFF
                val blue = bl - r // the accent stroke is the only strongly blue thing
                if (blue > 40) { sum += y * blue.toDouble(); weight += blue.toDouble() }
            }
            assertTrue("no curve stroke found in column x=$x", weight > 0)
            val drawn = ((t + h) - (sum / weight).toFloat()) / h
            val expected = applied[k]
            assertTrue("x=$x: drawn ${"%.3f".format(drawn)} vs applied ${"%.3f".format(expected)} (tolerance $tolerancePx px)", abs(drawn - expected) * h < tolerancePx)
            if (abs(other(x) - expected) * h > 2 * tolerancePx) discriminating++
            checked++
        }
        assertTrue("too few columns checked: $checked", checked >= 25)
        assertTrue("the points should make the two interpolations differ somewhere", discriminating > 0)
    }
}
