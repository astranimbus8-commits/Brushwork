package com.brushwork.paint.ui.editor.chrome

import com.brushwork.paint.ui.theme.IbisDims
import kotlin.math.max
import kotlin.math.min

/**
 * The ibisPaint main-screen geometry (v1.6 §3.7.2), in dp, as pure functions of the screen size and
 * its system bar insets. On the reference phone (392 × 873 dp, status bar 33, navigation bar 42) it
 * gives exactly the bands of the design: top row 33–81, options strip 85–129, X / Y pill at 135,
 * canvas fit insets 137 / 172, slider rows at 701 and 741, bottom bar 781–831, layer window
 * 382 × 520 from y 261. Every value is derived from [IbisDims], so the dims stay the single source.
 */
object ChromeLayout {

    // ------------------------------------------------------------------ top row (§3.7.3)

    /** The top-row circles, left to right (ibisPaint's silhouettes: undo, redo, toggle, dashed rect, hand, grid, ruler, picture). */
    enum class TopSlot { UNDO, REDO, VECTOR, SELECTION, STABILIZER, GRID, RULER, MORE }

    /** Narrow screens move these into the top of the More menu, the first one first (§3.7.2). */
    val FOLD_ORDER: List<TopSlot> = listOf(TopSlot.RULER, TopSlot.GRID, TopSlot.STABILIZER)

    /**
     * The top row on a screen [widthDp] wide (after horizontal insets): the [slots] shown at
     * [pitch] dp (each slot's touch target is pitch × [IbisDims.TopRowHeight]), circles of
     * [circle] dp, and the slots [folded] into the More menu.
     */
    data class TopRow(val pitch: Float, val circle: Float, val slots: List<TopSlot>) {
        val folded: List<TopSlot> get() = FOLD_ORDER.filter { it !in slots }

        /** Centre x (dp from the row's start) of slot [index]. */
        fun centerX(index: Int): Float = pitch * index + pitch / 2f
    }

    fun topRow(widthDp: Float): TopRow {
        val usable = (widthDp - IbisDims.TopNarrowMargin.value).coerceAtLeast(1f)
        var folded = 0
        if (widthDp < IbisDims.TopFoldWidth.value) {
            // Below the fold width Ruler goes first; more follow while the targets stay too small.
            folded = 1
            while (folded < FOLD_ORDER.size && usable / (TopSlot.entries.size - folded) < IbisDims.TopNarrowTouchMin.value) folded++
        }
        val slots = TopSlot.entries.filter { it !in FOLD_ORDER.take(folded) }
        val pitch = min(IbisDims.TopPitch.value, usable / slots.size)
        // 40 at the 48 dp pitch, 36 at 44 dp and below (the ring of surround between circles stays 8 dp).
        val circle = (pitch - (IbisDims.TopPitch.value - IbisDims.TopCircle.value))
            .coerceIn(IbisDims.TopNarrowCircleMin.value, IbisDims.TopCircle.value)
        return TopRow(pitch, circle, slots)
    }

    // ------------------------------------------------------------------ vertical bands

    /** Gap between the top row and the options strip (4 dp). */
    val optionsStripGap: Float get() = IbisDims.OptionsStripTop.value - IbisDims.StatusBar.value - IbisDims.TopRowHeight.value

    /** Gap between the options strip and the X / Y pill (6 dp). */
    val pillGap: Float get() = IbisDims.PillTop.value - IbisDims.OptionsStripTop.value - IbisDims.OptionsStripHeight.value

    /** Gap between the options strip and the canvas fit area (8 dp). */
    val fitGap: Float get() = IbisDims.FitInsetTop.value - IbisDims.OptionsStripTop.value - IbisDims.OptionsStripHeight.value

    /** Gap between the X / Y pill and a selection bar shown under it (6 dp: 135 + 32 + 6 = 173). */
    val selectionBarGap: Float
        get() = IbisDims.SelectionBarTopBelowPill.value - IbisDims.PillTop.value - IbisDims.PillHeight.value

    fun topRowBottom(statusDp: Float): Float = statusDp + IbisDims.TopRowHeight.value

    fun optionsStripTop(statusDp: Float): Float = topRowBottom(statusDp) + optionsStripGap

    fun pillTop(statusDp: Float): Float = optionsStripTop(statusDp) + IbisDims.OptionsStripHeight.value + pillGap

    /** Where the selection / object bar goes: at the pill's place, or under the pill when it shows ([pillHeightDp] > 0). */
    fun selectionBarTop(statusDp: Float, pillHeightDp: Float): Float =
        if (pillHeightDp > 0f) pillTop(statusDp) + pillHeightDp + selectionBarGap else pillTop(statusDp)

