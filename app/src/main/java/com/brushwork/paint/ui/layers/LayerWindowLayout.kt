package com.brushwork.paint.ui.layers

import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.ui.theme.IbisDims

/**
 * Sizes (dp) of every part of the ibisPaint layer window (design §3.7.7) for the size its host
 * gives it. The window FILLS that size (the v1.6 sizing contract): at ibisPaint's 382 × 520 every
 * part has its measured size (header 46, main block 360, blend row 56, opacity row 48, bottom pad
 * 10; left column 100 = preview 240 + buttons 120; list 220; strip 40). Pure, JVM-tested.
 *
 * Other sizes degrade without ever shrinking a touch target below 40 dp:
 * - a taller window grows the main block (the preview and the list get the room);
 * - a shorter one shrinks the preview (hidden below [MIN_PREVIEW]) and lets the 9-icon strip
 *   scroll when it no longer fits;
 * - a narrower one (the list would be under [MIN_FULL_LIST]) drops the preview and stacks the six
 *   left buttons in one 50 dp column ([compact]), so the list keeps room for its rows;
 * - a short, wide one puts the list beside a scrolling column of controls ([sideBySide], the v1.5
 *   fallback): on a short screen (under 480 dp tall, a phone in landscape; design §3.7.7) or in a
 *   window under [SIDE_BY_SIDE_HEIGHT], when it is at least [SIDE_BY_SIDE_MIN_WIDTH] wide.
 */
