package com.brushwork.paint.v110.a5

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.vector.CurveKeys
import com.brushwork.paint.ui.vector.CurveStripFacts
import com.brushwork.paint.ui.vector.CurveStripUi
import com.brushwork.paint.ui.vector.SharpState
import com.brushwork.paint.ui.vector.curveSpec
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.draw.VectorEraseMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (A5, R12): the More row's "Sharp corner" / "Smooth" item gave way to the X / Y pill's
 * cell, and the point thickness control keeps v1.9's place on the More row of Curve and Path:
 * - on the same line, with the same items after it (the lines are end-aligned), whether or not
 *   "All points 100 %" shows. That item comes and goes with the slider's own use (a point leaves
 *   or reaches 100 %) and sits before it, so a line change moved the slider away from the finger
 *   in the middle of a drag (at 392 dp, without the kept place of the removed item);
 * - a line of its own (it starts its line), never the last item of a line, where its slider's
 *   reach would meet the line's clip at the strip's end.
 * Pure: the specs of every open state with points selected, split as the host splits them at
 * 392 and 360 dp, with and without the VECTOR cell.
 */
@RunWith(RobolectricTestRunner::class)
class ThicknessKeepsItsLineTest {
    private val c: EditorController by lazy { controller() }

    private fun spec(f: CurveStripFacts) = curveSpec(c.tool(if (f.kind == CurveKind.PATH) ToolId.PATH else ToolId.CURVE), f, CurveStripUi())

    /** The thickness item's More line, whether it starts that line, and the keys after it there. */
    private data class Place(val line: Int, val first: Boolean, val after: List<String>)

    private fun place(f: CurveStripFacts, screen: Float, chip: Boolean): Place? {
        val spec = spec(f)
        val content = StripInventory.content(screen)
        val split = StripLayout.split(spec, content - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f)
        StripLayout.moreLines(split, spec.hint, content).forEachIndexed { i, line ->
            val k = line.indexOfFirst { it.key == CurveKeys.THICKNESS }
            if (k >= 0) return Place(i, k == 0, line.drop(k + 1).map { it.key })
        }
        return null
    }

    /** The open Curve (with its Handles, as a curve of two points or more) and Path states with one or two points selected. */
    private fun states(): List<CurveStripFacts> = buildList {
        val erase = listOf<VectorEraseMode?>(null) + VectorEraseMode.entries
        for (kind in listOf(CurveKind.CURVE, CurveKind.PATH)) for (sel in listOf(1, 2)) {
            val sharps = listOf(SharpState.ENDS, SharpState.SMOOTH, SharpState.SHARP) + if (sel >= 2) listOf(SharpState.MIXED) else emptyList()
            val gens = if (kind == CurveKind.PATH) listOf(null, CurveGenKind.ARCHIMEDEAN) else listOf(null)
            val tangents = if (kind == CurveKind.CURVE && sel == 1) listOf(false, true) else listOf(false)
            for (stroke in CurveStroke.entries) for (g in gens) for (sharp in sharps) for (tangent in tangents) for (e in erase) {
                add(
                    CurveStripFacts(
                        kind = kind, points = 5, selected = sel, single = if (sel == 1) 1 else -1, stroke = stroke, generated = g,
                        sharp = sharp, customTangent = tangent, handles = kind == CurveKind.CURVE, eraseCell = e,
                    ),
                )
            }
        }
    }

    @Test
    fun theThicknessControlKeepsItsPlaceWhenAllPoints100ComesAndGoes() {
        val problems = mutableListOf<String>()
        var checked = 0
        for (f in states()) for (screen in listOf(392f, 360f)) for (chip in listOf(false, true)) {
            val where = "${f.kind} selected ${f.selected} ${f.sharp} ${f.stroke} gen ${f.generated} tangent ${f.customTangent} erase ${f.eraseCell} at ${screen.toInt()} dp${if (chip) " with the VECTOR cell" else ""}"
            val at100 = place(f.copy(anyThickness = false), screen, chip)
            val varied = place(f.copy(anyThickness = true), screen, chip)
            if (at100 == null || varied == null) {
                problems += "$where: no thickness item on the More row"
                continue
            }
            checked++
            if (at100 != varied) problems += "$where: it moves when \"All points 100 %\" comes: $at100 -> $varied"
            for ((name, p) in listOf("at 100 %" to at100, "varied" to varied)) {
                if (!p.first) problems += "$where, $name: it does not start its line: $p"
                if (p.after.isEmpty()) problems += "$where, $name: it ends its line: $p"
            }
        }
        assertTrue("states checked: $checked", checked > 0)
        assertTrue("the thickness control's place:\n" + problems.take(20).joinToString("\n"), problems.isEmpty())
    }
}
