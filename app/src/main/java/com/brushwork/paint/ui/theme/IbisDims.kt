package com.brushwork.paint.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every size of the ibisPaint layout (v1.6 §3.7, measured on the reference screenshots: V13; the
 * reference phone is 392 × 873 dp). Values are dp (or sp for text) as the design gives them; y
 * positions are from the screen top on that phone (status bar ≈ 33 dp). Area E owns the file and
 * changes values, never names (the layout tests read them).
 */
object IbisDims {
    // ------------------------------------------------------------------ §3.7.2 screen bands

    /** Status bar height measured on the reference phone (system inset; informative). */
    val StatusBar: Dp = 33.dp

    /** Navigation bar height measured on the reference phone (system inset; informative). */
    val NavBar: Dp = 42.dp

    /** Top row band: y 33, 48 tall, no background. */
    val TopRowHeight: Dp = 48.dp

    /** Top row circles: visual diameter, touch target, pitch and first centre x (centres at 24 + 48·i). */
    val TopCircle: Dp = 40.dp
    val TopCircleTouch: Dp = 48.dp
    val TopPitch: Dp = 48.dp
    val TopFirstCenterX: Dp = 24.dp

    /** Narrow screens: pitch = min(48, (width − 8) / 8); circles shrink to at least this, touch stays ≥ [TopNarrowTouchMin]. */
    val TopNarrowCircleMin: Dp = 36.dp
    val TopNarrowTouchMin: Dp = 44.dp
    val TopNarrowMargin: Dp = 8.dp

    /** Below this width Ruler, then Grid, then Stabilizer fold into the More menu. */
    val TopFoldWidth: Dp = 336.dp

    /** Top row glyphs (white, 22 dp). */
    val TopGlyph: Dp = 22.dp

    /** Options strip: y 85, 44 tall, floating rounded panel from x 6 to width − 6, radius 10. */
    val OptionsStripTop: Dp = 85.dp
    val OptionsStripHeight: Dp = 44.dp
    val OptionsStripSide: Dp = 6.dp
    val OptionsStripRadius: Dp = 10.dp

    /** X / Y pill band: y 135, x 8. */
    val PillTop: Dp = 135.dp
    val PillStart: Dp = 8.dp

    /** Selection / object bar: 40 tall, at y 135, or 173 below the pill. */
    val SelectionBarHeight: Dp = 40.dp
    val SelectionBarTop: Dp = 135.dp
    val SelectionBarTopBelowPill: Dp = 173.dp

    /** Canvas fit insets on the reference phone (the pill and the selection bar are left out). */
    val FitInsetTop: Dp = 137.dp
    val FitInsetBottom: Dp = 172.dp

    /** Slider rows: size at y 701, opacity at y 741, each 40 tall (ibis is 34; 40 keeps every target ≥ 40). */
    val SliderRowHeight: Dp = 40.dp
    val SizeRowTop: Dp = 701.dp
    val OpacityRowTop: Dp = 741.dp

    /** From this width on (tablets, a phone in landscape) the two slider rows sit side by side in one row (v1.5's rule). */
    val SliderOneRowWidth: Dp = 560.dp

    /** The gap between the two halves of that one row. */
    val SliderOneRowGap: Dp = 12.dp

    /** Bottom bar: y 781, 50 tall, 7 slots of 56 × 50. */
    val BottomBarTop: Dp = 781.dp
    val BottomBarHeight: Dp = 50.dp
    val BottomSlotWidth: Dp = 56.dp
    const val BottomSlots: Int = 7

    // ------------------------------------------------------------------ §3.7.4 slider rows

    /** Value text: x 0–58, right-aligned, 12 sp with a 1 dp white halo. */
    val SliderValueWidth: Dp = 58.dp
    val SliderValueText: TextUnit = 12.sp
    val SliderValueHalo: Dp = 1.dp

    /** − / + buttons: centres x 73 and 375, visual 22, touch 40 × 40. */
    val SliderMinusCenterX: Dp = 73.dp
    val SliderPlusCenterX: Dp = 375.dp
    val SliderButton: Dp = 22.dp
    val SliderButtonTouch: Dp = 40.dp

    /** Track: x 92–355, 8 thick, radius 4. */
    val SliderTrackStart: Dp = 92.dp
    val SliderTrackEnd: Dp = 355.dp
    val SliderTrackThickness: Dp = 8.dp
    val SliderTrackRadius: Dp = 4.dp