data class LayerWindowMetrics(
    val width: Float,
    val height: Float,
    /** Short, wide window: the list beside a scrolling controls column. */
    val sideBySide: Boolean,
    /** Narrow window: one 50 dp column of left buttons and no preview. */
    val compact: Boolean,
    val header: Float,
    /** The main block (left column, list, strip); in [sideBySide], everything under the header. */
    val main: Float,
    val blendRow: Float,
    val opacityRow: Float,
    val bottomPad: Float,
    val padStart: Float,
    val gap1: Float,
    val gap2: Float,
    val padEnd: Float,
    val leftColumn: Float,
    /** Columns of left buttons (2 as ibisPaint, 1 when [compact]). */
    val leftColumns: Int,
    /** Height of the canvas preview, 0 = hidden. */
    val preview: Float,
    /** Height of the left buttons pane. */
    val buttonsPane: Float,
    val list: Float,
    val strip: Float,
    /** Height of a layer row and of the Selection Layer row. */
    val row: Float,
    val thumb: Float,
    val transparencyRow: Float,
    /** Width of the controls column in [sideBySide] (0 otherwise). */
    val controls: Float,
    /** [sideBySide] with a narrow controls column: the blend dropdown has a line of its own ([blendRow] is then both lines). */
    val blendWraps: Boolean = false,
) {
    /** Buttons per row of the side-by-side controls' grid (every cell at least 40 dp wide). */
    val gridColumns: Int get() = if (controls >= 5 * MIN_CELL) 5 else 4

    /** The strip's 9 icons don't fit the main block: it scrolls (each target stays 40 dp). */
    val stripScrolls: Boolean get() = !sideBySide && main < IbisDims.LayerStripIcons * IbisDims.LayerStripPitch.value

    /** The left buttons don't fit their pane: it scrolls. */
    val buttonsScroll: Boolean get() = !sideBySide && buttonsPane < (6 / leftColumns) * IbisDims.LayerButtonCellHeight.value

    /** A row is tall enough for the number, the eye line and the name on separate lines. */
    val tallRows: Boolean get() = row >= TALL_ROW

    companion object {
        /** Below this window height (and at least [SIDE_BY_SIDE_MIN_WIDTH] wide): side by side. */
        const val SIDE_BY_SIDE_HEIGHT = 300f
        const val SIDE_BY_SIDE_MIN_WIDTH = 360f

        /** The full ibisPaint layout needs at least this much list width; below it, [compact]. */
        const val MIN_FULL_LIST = 180f

        /** A preview shorter than this is not worth showing. */
        const val MIN_PREVIEW = 48f

        /** The main block never gets less than this (the window then clips at the bottom). */
        const val MIN_MAIN = 120f

        /** Rows at least this tall stack number, eye line and name (80 in ibisPaint). */
        const val TALL_ROW = 72f

        /**
         * The room a row keeps beside its thumbnail: the gutter (4, or 10 with the clip
         * bracket), the 40 dp eye (or the eye stacked over the mask square), at least 44 dp for
         * "100%" / "Normal" with their pad, the 40 dp ≡. A narrower list shrinks the thumbnail
         * (62 at ibisPaint's 220 dp list, 48 at a 360 dp phone's 188, at least [SMALL_THUMB])
         * before it squeezes the values.
         */
        const val ROW_ROOM_BESIDE_THUMB = 140f

        const val SMALL_THUMB = 44f
        const val SIDE_ROW = 56f

        /**
         * A side-by-side list at least this wide keeps [SIDE_ROW] rows: a masked row then has its
         * mask square, eye and values in line (4 + 44 + 40 + 4 + 40 + 48 + 40). A narrower one
         * gets 80 dp rows, where the eye stacks over the mask square.
         */
        const val SIDE_LIST_FOR_SHORT_ROWS = 220f

        /** The side-by-side controls column: half the window, within these bounds. */
        const val CONTROLS_WIDTH = 236f
        const val CONTROLS_MAX_WIDTH = 280f

        /**
         * A side-by-side list keeps at least this much (gutter 4, thumbnail 44, the eye over the
         * mask square 40, 4 + 44 for "100%" / "Normal", ≡ 40): a narrower window takes it from
         * the controls column, down to [CONTROLS_NARROW_WIDTH], whose blend dropdown then wraps.
         */
        const val MIN_SIDE_LIST = 176f
        const val CONTROLS_NARROW_WIDTH = 180f

        /** The wrapped blend row: the toggles' 56 dp line, the dropdown's 40 dp target, a 4 dp pad. */
        const val BLEND_WRAPPED = 100f

        /** The smallest grid cell of the side-by-side controls. */
        const val MIN_CELL = 40f
        const val COMPACT_PAD = 4f
        const val COMPACT_LEFT = 50f

        /**
         * The metrics of a window of [width] × [height] dp; [shortScreen]: the screen itself is
         * short (under 480 dp tall, [LayerListMath.isShortScreen]), which asks for the side-by-side
         * layout whenever the window is wide enough for it.
         */
        fun of(width: Float, height: Float, shortScreen: Boolean = false): LayerWindowMetrics {
            val w = if (width.isFinite()) width.coerceAtLeast(0f) else 0f
            val h = if (height.isFinite()) height.coerceAtLeast(0f) else 0f
            val header = IbisDims.LayerHeader.value
            val blend = IbisDims.BlendRow.value
            val opacity = IbisDims.LayerOpacityRow.value
            val pad = IbisDims.LayerBottomPad.value
            val strip = IbisDims.LayerStrip.value
            val transparency = IbisDims.TransparencyRow.value
            if ((shortScreen || h < SIDE_BY_SIDE_HEIGHT) && w >= SIDE_BY_SIDE_MIN_WIDTH) {
                // Half the window for the controls (the blend dropdown then shows "Normal" whole
                // in a 520 dp landscape window), the rest for the list; a narrow window keeps the
                // list's minimum and narrows the controls (their dropdown wraps).
                val half = (w / 2f).coerceIn(CONTROLS_WIDTH, CONTROLS_MAX_WIDTH)
                val controls = minOf(half, w - 3 * COMPACT_PAD - MIN_SIDE_LIST).coerceAtLeast(CONTROLS_NARROW_WIDTH)
                val wraps = controls < CONTROLS_WIDTH
                val list = (w - controls - 3 * COMPACT_PAD).coerceAtLeast(0f)
                val shortRows = list >= SIDE_LIST_FOR_SHORT_ROWS
                return LayerWindowMetrics(
                    width = w, height = h, sideBySide = true, compact = true,
                    header = header, main = (h - header).coerceAtLeast(0f), blendRow = if (wraps) BLEND_WRAPPED else blend,
                    opacityRow = opacity, bottomPad = COMPACT_PAD,
                    padStart = COMPACT_PAD, gap1 = COMPACT_PAD, gap2 = 0f, padEnd = COMPACT_PAD,
                    leftColumn = 0f, leftColumns = 0, preview = 0f, buttonsPane = 0f,
                    list = list, strip = 0f,
                    row = if (shortRows) SIDE_ROW else IbisDims.LayerRow.value,
                    thumb = if (shortRows) SMALL_THUMB else thumbFor(list),
                    transparencyRow = transparency,
                    controls = controls,
                    blendWraps = wraps,
                )
            }
            val main = (h - header - blend - opacity - pad).coerceAtLeast(MIN_MAIN)
            val padStart = IbisDims.LayerMainPadStart.value
            val gap1 = IbisDims.LayerMainGap1.value
            val gap2 = IbisDims.LayerMainGap2.value
            val padEnd = IbisDims.LayerMainPadEnd.value
            val left = IbisDims.LayerLeftColumn.value
            val fullList = w - padStart - gap1 - gap2 - padEnd - left - strip
            val row = IbisDims.LayerRow.value
            if (fullList >= MIN_FULL_LIST) {
                val buttons = IbisDims.LayerButtonsPane.value
                val preview = (main - buttons).let { if (it >= MIN_PREVIEW) it else 0f }
                return LayerWindowMetrics(
                    width = w, height = h, sideBySide = false, compact = false,
                    header = header, main = main, blendRow = blend, opacityRow = opacity, bottomPad = pad,
                    padStart = padStart, gap1 = gap1, gap2 = gap2, padEnd = padEnd,
                    leftColumn = left, leftColumns = 2, preview = preview, buttonsPane = minOf(buttons, main),
                    list = fullList, strip = strip, row = row, thumb = thumbFor(fullList), transparencyRow = transparency,
                    controls = 0f,
                )
            }
            val list = (w - 3 * COMPACT_PAD - gap2 - COMPACT_LEFT - strip).coerceAtLeast(0f)
            return LayerWindowMetrics(
                width = w, height = h, sideBySide = false, compact = true,
                header = header, main = main, blendRow = blend, opacityRow = opacity, bottomPad = pad,
                padStart = COMPACT_PAD, gap1 = COMPACT_PAD, gap2 = gap2, padEnd = COMPACT_PAD,
                leftColumn = COMPACT_LEFT, leftColumns = 1, preview = 0f, buttonsPane = main,
                list = list, strip = strip, row = row, thumb = thumbFor(list), transparencyRow = transparency,
                controls = 0f,
            )
        }

        /** The thumbnail of a list [list] dp wide: 62 while the row keeps [ROW_ROOM_BESIDE_THUMB] beside it, smaller (≥ [SMALL_THUMB]) below. */
        fun thumbFor(list: Float): Float = (list - ROW_ROOM_BESIDE_THUMB).coerceIn(SMALL_THUMB, IbisDims.LayerThumb.value)
    }

    /**
     * v1.7 (§3.8): the thumbnail of a row [depth] folders deep. Its indent comes out of the
     * thumbnail first (down to [SMALL_THUMB]) so the name, the values and every badge keep their
     * room at 392 dp: [thumb] at the top level, and wherever the list is wide enough.
     */
    fun thumbAt(depth: Int): Float {
        if (depth <= 0) return thumb
        return (list - ROW_ROOM_BESIDE_THUMB - LayerTreeRows.indent(depth)).coerceIn(SMALL_THUMB, maxOf(thumb, SMALL_THUMB))
    }
}

