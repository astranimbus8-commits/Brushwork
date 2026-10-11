package com.brushwork.paint.ui.vector

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.tools.strip.StripSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment

/**
 * v1.8 F (design §3.9 "Shape"): what the Shape strip's UI tests share. Each runs ONE UI test
 * (Compose's frame clock serves one test per sandbox) over the two phone widths, a [StripRig]
 * (`panel = false`: the host as `ToolOptionsBar` calls it) per width:
 * - the tool's states: [fresh] (nothing open), [open] (a shape pending), [points] (Points mode,
 *   [select] picking the points) and [withCopies] (a mirror ruler on: the open shape has a copy);
 * - [inspect]: the lead's inventory ([StripInventory.inspect]: rules 1 to 5, the measured widths,
 *   the v1.7 handles of [StripHandlesV17]) and `legacyContent == null`;
 * - [split]: row 1 and the More row of a spec at each configuration, pure;
 * - [row1Handles] / [moreHandles]: what the rows show now; [clippedTexts]: texts cut by their item;
 * - [staysOpen]: a sheet opened from the More row outlives the row.
 */
internal object ShapeStripKit {
    /** The two phones of §3.9: 360 dp and the T606's 392 dp. */
    val WIDTHS = listOf(360 to "w360dp-h780dp-xxhdpi", 392 to "w392dp-h873dp-xxhdpi")