    /**
     * Canvas fit inset at the top: the top row and the options strip. The pill and the selection
     * bar are left out (they never move the canvas, V11), and so is the hidden state of the interface.
     */
    fun fitInsetTop(statusDp: Float): Float = optionsStripTop(statusDp) + IbisDims.OptionsStripHeight.value + fitGap

    /**
     * How many slider rows the brush sliders take on a screen [widthDp] wide: size over opacity
     * (2) on a phone, as ibisPaint; side by side in one row (1) from [IbisDims.SliderOneRowWidth]
     * on (tablets, a phone in landscape: v1.5's rule, which leaves the canvas twice the height there).
     */
    fun sliderRowCount(widthDp: Float): Int = if (widthDp >= IbisDims.SliderOneRowWidth.value) 1 else 2

    /** Canvas fit inset at the bottom: the [rows] slider rows, the bottom bar and the navigation bar. */
    fun fitInsetBottom(navDp: Float, rows: Int = 2): Float = navDp + IbisDims.BottomBarHeight.value + rows * IbisDims.SliderRowHeight.value

    /** The top of the bottom bar on a screen [screenHeightDp] tall. */
    fun bottomBarTop(screenHeightDp: Float, navDp: Float): Float = screenHeightDp - navDp - IbisDims.BottomBarHeight.value

    /** The top of the size slider row (on a phone the opacity row follows it). */
    fun sliderRowsTop(screenHeightDp: Float, navDp: Float, rows: Int = 2): Float =
        bottomBarTop(screenHeightDp, navDp) - rows * IbisDims.SliderRowHeight.value

    // ------------------------------------------------------------------ bottom bar (§3.7.5)

    /** Width of each of the 7 bottom slots: 56 on the reference phone, shared evenly on narrower screens. */
    fun bottomSlotWidth(widthDp: Float): Float = min(IbisDims.BottomSlotWidth.value, widthDp / IbisDims.BottomSlots)

    // ------------------------------------------------------------------ tool menu (§3.7.6)

    /** The tool menu's height limit: 434 dp, or less where the room between the top row and the bar is short. */
    fun toolMenuMaxHeight(screenHeightDp: Float, statusDp: Float, navDp: Float): Float {
        val room = bottomBarTop(screenHeightDp, navDp) - IbisDims.ToolMenuAboveBar.value - topRowBottom(statusDp) - IbisDims.LayerWindowTopRoom.value
        return max(IbisDims.ToolCellHeight.value, min(IbisDims.ToolMenuMaxHeight.value, room))
    }

    /**
     * The More menu's height limit (§3.7.6: "under the top row … scrolling"): the room between the
     * top row and the bottom bar, less a gap. Without it the menu (some 17 entries) is taller than
     * that room, and Material's dropdown, finding no place below its anchor, moves it up over the
     * top row and down over the bottom bar. (Material keeps a menu below its anchor only when it
     * ends 48 dp above the window's bottom: the bottom bar and the gap leave that.)
     */
    fun moreMenuMaxHeight(screenHeightDp: Float, statusDp: Float, navDp: Float): Float {
        val room = bottomBarTop(screenHeightDp, navDp) - topRowBottom(statusDp) - IbisDims.LayerWindowTopRoom.value
        return max(IbisDims.ToolCellHeight.value, room)
    }

    // ------------------------------------------------------------------ layer window (§3.7.7, sizing owned by E)

    /**
     * The layer window: [x] from the left, [width] × [height], its bottom on the bottom bar's top.
     * [short] screens (under 480 dp tall, e.g. a phone in landscape) use the v1.5 side-by-side window
     * instead ([width] / [height] are then 0: the host keeps the v1.5 sizing).
     */
    data class LayerWindow(val x: Float, val width: Float, val height: Float, val bottom: Float, val short: Boolean) {
        val top: Float get() = bottom - height
    }

    fun layerWindow(screenWidthDp: Float, screenHeightDp: Float, statusDp: Float, navDp: Float): LayerWindow {
        val barTop = bottomBarTop(screenHeightDp, navDp)
        if (screenHeightDp < IbisDims.LayerWindowShortScreen.value) return LayerWindow(0f, 0f, 0f, barTop, short = true)
        val width = min(IbisDims.LayerWindowWidth.value, screenWidthDp - IbisDims.LayerWindowSideRoom.value)
        val available = barTop - topRowBottom(statusDp)
        val height = min(IbisDims.LayerWindowHeight.value, available - IbisDims.LayerWindowTopRoom.value)
        return LayerWindow(IbisDims.LayerWindowStart.value, width.coerceAtLeast(1f), height.coerceAtLeast(1f), barTop, short = false)
    }
}