/**
 * Accessibility labels of the layer window (I10, design §3.7.7 / §3.7.11): every clickable's
 * label is unique among the clickables shown together (per-row labels carry the layer number N,
 * counted from the bottom as the bottom bar's "active layer N"). The v1.5 labels the existing
 * tests drive stay on their new controls.
 */
object LayerLabels {
    const val PANE = "Layers"
    const val TITLE = "Layer"
    const val CLOSE = "Close layers"

    // Left column
    const val ADD = "Add layer"
    const val ADD_LONG = "Special layers"
    const val DUPLICATE = "Duplicate layer"
    const val DUPLICATE_SELECTION = "Duplicate layer (selected pixels only)"
    const val FLIP_CANVAS_H = "Flip canvas horizontally"
    const val FLIP_CANVAS_V = "Flip canvas vertically"
    const val IMPORT = "Import picture"
    const val SPECIAL = "New special layer"
    const val NEW_VECTOR = "New vector layer"
    const val NEW_ADJUSTMENT = "New adjustment layer (Tone)"

    // List
    const val SELECTION_ROW = "Selection Layer"
    const val NO_SELECTION = "No Selection"
    fun selectRow(n: Int) = "Select layer $n"
    fun hide(n: Int) = "Hide layer $n"
    fun show(n: Int) = "Show layer $n"
    fun reorder(n: Int) = "Reorder layer $n"
    fun moveUp(n: Int) = "Move layer $n up"
    fun moveDown(n: Int) = "Move layer $n down"
    fun editMask(n: Int) = "Edit mask of layer $n"
    fun editContent(n: Int) = "Edit content of layer $n"
    fun maskOf(n: Int) = "Mask of layer $n"
    fun transparency(t: TransparencyDisplay) = "Transparency: ${t.label}"