    /** Runs [block] on a fresh strip rig at each of [WIDTHS] (the screen's width in dp). */
    fun atEachWidth(block: (rig: StripRig, screenDp: Int) -> Unit) {
        for ((dp, qualifiers) in WIDTHS) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val rig = StripRig(panel = false).show()
            try {
                block(rig, dp)
            } finally {
                StripTestKit.closeMoreRow()
                rig.close()
                // The next width's rig is a new activity: this one's window leaves the screen.
                runCatching { rig.activity.windowManager.removeViewImmediate(rig.activity.window.decorView) }
                RobolectricUi.settle(2, 50)
            }
        }
    }

    /** Each tool's settings as it opened (cleared preferences): the state [StripHandlesV17] recorded. */
    private val initial = java.util.WeakHashMap<ShapeTool, ShapeSettings>()

    /** Each rig's raster "Layer 1" and "Vector 1" (picked by reference: nothing shifts them). */
    private val layers = java.util.WeakHashMap<StripRig, Pair<Layer, Layer>>()

    fun tool(rig: StripRig): ShapeTool = rig.c.tools.getValue(ToolId.SHAPE) as ShapeTool

    /**
     * The Shape tool on the raster layer, or on the vector layer when [vector] (the VECTOR cell),
     * nothing open, no ruler, the settings it opened with, [settings] applied.
     */
    fun fresh(rig: StripRig, vector: Boolean = false, settings: (ShapeSettings) -> ShapeSettings = { it }): ShapeTool {
        val t = tool(rig)
        // First: a layer change would commit the open shape (an editable one into a layer of its own).
        t.discard()
        rig.c.updateSymmetry(SymmetrySettings())
        val (raster, vectorLayer) = layers.getOrPut(rig) { rig.doc.layers[0] to rig.doc.layers[1] }
        rig.c.selectLayer(if (vector) vectorLayer else raster)
        rig.c.selectTool(ToolId.SHAPE)
        rig.settle()
        t.discard()
        val first = initial.getOrPut(t) { t.settings }
        t.update { settings(first) }
        rig.settle()
        return t
    }

    /** [fresh], then a shape opened at the canvas centre (the Numbers sheet's default shape). */
    fun open(rig: StripRig, vector: Boolean = false, settings: (ShapeSettings) -> ShapeSettings = { it }): ShapeTool {
        val t = fresh(rig, vector, settings)
        assertTrue("a shape opens", t.ensurePending())
        rig.settle()
        return t
    }

    /** [open], Points mode on, then [select] picks the points (all by default). */
    fun points(
        rig: StripRig,
        vector: Boolean = false,
        settings: (ShapeSettings) -> ShapeSettings = { it },
        select: (ShapeTool) -> Unit = { it.selectPoints(PointSelection.all(it.pointCount)) },
    ): ShapeTool {
        val t = open(rig, vector, settings)
        t.setPointEditing(true)
        assertTrue("Points mode", t.pointsMode)
        select(t)
        rig.settle()
        return t
    }

    /** A mirror ruler on, then [open]: the open shape has its mirror copy. */
    fun withCopies(rig: StripRig, vector: Boolean = false, settings: (ShapeSettings) -> ShapeSettings = { it }): ShapeTool {
        val t = fresh(rig, vector, settings)
        rig.c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        assertTrue("a shape opens", t.ensurePending())
        rig.settle()
        assertTrue("the open shape has copies", t.hasCopies)
        return t
    }

    /** The inventory of the state the rig shows as [key] ("SHAPE", "SHAPE@vector", ...): no problems, converted. */
    fun inspect(rig: StripRig, key: String, record: Set<String>? = StripHandlesV17.HANDLES[key]): Set<String> {
        assertNull("$key: converted (no legacy content)", rig.spec.legacyContent)
        val (shown, problems) = StripInventory.inspect(rig, key, record)
        assertEquals("$key: the inventory (shown: $shown)", emptyList<String>(), problems)
        assertFalse("$key: the inspection leaves the More row closed", rig.state.anyOpen)
        return shown
    }

    /** [spec]'s row 1 and More-row item keys, and the toggle, on a [screenDp] screen with or without the VECTOR cell. */
    fun split(spec: StripSpec, screenDp: Int, chip: Boolean): Triple<List<String>, List<String>, Boolean> {
        val available = StripInventory.content(screenDp.toFloat()) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
        val s = StripLayout.split(spec, available)
        return Triple(s.row1.map { it.key }, s.more.map { it.key }, s.toggle)
    }

    /** How many More-row lines [spec] takes on a [screenDp] screen with or without the VECTOR cell. */
    fun moreLines(spec: StripSpec, screenDp: Int, chip: Boolean): List<List<String>> {
        val available = StripInventory.content(screenDp.toFloat()) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
        val s = StripLayout.split(spec, available)
        return StripLayout.moreLines(s, spec.hint, StripInventory.content(screenDp.toFloat())).map { l -> l.map { it.key } }
    }

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    private fun under(n: SemanticsNode, tag: String) = generateSequence(n) { it.parent }.any { it.tag() == tag }

    /** The handles of the controls row 1 shows now, left to right. */
    fun row1Handles(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && StripInventory.isControl(n) && n.tag() != V18Tags.STRIP_TOGGLE && under(n, V18Tags.STRIP_ROW1) }
        .sortedBy { it.positionInWindow.x }
        .mapNotNull(StripInventory::handle)

    /** The handles of the controls the More row shows now (it must be open), in reading order. */
    fun moreHandles(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && StripInventory.isControl(n) && under(n, V18Tags.STRIP_MORE_ROW) }
        .sortedWith(compareBy({ it.positionInWindow.y.toInt() / 8 }, { it.positionInWindow.x }))
        .mapNotNull(StripInventory::handle)

    /**
     * The texts on row 1 and the More row that do not fit their fixed-width item (cut, ellipsized
     * or past their lines): the declared widths are measured, so a label must fit inside them.
     */
    fun clippedTexts(): List<String> = clippedTexts { n -> under(n, V18Tags.STRIP_ROW1) || under(n, V18Tags.STRIP_MORE_ROW) }

    /** [clippedTexts] of the placed nodes [where] picks (a sheet's texts...). */
    fun clippedTexts(where: (SemanticsNode) -> Boolean): List<String> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && where(n) }
        .mapNotNull { n ->
            val get = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
            val results = mutableListOf<TextLayoutResult>()
            if (get(results) != true) return@mapNotNull null
            val r = results.firstOrNull() ?: return@mapNotNull null
            val text = r.layoutInput.text.text
            val lines = 0 until r.lineCount
            val wider = lines.any { r.getLineRight(it) - r.getLineLeft(it) > n.size.width + 1f }
            val shortened = r.lineCount == 0 || r.getLineEnd(r.lineCount - 1) < text.length || lines.any { r.isLineEllipsized(it) }
            if (wider || shortened) "$text (lines ${lines.map { (r.getLineRight(it) - r.getLineLeft(it)).toInt() }} px in ${n.size.width} px)" else null
        }

    /** Back on the top window (a sheet, a dialog or a popup closes). */
    fun back(rig: StripRig) {
        val top = RobolectricUi.windowRoots().lastOrNull() ?: return
        if (top === rig.activity.window.decorView) return
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        rig.settle()
    }

    /**
     * §3.9 "Overlays": opens the More row, clicks [label] on it, and checks the row closed and
     * [shows] (the sheet or dialog) holds, and still holds once the UI settled again. Leaves the
     * overlay open (the caller closes it).
     */
    fun staysOpen(rig: StripRig, label: String, what: String, shows: () -> Boolean) {
        assertTrue("$what: the More row opens", StripTestKit.openMoreRow())
        assertTrue("$what: \"$label\" is on the More row: ${moreHandles()}", label in moreHandles())
        SmokeUi.click(label, exact = true)
        rig.settle()
        assertFalse("$what: the More row closed", StripTestKit.moreRowShown())
        assertTrue("$what: open", shows())
        rig.settle()
        assertTrue("$what: still open after the row closed", shows())
    }

    /** The state key of the Shape tool on the raster layer, or the vector layer when [vector]. */
    fun key(vector: Boolean) = "SHAPE" + if (vector) "@vector" else ""
}
