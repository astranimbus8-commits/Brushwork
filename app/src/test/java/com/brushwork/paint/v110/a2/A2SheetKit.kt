package com.brushwork.paint.v110.a2

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ColoredObjectTool
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.BwSheetTitleKey
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.SheetHost
import com.brushwork.paint.ui.common.SheetPill
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.rememberSheetHostState
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.PillColorCell
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * A2 test kit: the Object colors sheet as the editor hosts it, for the UI tests that drive a
 * real tool ([A2ToolRig]'s controller). [show] sets up the activity (the picker in wheel mode)
 * with a [SheetHost] and the pill's COLOR cell, shown while the current tool has an open object's
 * colors (as the editor's pill), and [extra] (a settings sheet) when given. The helpers find
 * controls by tag or label and type into a sheet's own text field.
 */
internal object A2SheetKit {

    /** The activity with the sheet host, the pill's cell over the canvas's place and [extra]. */
    fun show(c: EditorController, extra: @Composable () -> Unit = {}): ComponentActivity {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        activity.setContent {
            BrushworkTheme {
                val h = rememberSheetHostState()
                CompositionLocalProvider(LocalSheetHost provides h, LocalIncrements provides c.increments) {
                    // Back as in the editor (EditorScreen): it closes the top sheet as its ✕ would.
                    BackHandler(enabled = !h.isEmpty) { h.dismissTop() }
                    Box(Modifier.fillMaxSize()) {
                        SheetHost(h, bottomInset = 0.dp)
                        SheetPill(h, Modifier.align(Alignment.BottomCenter))
                        Box(Modifier.align(Alignment.TopStart).padding(top = 120.dp, start = 8.dp)) { Cell(c) }
                    }
                    extra()
                }
            }
        }
        SmokeUi.settle()
        return activity
    }

    /** The pill's COLOR cell as the editor's pill shows it: while the tool has an open object's colors. */
    @Composable
    private fun Cell(c: EditorController) {
        val e = (c.tools[c.activeToolId] as? ColoredObjectTool)?.objectColors ?: return
        PillColorCell(e)
    }

    /** What a frame does in the app: state to its observers, the main looper's work, recomposition. */
    fun settle() {
        frame()
        shadowOf(Looper.getMainLooper()).idle()
        SmokeUi.settle()
    }

    fun byTag(tag: String): RobolectricUi.Element? =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }

    fun tapTag(tag: String) {
        val e = byTag(tag) ?: throw AssertionError("no $tag; shown: ${SmokeUi.shown().take(40)}")
        requireNotNull(e.node.config.getOrNull(SemanticsActions.OnClick)?.action) { "$tag is not clickable" }.invoke()
        SmokeUi.settle()
    }

    fun tab(t: ObjectColorTarget) = "${V110Tags.COLORS_SHEET_TAB}.${t.name}"

    /** Taps the pill's cell: the Object colors sheet opens on the open object. */
    fun openSheet(where: String) {
        tapTag(V110Tags.PILL_COLOR)
        assertEquals("$where: the sheet opened", listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
    }

    /** The shown panel of the sheet titled [title]. */
    private fun panel(title: String): RobolectricUi.Element =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(BwSheetTitleKey) == title && it.node.layoutInfo.isPlaced }
            ?: throw AssertionError("no sheet \"$title\"; sheets: ${SmokeUi.sheetTitles()}")

    private fun RobolectricUi.Element.within(p: RobolectricUi.Element): Boolean {
        val b = p.bounds
        return window === p.window && node.layoutInfo.isPlaced &&
            bounds.top >= b.top && bounds.bottom <= b.bottom && bounds.left >= b.left && bounds.right <= b.right
    }

    /** Types [hex] into the text field of the sheet titled [title] (its own, not another sheet's). */
    fun typeIn(title: String, hex: String) {
        val p = panel(title)
        // Its first field from the top: the hex (the picker's other fields are below it).
        val field = RobolectricUi.textFields().firstOrNull { it.within(p) } ?: throw AssertionError("no text field in the sheet \"$title\"")
        field.type(hex)
        SmokeUi.settle()
    }

    /** Taps the control named [label] (its text or description) inside the sheet titled [title]. */
    fun tapIn(title: String, label: String) {
        val p = panel(title)
        val e = RobolectricUi.elements().lastOrNull { e ->
            e.within(p) && (
                e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                    e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
                )
        } ?: throw AssertionError("no \"$label\" in the sheet \"$title\"")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        requireNotNull(n?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "\"$label\" is not clickable" }.invoke()
        SmokeUi.settle()
    }

    /** Whether the control named [label] (its description) is shown selected. */
    fun selected(label: String): Boolean? =
        RobolectricUi.elements().lastOrNull { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true }
            ?.node?.config?.getOrNull(SemanticsProperties.Selected)

    fun selectedTag(tag: String): Boolean? = byTag(tag)?.node?.config?.getOrNull(SemanticsProperties.Selected)

    fun assertOpen(where: String) = assertEquals(where, listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())

    /** No Object colors sheet, open or minimized, and the cell (if shown) not selected. */
    fun assertClosed(where: String) {
        assertTrue("$where: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", ObjectColorLabels110.CELL !in SmokeUi.sheetTitles() && ObjectColorLabels110.CELL !in SmokeUi.pillTitles())
        byTag(V110Tags.PILL_COLOR)?.let { assertEquals("$where: the cell is not selected", false, it.node.config.getOrNull(SemanticsProperties.Selected)) }
    }
}
