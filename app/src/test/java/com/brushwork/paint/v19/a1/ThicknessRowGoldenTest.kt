package com.brushwork.paint.v19.a1

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.RepeatIconButton
import com.brushwork.paint.ui.common.stepOnLongPress
import com.brushwork.paint.ui.points.ThicknessRowDefaults
import com.brushwork.paint.ui.points.ThicknessRowFit
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.vector.ThicknessControl
import com.brushwork.paint.ui.vector.stepThickness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.9 A1 step 1 (R1 (d), the golden of the one-point control): v1.8's `ThicknessControl`, kept
 * here verbatim as [V18ThicknessControl], and v1.9's (now a `ThicknessRow`) are composed one above
 * the other in the strip's 260 dp cell, on the same tool and point, at 0 %, 100 % and 300 %. Their
 * semantics (texts, descriptions, roles, click labels, actions, progress, bounds within the cell)
 * and their pixels (real Skia) must be equal: the one point's control is unchanged.
 *
 * No committed image and no hash: the reference is rendered by the same run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v19.a1.thicknessgoldensandbox"])
class ThicknessRowGoldenTest {

    @Test
    fun theOnePointControlIsUnchanged() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.tools
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
        tool.select(1)
        var width by mutableFloatStateOf(1f)
        activity.setContent {
            BrushworkTheme {
                Column(Modifier.background(BACKGROUND)) {
                    Box(Modifier.width(CELL).testTag(OLD), contentAlignment = Alignment.Center) {
                        V18ThicknessControl(tool, 1, width) {}
                    }
                    Box(Modifier.width(CELL).testTag(NEW), contentAlignment = Alignment.Center) {
                        ThicknessControl(tool, 1, width) {}
                    }
                }
            }
        }
        settle()

        for (percent in listOf(100, 0, 300)) {
            tool.setWidth(1, percent / 100f)
            tool.endNumericEdit()
            width = tool.widthOf(1)
            settle()
            val old = cell(OLD)
            val new = cell(NEW)
            val oldSig = signature(old)
            val newSig = signature(new)
            assertTrue("$percent %: the old control is there: $oldSig", oldSig.any { "$percent %" in it })
            assertEquals("$percent %: the same semantics", oldSig.joinToString("\n"), newSig.joinToString("\n"))

            val shot = render(activity)
            val a = crop(shot, old)
            val b = crop(shot, new)
            assertEquals("$percent %: the same size", "${a.width}x${a.height}", "${b.width}x${b.height}")
            assertTrue("$percent %: the render shows the control", distinct(a) > 8)
            val diff = differing(a, b)
            assertEquals("$percent %: pixels that differ (first at ${firstDiff(a, b)})", 0, diff)
        }

        // At font scale 1 every value fits the 52 dp box: the text keeps v1.8's style.
        val base = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp))
        assertTrue(ThicknessRowFit.fit(base, 100f) { 80f } === base)
        assertEquals(13.5f, ThicknessRowFit.fit(base, 100f) { s -> if (s.fontSize.value > 13.5f) 120f else 90f }.fontSize.value, 0f)
        assertEquals(ThicknessRowDefaults.MIN_TEXT_SP, ThicknessRowFit.fit(base, 100f) { 200f }.fontSize.value, 0f)
    }

    private fun cell(tag: String): RobolectricUi.Element = RobolectricUi.elements().single { e ->
        e.node.config.getOrNull(SemanticsProperties.TestTag) == tag
    }

    /** One line per semantics node under [cell], in tree order, with bounds relative to the cell. */
    private fun signature(cell: RobolectricUi.Element): List<String> {
        val origin = cell.bounds
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode, depth: Int) {
            val b = n.boundsInWindow
            val parts = mutableListOf<String>()
            for ((key, value) in n.config) {
                // (Focus belongs to the window, not to the control: the first slider composed gets it.)
                if (key.name == "TestTag" || key.name == "Focused") continue
                val shown = when (value) {
                    is androidx.compose.ui.semantics.AccessibilityAction<*> -> "action(${value.label})"
                    is Function<*> -> "fn"
                    else -> value.toString()
                }
                parts += "${key.name}=$shown"
            }
            parts.sort()
            out += "  ".repeat(depth) + parts.joinToString(", ") +
                " @ ${(b.left - origin.left).roundToInt()},${(b.top - origin.top).roundToInt()}" +
                " ${b.width.roundToInt()}x${b.height.roundToInt()}"
            n.children.forEach { walk(it, depth + 1) }
        }
        cell.node.children.forEach { walk(it, 0) }
        return out
    }

    private fun render(activity: ComponentActivity): Bitmap {
        val v = activity.window.decorView
        val out = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(out))
        return out
    }

    private fun crop(shot: Bitmap, cell: RobolectricUi.Element): Bitmap {
        val b = cell.bounds
        // (The cell's top-left in the window: the decor view draws the window from its 0, 0.)
        return Bitmap.createBitmap(shot, b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
    }

    private fun distinct(a: Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) seen += a.getPixel(x, y)
        return seen.size
    }

    private fun differing(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    private fun firstDiff(a: Bitmap, b: Bitmap): String {
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) {
            return "$x,$y: ${Integer.toHexString(a.getPixel(x, y))} vs ${Integer.toHexString(b.getPixel(x, y))}"
        }
        return "none"
    }

    private companion object {
        const val OLD = "golden.v18"
        const val NEW = "golden.v19"
        val CELL = 260.dp
        val BACKGROUND = Color(0xFF26262B)
    }
}