    /** Thumb: 22 white with a 1 dp ring. */
    val SliderThumb: Dp = 22.dp
    val SliderThumbRing: Dp = 1.dp

    /** Opacity track checker cell. */
    val SliderChecker: Dp = 4.dp

    // ------------------------------------------------------------------ §3.7.5 bottom bar

    val BottomGlyph: Dp = 26.dp

    /** Slot 3: black disc with a white ring and the size in white. */
    val BrushDisc: Dp = 40.dp
    val BrushDiscRing: Dp = 1.5.dp
    val BrushDiscText: TextUnit = 11.sp

    /** Slot 4: colour square with a white border. */
    val ColorSquare: Dp = 26.dp
    val ColorSquareBorder: Dp = 1.5.dp

    /** The open state of slots 2 and 6: a 41 × 43 box with a 1 dp edge. */
    val OpenBoxWidth: Dp = 41.dp
    val OpenBoxHeight: Dp = 43.dp
    val OpenBoxEdge: Dp = 1.dp

    /** Hide / show interface fade (ms). */
    const val HideInterfaceFadeMs: Int = 150

    // ------------------------------------------------------------------ §3.7.6 tool menu, More menu

    /** Tool menu: x 6, bottom 16 dp above the bottom bar (y 765), 150 wide, at most 434 tall, radius 12. */
    val ToolMenuStart: Dp = 6.dp
    val ToolMenuAboveBar: Dp = 16.dp
    val ToolMenuWidth: Dp = 150.dp
    val ToolMenuMaxHeight: Dp = 434.dp
    val ToolMenuRadius: Dp = 12.dp

    /** Tool menu cells: two columns of 75 × 52; a 28 dp glyph over an 11 sp label (up to 2 lines). */
    val ToolCellWidth: Dp = 75.dp
    val ToolCellHeight: Dp = 52.dp
    val ToolCellGlyph: Dp = 28.dp
    val ToolCellText: TextUnit = 11.sp

    /** More menu: a dropdown 280 wide under the top row. */
    val MoreMenuWidth: Dp = 280.dp

    // ------------------------------------------------------------------ §3.7.7 layer window (382 × 520)

    /** Width min(382, w − 10) at x 5; height min(520, available below the top row − 8). */
    val LayerWindowWidth: Dp = 382.dp
    val LayerWindowHeight: Dp = 520.dp
    val LayerWindowStart: Dp = 5.dp
    val LayerWindowSideRoom: Dp = 10.dp
    val LayerWindowTopRoom: Dp = 8.dp
    val LayerWindowRadius: Dp = 8.dp

    /** Below this screen height the v1.5 side-by-side window is used. */
    val LayerWindowShortScreen: Dp = 480.dp

    /** Header: 46 tall, "Layer" 16 sp, a 40 dp ✕. */
    val LayerHeader: Dp = 46.dp
    val LayerHeaderText: TextUnit = 16.sp
    val LayerClose: Dp = 40.dp

    /** Main block: 360 tall; left pad 8, gaps 6 and 2, right pad 6. */
    val LayerMain: Dp = 360.dp
    val LayerMainPadStart: Dp = 8.dp
    val LayerMainGap1: Dp = 6.dp
    val LayerMainGap2: Dp = 2.dp
    val LayerMainPadEnd: Dp = 6.dp

    /** Left column 100 wide: preview 240 tall, buttons pane 120 tall (3 × 2 cells of 50 × 40). */
    val LayerLeftColumn: Dp = 100.dp
    val LayerPreview: Dp = 240.dp
    val LayerButtonsPane: Dp = 120.dp
    val LayerButtonCellWidth: Dp = 50.dp
    val LayerButtonCellHeight: Dp = 40.dp

    /** List 220 wide; Selection Layer row and layer rows 80 tall. */
    val LayerList: Dp = 220.dp
    val LayerRow: Dp = 80.dp

    /** Row parts: clip gutter 10, thumbnail 62 (2 dp border when active), kind badge 16. */
    val LayerClipGutter: Dp = 10.dp
    val LayerThumb: Dp = 62.dp
    val LayerThumbBorder: Dp = 2.dp
    val LayerBadge: Dp = 16.dp

    /** Mask square 26 visual, 40 touch. */
    val LayerMaskSquare: Dp = 26.dp
    val LayerMaskTouch: Dp = 40.dp

