package com.brushwork.paint.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.V18Tags

/**
 * v1.8 (F4, design §3.2 and §4.9; frozen, the lead's): the X / Y pill's "Select several" cell
 * (tag [V18Tags.PILL_SEVERAL]) for tests that drive the UI through [RobolectricUi] or `SmokeUi`.
 * The cell replaced the strips' "Select several" chips (Curve, Polyline, Path, Shape points, Free
 * deform): a 40 dp toggle at the end of the pill's first row, described [PointLabels.SELECT_SEVERAL],
 * its state "On" / "Off". It shows while the tool's `PointEditor.selectSeveralAvailable`.
 */
object PillTestKit {
    /** The placed "Select several" cell (the last one), or null: no points to pick, or no pill. */
    fun severalCell(): SemanticsNode? = StripTestKit.tagged(V18Tags.PILL_SEVERAL)

    /** The cell's state, "On" or "Off" (null without the cell). */
    fun severalState(): String? = severalCell()?.config?.getOrNull(SemanticsProperties.StateDescription)

    /**
     * Switches "Select several" through the cell (its click action, what a tap does), then lets
     * the UI settle as `SmokeUi.settle()` does. Fails when the cell does not show.
     */
    fun toggleSeveral() {
        val cell = requireNotNull(severalCell()) { "no \"${PointLabels.SELECT_SEVERAL}\" cell in the X / Y pill" }
        requireNotNull(cell.config.getOrNull(SemanticsActions.OnClick)?.action) { "the \"${PointLabels.SELECT_SEVERAL}\" cell is not clickable" }.invoke()
        RobolectricUi.settle(SETTLE_STEPS, SETTLE_MS)
    }

    /** As `SmokeUi.settle()`: 12 frames of 50 ms. */
    private const val SETTLE_STEPS = 12
    private const val SETTLE_MS = 50L
}
