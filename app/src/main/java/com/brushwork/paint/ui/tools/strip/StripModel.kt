package com.brushwork.paint.ui.tools.strip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

/**
 * v1.8 (items 8 and 9, design §3.9 and §4.6): the options strip as data. Each tool's builder
 * (`ToolStrips`) describes its controls as [StripItem]s; [StripLayout] splits them between row 1
 * (one fixed row that never scrolls, I18) and the More row under it; `StripHost` draws both.
 * Frozen from F3 on (§4.5): areas fill specs, they never change these declarations.
 */
enum class StripRow { ROW1, MORE }

/** One control of a tool's strip. */
@Immutable
class StripItem(
    /** Stable and unique within its spec, e.g. "brush.size". */
    val key: String,
    /** The control's handle (description, else visible text): the inventory and the tests use it. */
    val label: String,
    /** Declared width; 40 dp minimum for anything tappable (TouchTargetAuditTest.MIN). Items are 4 dp apart. */
    val width: Dp,
    val row: StripRow = StripRow.ROW1,
    /** ROW1 only: 0 = never leaves row 1; above 0 (AUTO) = moves to the More row's front when row 1 is full, highest rank first. */
    val overflowRank: Int = 0,
    /** False keeps the width reserved and draws nothing, so neighbours never jump. */
    val shown: Boolean = true,
    val content: @Composable () -> Unit,
)

/** What a tool's strip shows: its items, an optional hint, and the sheets and dialogs it opens. */
class StripSpec(
    val items: List<StripItem>,
    /** Optional one-line hint, the More row's first line. */
    val hint: String? = null,
    /**
     * Every sheet and dialog the strip opens (§3.9 "Overlays"). The host composes it whenever the
     * tool's strip shows, More row open or closed, so a sheet opened from the More row outlives it.
     */
    val overlays: @Composable () -> Unit = {},
    /** v1.7 handles that now live inside an item's dropdown or popup: handle to item key (inventory rule 5). */
    val movedInto: Map<String, String> = emptyMap(),
) {
    companion object {
        /** A converted strip with nothing in it (a tool without options). */
        val EMPTY = StripSpec(emptyList())
    }
}

/** What a builder needs from the chrome, without reaching into `EditorScreen`. */
class StripContext(
    /** The selection bar or the object bar is showing (EditorScreen.selectionBarVisible / objectBarVisible). */
    val selectionBarShown: Boolean,
    val vectorMode: Boolean,
    /** Closes the More row (D25). An item calls it before it opens a sheet or a dialog; an anchored dropdown never does. */
    val closeMore: () -> Unit = {},
)

/** Provided by `EditorScreen` around the strip (and refined by `ToolOptionsBar`, which knows the vector mode). */
val LocalStripContext = staticCompositionLocalOf { StripContext(selectionBarShown = false, vectorMode = false) }