    /** Number and name text. */
    val LayerRowText: TextUnit = 11.sp
    val LayerSelectionRowText: TextUnit = 14.sp

    /** Eye: 28 visual, 40 touch; lock icons 12; drag handle touch 40 × 80. */
    val LayerEye: Dp = 28.dp
    val LayerEyeTouch: Dp = 40.dp
    val LayerLockIcon: Dp = 12.dp
    val LayerDragHandleWidth: Dp = 40.dp

    /** Transparency squares row: 40 tall, four 28 dp squares with 40 × 40 targets, the chosen one with a 2 dp border. */
    val TransparencyRow: Dp = 40.dp
    val TransparencySquare: Dp = 28.dp
    val TransparencyTouch: Dp = 40.dp
    val TransparencyBorder: Dp = 2.dp

    /** Right strip: 40 wide, 9 icons at a 40 dp pitch. */
    val LayerStrip: Dp = 40.dp
    val LayerStripPitch: Dp = 40.dp
    const val LayerStripIcons: Int = 9

    /** Blend row: 56 tall; three 48 dp toggles, then a 36 dp tall dropdown. */
    val BlendRow: Dp = 56.dp
    val BlendToggle: Dp = 48.dp
    val BlendDropdownHeight: Dp = 36.dp

    /** Opacity row: 48 tall; −/+ 22 visual, 40 touch; 22 dp thumb. */
    val LayerOpacityRow: Dp = 48.dp

    /** Bottom pad: 46 + 360 + 56 + 48 + 10 = 520. */
    val LayerBottomPad: Dp = 10.dp

    // ------------------------------------------------------------------ §3.7.8 X / Y pill

    /** Pill: 32 tall, radius 16; contents with 4 dp gaps; about 236 wide. */
    val PillHeight: Dp = 32.dp
    val PillRadius: Dp = 16.dp
    val PillGap: Dp = 4.dp
    val PillWidth: Dp = 236.dp

    /** The ✥ fold cell, 32 × 32. */
    val PillFold: Dp = 32.dp

    /** Each X / Y cell: 28 visual, 40 touch, 72..110 wide, radius 6, 1 dp bevel lines. */
    val PillCellHeight: Dp = 28.dp
    val PillCellTouch: Dp = 40.dp
    val PillCellMinWidth: Dp = 72.dp
    val PillCellMaxWidth: Dp = 110.dp
    val PillCellRadius: Dp = 6.dp
    val PillBevel: Dp = 1.dp

    /** "X" / "Y" prefix 11 sp bold; the value 13 sp. */
    val PillPrefixText: TextUnit = 11.sp
    val PillValueText: TextUnit = 13.sp

    /** The "#" increments cell: 32 × 28 visual, 40 touch. */
    val PillHashWidth: Dp = 32.dp
    val PillHashHeight: Dp = 28.dp

    /** Vertical finger travel that switches a cell drag to fine mode (× 0.1). */
    val PillFineTravel: Dp = 48.dp

    // ------------------------------------------------------------------ §3.7.9 sheets and floating items

    /** Panels: full width, bottom on the bottom bar's top, at most 50 % of the screen (436 on the reference phone). */
    val SheetMaxHeight: Dp = 436.dp
    val SheetHandle: Dp = 16.dp
    val SheetRadius: Dp = 12.dp

    /** ✓ / ✕: 8 dp above the slider rows (or the bottom bar); end 8 while the tool menu is open. */
    val PendingBarGap: Dp = 8.dp

    // ------------------------------------------------------------------ §3.2, §3.3, §3.6 on-canvas sizes

    /** Path: a tap this close to the dashed control polygon inserts a point. */
    val PathInsertDistance: Dp = 24.dp

    /** Path control points: hollow circles of this diameter; the control polygon line width. */
    val PathPoint: Dp = 12.dp
    val PathPolygonLine: Dp = 1.dp

    /** Curve Handles group: the mini "Handle scale" slider width. */
    val HandleScaleSlider: Dp = 120.dp

    /** A pinch scales the selected point's handles when a finger starts this close to the point or a handle end. */
    val HandlePinchDistance: Dp = 40.dp

    /** Text frames: smallest frame on screen, the out-port square, thread lines. */
    val FrameMinSize: Dp = 32.dp
    val FramePort: Dp = 14.dp
    val ThreadLine: Dp = 1.5.dp
}
