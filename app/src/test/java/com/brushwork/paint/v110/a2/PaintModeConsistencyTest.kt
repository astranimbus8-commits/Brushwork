package com.brushwork.paint.v110.a2

import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.paintModeName
import com.brushwork.paint.ui.vector.curvePaint
import com.brushwork.paint.ui.vector.curvePaintRefusal
import com.brushwork.paint.ui.vector.paintMode
import com.brushwork.paint.ui.vector.shapeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.10 (R3 (a) and (e); design 4.4 A2 step 2): one UI for one function, read from the sources.
 * Every paint-mode control of the Shape and curve tools is built from `PaintModeSegments` or
 * `PaintModeGlyph` (through VectorWidgets' PaintModeSheetRow, PaintModeMenuCell and
 * PaintModeChoices); v1.9's controls are gone from those places: the `StyleGlyph` canvas, the
 * `ShapeStyle` text chips and menu entries, the "Stroke" / "Fill" / "Both" entries, the "Fill the
 * path" switch, and `LineWeight` / `FormatColorFill` / `FormatShapes` as a paint mode's face
 * (row 7: `LineWeight` means a width). The enums keep their names and map one to one, and
 * ShapeStyle's labels are the paint modes' names.
 */
class PaintModeConsistencyTest {

    private fun mainSources(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (c in listOf(File(dir, "src/main/java/com/brushwork/paint"), File(dir, "app/src/main/java/com/brushwork/paint"))) {
                if (c.isDirectory) return c
            }
            dir = dir.parentFile
        }
        throw AssertionError("main sources not found from ${System.getProperty("user.dir")}")
    }

    private fun source(path: String): String = File(mainSources(), path).readText()

    /** The text of every `// region v110-A2 paintMode` block of [path] (to its `// endregion`). */
    private fun paintModeRegions(path: String): String {
        val lines = source(path).lines()
        val out = StringBuilder()
        var inside = false
        var blocks = 0
        for (l in lines) {
            val t = l.trim()
            when {
                t == "// region v110-A2 paintMode" -> { inside = true; blocks++ }
                inside && t == "// endregion" -> inside = false
                inside -> out.appendLine(l)
            }
        }
        assertTrue("$path: a v110-A2 paintMode region", blocks > 0)
        return out.toString()
    }

    /** The v1.9 paint-mode controls that must be gone from the places of R3 (a). */
    private val gone = listOf(
        "StyleGlyph(", "ShapeStyle.entries", "PaintModeEntry(", "\"Fill the path\"",
        "Icons.Filled.LineWeight", "Icons.Filled.FormatColorFill", "Icons.Filled.FormatShapes",
        "\"Stroke\", CurveLabels17", "\"Fill\", CurveLabels17", "\"Both\", CurveLabels17",
    )

    private fun assertNoneGone(where: String, text: String) {
        for (g in gone) assertTrue("$where still holds $g", !text.contains(g))
    }

    @Test
    fun everyPaintModeControlIsTheSegmentsOrTheGlyph() {
        // Row 1: the Shape strip's style cell shows the glyph; its menu the segments.
        val shapeStrip = paintModeRegions("ui/vector/ShapeStrip.kt")
        assertTrue("ShapeStrip: PaintModeMenuCell", shapeStrip.contains("PaintModeMenuCell("))
        assertTrue("ShapeStrip: tagged as the strip's", shapeStrip.contains("PaintModeTags.SHAPE_STRIP"))
        assertNoneGone("ShapeStrip's region", shapeStrip)

        // Row 2: ShapeToolOptions.kt's Shape settings sheet. (Row 5, the v1.5 strip function the tests
        // still rendered, is gone, v1.10 G2 V4: the strip's style cell of row 1 is the only one.)
        val shapeOptions = source("ui/vector/ShapeToolOptions.kt")
        assertTrue("Shape settings: PaintModeSheetRow", shapeOptions.contains("PaintModeSheetRow(s.style.paintMode(), PaintModeTags.SHAPE)"))
        assertTrue("ShapeToolOptions() is gone", !shapeOptions.contains("fun ShapeToolOptions("))
        assertTrue("ShapeToolOptions.kt: no ShapeStyle chips", !shapeOptions.contains("ShapeStyle.entries") && !shapeOptions.contains("StyleGlyph("))

        // Row 3: the curve strips' Stroke kind cell: the glyph as its face, the choices in its menu.
        val curveStrips = paintModeRegions("ui/vector/CurveStrips.kt")
        assertTrue("CurveStrips: PaintModeGlyph face", curveStrips.contains("PaintModeGlyph("))
        assertTrue("CurveStrips: PaintModeChoices", curveStrips.contains("PaintModeChoices("))
        assertTrue("CurveStrips: tagged as the strip's", curveStrips.contains("PaintModeTags.CURVE_STRIP"))
        assertNoneGone("CurveStrips' region", curveStrips)

        // Row 4: the curve settings sheet: the segments; no "Fill the path" switch.
        val curveOptions = paintModeRegions("ui/vector/CurveToolOptions.kt")
        assertTrue("Curve settings: PaintModeSheetRow", curveOptions.contains("PaintModeSheetRow(") && curveOptions.contains("PaintModeTags.CURVE"))
        // Review fix: the sheet refuses Fill and Both with 1 or 2 points, as the strip's menu does.
        assertTrue("Curve settings: the strip's refusal", curveOptions.contains("refusal = curvePaintRefusal(tool.fillPossible)"))
        assertNoneGone("CurveToolOptions' region", curveOptions)

        // The Object colors sheet's mode glyphs: the segments with their refusal (v1.10 G2 V4), so a
        // refused mode says why.
        val sheet = source("ui/vector/ObjectColorsSheet.kt")
        assertTrue("Object colors: the paint-mode glyphs", sheet.contains("PaintModeSegments(") && sheet.contains("refusal = "))
        assertTrue("Object colors: no workaround around the segments", !sheet.contains("PaintModeChoices("))
        assertNoneGone("ObjectColorsSheet.kt", sheet)

        // The three VectorWidgets controls are built from the F0 controls; StyleGlyph moved to F0.
        val widgets = source("ui/vector/VectorWidgets.kt")
        assertTrue("VectorWidgets: StyleGlyph moved to F0 (PaintModeGlyph)", !widgets.contains("fun StyleGlyph("))
        for (f in listOf("PaintModeSheetRow", "PaintModeMenuCell", "PaintModeChoices")) {
            val start = widgets.indexOf("internal fun $f(")
            assertTrue("VectorWidgets: $f", start >= 0)
            val next = widgets.indexOf("\n@Composable", start).let { if (it < 0) widgets.length else it }
            val body = widgets.substring(start, next)
            assertTrue("$f draws PaintModeSegments or PaintModeGlyph", body.contains("PaintModeSegments(") || body.contains("PaintModeGlyph("))
        }
        // Row 7: LineWeight is a width: never in a paint-mode helper.
        val helpers = widgets.substring(widgets.indexOf("paint mode (v1.10, R3)").also { assertTrue("the paint-mode section", it >= 0) })
            .let { it.substring(0, it.indexOf("internal const val GRADIENT_KEPT").let { e -> if (e < 0) it.length else e }) }
        assertNoneGone("VectorWidgets' paint-mode section", helpers)
    }

    @Test
    fun theEnumsMapOneToOneAndShareTheNames() {
        for (st in ShapeStyle.entries) {
            assertEquals(st, st.paintMode().shapeStyle())
            assertEquals("ShapeStyle.$st reads as its paint mode", paintModeName(st.paintMode()), st.label)
        }
        for (p in CurvePaint.entries) assertEquals(p, p.paintMode().curvePaint())
        for (m in PaintMode.entries) {
            assertEquals(m, m.shapeStyle().paintMode())
            assertEquals(m, m.curvePaint().paintMode())
            assertEquals("the same enum name everywhere", m.name, m.curvePaint().name)
            // The curve tools' refusal: Fill and Both need 3 points; Stroke never refused.
            assertEquals(if (m == PaintMode.STROKE) null else CurveLabels17.FILL_NEEDS_3, curvePaintRefusal(false)(m))
            assertEquals(null, curvePaintRefusal(true)(m))
        }
        assertNotNull(ShapeStyle.entries.firstOrNull { it.paintMode() == PaintMode.BOTH })
    }
}