// ---- v1.8.0's control, verbatim (CurveToolOptions.kt at v1.8.0), with its private helpers ----

private const val MAX_THICKNESS_PERCENT = 300f
private const val THICKNESS_STEP = 5f

private fun thicknessSliderSteps(step: Float): Int {
    val n = MAX_THICKNESS_PERCENT / step
    val r = n.roundToInt()
    return if (abs(n - r) < 1e-3f && r in 1..600) r - 1 else 0
}

@Composable
private fun V18ThicknessControl(tool: CurveTool, index: Int, width: Float, onType: () -> Unit) {
    val percent = width * 100f
    val step = tool.controller.increments.step(IncrementKind.PERCENT) ?: THICKNESS_STEP
    fun current(): Float = tool.widthOf(index) * 100f
    fun setPercent(p: Float) = tool.setWidth(index, p / 100f)
    val beforeTaps = remember(index) { floatArrayOf(Float.NaN) }
    val sliding = remember(index) { booleanArrayOf(false) }
    val latestFirstDown by rememberUpdatedState { beforeTaps[0] = current() }
    val latestReset by rememberUpdatedState {
        tool.thicknessRing = false
        if (current() != beforeTaps[0]) tool.undoStep()
        setPercent(100f)
        tool.endNumericEdit()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Thinner point", onRelease = { tool.endNumericEdit() }) {
            setPercent(stepThickness(current(), up = false, step))
        }
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 52.dp)
                .clip(RoundedCornerShape(8.dp))
                .stepOnLongPress(IncrementKind.PERCENT)
                .clickable(onClickLabel = "Type the point thickness", role = Role.Button) { onType() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${percent.roundToInt()} %",
                style = MaterialTheme.typography.bodyMedium,
                color = BrushworkColors.OnChrome,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrushworkColors.ChromeHigh)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .semantics { contentDescription = "Point thickness ${percent.roundToInt()} %" },
            )
        }
        RepeatIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Thicker point", onRelease = { tool.endNumericEdit() }) {
            setPercent(stepThickness(current(), up = true, step))
        }
        Slider(
            value = percent.coerceIn(0f, MAX_THICKNESS_PERCENT),
            onValueChange = { v ->
                tool.thicknessRing = true
                if (!sliding[0]) { sliding[0] = true; tool.beginNumericEdit() }
                setPercent(((v / step).roundToInt() * step).coerceIn(0f, MAX_THICKNESS_PERCENT))
            },
            onValueChangeFinished = {
                sliding[0] = false
                tool.thicknessRing = false
                tool.endNumericEdit()
            },
            valueRange = 0f..MAX_THICKNESS_PERCENT,
            steps = thicknessSliderSteps(step),
            colors = SliderDefaults.colors(thumbColor = BrushworkColors.Accent, activeTrackColor = BrushworkColors.Accent),
            modifier = Modifier
                .width(THICKNESS_SLIDER)
                .onDoubleTap(onFirstDown = { latestFirstDown() }) { latestReset() }
                .semantics { contentDescription = "Point thickness slider" },
        )
    }
}

private val THICKNESS_SLIDER = 128.dp

private fun Modifier.onDoubleTap(onFirstDown: () -> Unit = {}, action: () -> Unit): Modifier = pointerInput(Unit) {
    val timeout = viewConfiguration.doubleTapTimeoutMillis
    val slop = viewConfiguration.touchSlop
    var lastUp = Long.MIN_VALUE / 2
    var lastPos = Offset.Zero
    awaitPointerEventScope {
        var downPos = Offset.Zero
        var moved = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val ch = e.changes.firstOrNull() ?: continue
            when {
                ch.pressed && !ch.previousPressed -> {
                    downPos = ch.position
                    moved = false
                    if (ch.uptimeMillis - lastUp > timeout) onFirstDown()
                }
                ch.pressed -> if ((ch.position - downPos).getDistance() > slop) moved = true
                !ch.pressed && ch.previousPressed -> {
                    if (moved) {
                        lastUp = Long.MIN_VALUE / 2
                    } else if (ch.uptimeMillis - lastUp <= timeout && (ch.position - lastPos).getDistance() <= slop * 3) {
                        lastUp = Long.MIN_VALUE / 2
                        action()
                    } else {
                        lastUp = ch.uptimeMillis
                        lastPos = ch.position
                    }
                }
            }
        }
    }
}