    /**
     * What a row's values say, as ONE description that names the row (so no two rows, nor a row
     * and the blend dropdown, share a label; I10): "Layer 2: 100%, Normal", then the adjustment's
     * effect and the locks ("Layer 3: 60%, Multiply, Tone, locked, alpha locked"). v1.7: then
     * what a folder above passes on (", in a locked folder", ", in a hidden folder").
     */
    fun rowState(
        n: Int,
        percent: Int,
        blend: String,
        effect: String? = null,
        locked: Boolean = false,
        alphaLocked: Boolean = false,
        inLockedFolder: Boolean = false,
        inHiddenFolder: Boolean = false,
    ): String =
        buildString {
            append("Layer ").append(n).append(": ").append(percent).append("%, ").append(blend)
            if (effect != null) append(", ").append(effect)
            if (locked) append(", locked")
            if (alphaLocked) append(", alpha locked")
            if (inLockedFolder) append(", in a locked folder")
            if (inHiddenFolder) append(", in a hidden folder")
        }

    // Badges (content descriptions): the v1.5 kind names, spoken with the row's number so that
    // two layers of one kind (two text layers, two frames "1 of 1" of two stories) never share a
    // label (I10): "Layer 3: text layer", "Layer 2: text frame 1 of 2", "Editable mask of layer 4".
    const val TEXT_BADGE = "Text layer"
    const val SHAPE_BADGE = "Shape layer"
    const val VECTOR_BADGE = "Vector layer"
    const val ADJUSTMENT_BADGE = "Adjustment layer"
    const val SPEC_MASK_BADGE = "Editable mask"

    /** The kind badge of row [n]: "Layer 3: text layer" for [kind] [TEXT_BADGE]. */
    fun badge(n: Int, kind: String) = "Layer $n: ${kind.replaceFirstChar { it.lowercase() }}"

    /** The ⛓ badge of row [n]: "Layer 2: text frame 1 of 2" (", more text than fits"). */
    fun frameBadge(n: Int, frame: FrameBadge) = badge(n, frame.description)

    /** The badge on row [n]'s editable (spec) mask square: "Editable mask of layer 4". */
    fun specMaskBadge(n: Int) = "$SPEC_MASK_BADGE of layer $n"

    // Right strip
    const val CLEAR = "Clear layer"
    const val CLEAR_MASK = "Clear layer mask"
    const val MASK = "Layer mask"
    const val TRANSFORM = "Transform layer"
    const val FLIP_H = "Flip layer horizontally"
    const val FLIP_V = "Flip layer vertically"
    const val MERGE = "Merge down"
    const val APPLY_BELOW = "Apply to layer below"
    const val DELETE = "Delete layer"
    const val FILTERS = "Filters for this layer"
    const val MORE = "More layer actions"

    /** The ⋮ menu entry that opens its mask page (the strip's "Layer mask" opens it too). */
    const val MASK_ACTIONS = "Mask actions"

    // Blend row: the toggles keep their v1.5 captions as visible text.
    const val CLIPPING = "Clipping"
    const val ALPHA_LOCK = "Alpha lock"
    const val ALPHA_LOCK_CAPTION = "α lock"
    const val LOCK = "Lock layer"
    const val LOCK_CAPTION = "Lock"
    const val BLEND = "Choose blend mode"

    // Opacity row
    const val OPACITY = "Layer opacity"
    const val TYPE_OPACITY = "Type layer opacity"
    const val LESS_OPACITY = "Less layer opacity"
    const val MORE_OPACITY = "More layer opacity"
}

/** Test tags of the window's parts (layout tests measure them). */
object LayerWindowTags {
    const val HEADER = "layers.header"
    const val MAIN = "layers.main"
    const val LEFT = "layers.left"
    const val PREVIEW = "layers.preview"
    /** The canvas picture inside the preview pane. */
    const val PREVIEW_PICTURE = "layers.preview.picture"
    const val BUTTONS = "layers.buttons"
    const val LIST = "layers.list"
    const val ROWS = "layers.rows"
    const val TRANSPARENCY = "layers.transparency"
    const val STRIP = "layers.strip"
    const val BLEND = "layers.blend"
    const val OPACITY = "layers.opacity"
    const val CONTROLS = "layers.controls"
    const val SELECTION_ROW = "layers.selectionRow"
    fun row(id: Long) = "layers.row.$id"
    fun thumb(id: Long) = "layers.thumb.$id"
    /** A row's "100%" / "Normal" column. */
    fun values(id: Long) = "layers.values.$id"
    /** A row's layer number, in the room its badges leave it. */
    fun number(id: Long) = "layers.number.$id"
    /** A row's number-line badge: [BADGE_MASK], [BADGE_ALPHA] or [BADGE_LOCK]. */
    fun badge(id: Long, kind: String) = "layers.badge.$kind.$id"
    const val BADGE_MASK = "mask"
    const val BADGE_ALPHA = "alpha"
    const val BADGE_LOCK = "lock"

    /** v1.7: the dimmed lock of a layer inside a locked folder. */
    const val BADGE_FOLDER_LOCK = "folderLock"
}
