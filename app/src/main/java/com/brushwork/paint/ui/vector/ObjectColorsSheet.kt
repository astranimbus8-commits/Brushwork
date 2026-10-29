package com.brushwork.paint.ui.vector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.InToolHistoryOwner
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.color.ColorDialogActions
import com.brushwork.paint.ui.color.ColorDialogBody
import com.brushwork.paint.ui.color.ColorDialogModel
import com.brushwork.paint.ui.color.rememberColorDialogModel
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.IconLongPressScope
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.PaintModeSegments
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.common.iconLongPressLabel
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v1.10 (R2, R3, R7; design 4.4 A2): the [ObjectColorLabels110.CELL] sheet the pill's COLOR cell opens on the
 * object open in the Shape, Curve, Path, Polyline or Text tool ([editor]).
 *
 * A half-height see-through [BwSheet] like `ColorPickerDialog`, titled [ObjectColorLabels110.CELL],
 * with ONE row of [OBJECT_COLORS_ROW] above the color body:
 * - the target tabs, icons showing each target's current color (a disc; a sweep for a kept
 *   gradient, the checker with a slash for none): [ObjectColorLabels110.FILL] and
 *   [ObjectColorLabels110.STROKE], or [ObjectColorLabels110.TEXT] and [ObjectColorLabels110.OUTLINE].
 *   Long-press names and content descriptions only; tags `V110Tags.COLORS_SHEET_TAB.<TARGET>`;
 * - while the Fill (Outline) tab is selected, the "none" button ([ObjectColorLabels110.NO_FILL],
 *   [ObjectColorLabels110.NO_OUTLINE]; [ObjectColorEditor.setNone]: no fill is paint mode Stroke,
 *   no outline is width 0). It sits in the row, not above the body, so the body keeps its room;
 * - for a shape or a path, the three [PaintModeSegments] glyphs at the row's end (R3: the glyphs and
 *   names of every other paint-mode control; tags `V110Tags.COLORS_SHEET_MODE.<MODE>`), with their
 *   refusal so that Fill and Both, refused with the fill, say why on a tap.
 *
 * The selected tab drives the embedded [ColorDialogBody] (`extraAbove` = the row, so the wheel and
 * OK fit without scrolling), whose color goes to the object live ([ObjectColorEditor.preview], R7).
 * Each tab has its own picker state, started from the object's color when the sheet opened. A
 * target the editor refuses now ([ObjectColorEditor.unavailable], a curve's fill with fewer than 3
 * points) is dimmed; a tap on it shows the reason ([ObjectColorExtras.toast]); the sheet opens on
 * the first target that is not refused and leaves a target refused meanwhile.
 *
 * Sheet contract (R7, J21): OK keeps the previewed colors ([ObjectColorEditor.commit]; no undo step
 * of its own, the object's ✓ carries them); Cancel, Back and the host's close restore the colors the
 * sheet opened with ([ObjectColorEditor.cancel], every tab at once). Inside the editor a swipe or a
 * touch outside the sheet only minimizes it (SheetHost) and keeps the preview. When the object's
 * session ends, the caller drops this call: the tool's ✓ keeps the colors inside its one step, ✕
 * restores them with everything else. Undo while the sheet is open or minimized first closes it
 * with Cancel, then undoes as usual; when the colors were the object's only change, it closes the
 * object as it was opened and undoes no document step ([ObjectColorSheetState]).
 */
@Composable
internal fun ObjectColorsSheet(editor: ObjectColorEditor, onClose: () -> Unit) {
    val targets = editor.targets
    if (targets.isEmpty()) return
    // The start values (and the restore point of the main color) are taken once per opening,
    // before any preview (previews are delivered from the next frame on). An opening that went
    // away unanswered (the pill folded, the interface hidden) left them in place: A2's editors
    // keep them for the rest of the object's session, so Cancel restores what it found.
    DisposableEffect(editor) {
        editor.begin()
        onDispose { }
    }
    // The sheet opens on the first target the editor takes now (a curve with 1 or 2 points: its
    // Stroke, as its Fill is refused) and never stays on a target that is refused meanwhile.
    var chosen by rememberSaveable(editor) { mutableStateOf(firstAvailable(editor, targets)) }
    val target = chosen.takeIf { it in targets && editor.unavailable(it) == null } ?: firstAvailable(editor, targets)
    if (target != chosen) SideEffect { chosen = target }
    val session = remember(editor) { SheetSession() }
    // One picker state per tab, each started from that target's color as the sheet opened.
    val models = HashMap<ObjectColorTarget, ColorDialogModel>()
    for (t in targets) key(t) {
        val start = remember(editor) { startColorOf(editor, t) }
        models[t] = rememberColorDialogModel(start, showAlpha = true)
    }
    val model = models.getValue(target)
    val cancel: () -> Unit = {
        if (session.open) {
            session.open = false
            models.values.forEach { it.closed = true }
            editor.cancel()
            onClose()
        }
    }
    BwSheet(
        title = ObjectColorLabels110.CELL,
        onDismiss = cancel,
        showClose = false,
        actions = {
            ColorDialogActions(
                model,
                onPick = { c ->
                    // A color OK takes in the same frame as its change was not delivered yet.
                    val last = session.delivered[target]
                    if (session.open && (if (last == null) c != model.start else c != last)) editor.preview(target, c)
                    session.open = false
                    models.values.forEach { it.closed = true }
                    editor.commit()
                },
                onDismiss = onClose,
                onCancel = cancel,
            )
        },
    ) {
        ObjectColorsRow(editor, targets, target) { t ->
            val reason = editor.unavailable(t)
            if (reason != null) (editor as? ObjectColorExtras)?.toast(reason) else chosen = t
        }
        key(target) {
            ColorDialogBody(
                model,
                onPreview = { c ->
                    // A tab shown again delivers its picker's color once more (the body's watch
                    // starts over): the object already had it, and since then the none button
                    // ([setNone][ObjectColorEditor.setNone]) or a paint-mode glyph may have
                    // changed it; only a new color goes.
                    if (session.open && session.delivered[target] != c) {
                        session.delivered[target] = c
                        editor.preview(target, c)
                    }
                },
                extraAbove = OBJECT_COLORS_ROW,
            )
        }
    }
}

/** The row above the color body: one tab per target, the "none" button, the paint-mode segments. */
@Composable
private fun ObjectColorsRow(
    editor: ObjectColorEditor,
    targets: List<ObjectColorTarget>,
    target: ObjectColorTarget,
    onTab: (ObjectColorTarget) -> Unit,
) {
    IconLongPressScope {
        Row(Modifier.fillMaxWidth().height(OBJECT_COLORS_ROW), verticalAlignment = Alignment.CenterVertically) {
            for (t in targets) {
                TargetTab(t, editor.face(t), selected = t == target, available = editor.unavailable(t) == null) { onTab(t) }
            }
            val none = noneLabel(target)
            if (none != null) {
                NoneButton(none, on = editor.face(target) == ObjectColorFace.None) { editor.setNone(target) }
            }
            Spacer(Modifier.weight(1f))
            if (ObjectColorTarget.FILL in targets && ObjectColorTarget.STROKE in targets) {
                // The PaintModeSegments glyphs; Fill and Both are refused while the fill is (a curve
                // with 1 or 2 points): greyed, and a tap says why (R2: the FILL_NEEDS_3 toast).
                val fillRefused = editor.unavailable(ObjectColorTarget.FILL)
                PaintModeSegments(
                    selected = editor.paintMode,
                    tagPrefix = V110Tags.COLORS_SHEET_MODE,
                    refusal = { m -> if (m != PaintMode.STROKE) fillRefused else null },
                    onRefused = { why -> (editor as? ObjectColorExtras)?.toast(why) },
                    onSelect = { m -> editor.setPaintMode(m) },
                )
            }
        }
    }
}

/** One target tab: [face] as a swatch, known by the target's name; dimmed while not [available]. */
@Composable
private fun TargetTab(t: ObjectColorTarget, face: ObjectColorFace, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    val name = objectColorName(t)
    val state = when (face) {
        is ObjectColorFace.Gradient -> GRADIENT_KEPT
        ObjectColorFace.None -> noneLabel(t)
        is ObjectColorFace.Solid -> null
    }
    Box(
        Modifier
            .size(OBJECT_COLORS_ROW)
            .background(if (selected) BrushworkColors.AccentDim else Color.Transparent, tabShape)
            .testTag("${V110Tags.COLORS_SHEET_TAB}.${t.name}")
            .iconLongPressLabel(name)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics {
                contentDescription = name
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        ObjectColorSwatch(face, Modifier.alpha(if (available) 1f else 0.38f), size = 28.dp)
    }
}

/** The 40 dp "none" button of the Fill (Outline) tab, highlighted while the target is none. */
@Composable
private fun NoneButton(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .background(if (on) BrushworkColors.AccentDim else Color.Transparent, tabShape)
            .iconLongPressLabel(label)
            .selectable(selected = on, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.FormatColorReset, contentDescription = null, tint = if (on) Color.White else BrushworkColors.OnChrome, modifier = Modifier.size(22.dp))
    }
}

/**
 * The round swatch of an object color: a disc of the color over the checker (translucent colors
 * show it), a sweep of the stops for a kept gradient, the checker with a slash for none. A 1 dp
 * light outer ring and a 1 dp dark inner ring frame it, so black and dark-navy colors stay visible
 * on the dark chrome (as the bottom bar's color square).
 */
@Composable
internal fun ObjectColorSwatch(face: ObjectColorFace, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(50)).checkerboard(size / 5)) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val center = Offset(r, r)
            val ring = 1.dp.toPx()
            when (face) {
                is ObjectColorFace.Solid -> drawCircle(Color(face.argb), r, center)
                is ObjectColorFace.Gradient -> {
                    val stops = face.stops.map { Color(it) }
                    if (stops.size >= 2) drawCircle(Brush.sweepGradient(stops + stops.first(), center), r, center)
                    else if (stops.size == 1) drawCircle(stops[0], r, center)
                }
                ObjectColorFace.None -> drawLine(SLASH, Offset(r * 0.4f, r * 1.6f), Offset(r * 1.6f, r * 0.4f), strokeWidth = 2.dp.toPx())
            }
            drawCircle(FRAME_LIGHT, r - ring / 2f, center, style = Stroke(ring))
            drawCircle(FRAME_DARK, r - ring * 1.5f, center, style = Stroke(ring))
        }
    }
}

/**
 * A2's own editors (`ShapeColorsDelegate`, `CurveColorsDelegate`, `TextColorsDelegate`): what the
 * sheet uses beyond [ObjectColorEditor].
 */
internal interface ObjectColorExtras {
    /** The color [t] holds even while it is not painted (no fill, no outline): its picker starts there. */
    fun colorOf(t: ObjectColorTarget): Int

    /** Shows [text] the way the tool shows its messages (the reason a target is refused). */
    fun toast(text: String)

    /** Whether the pill's sheet is open on this editor's object (the pill's `PillColorCell`). */
    val sheet: ObjectColorSheetState
}

/**
 * v1.10 (design 4.4 A2 step 6): whether the pill's [ObjectColorLabels110.CELL] sheet is open, kept
 * by the editor rather than by the pill's cell. The pill composes the cell in another place when
 * it folds and drops it while the interface is hidden or the eraser is on; the sheet outlives that
 * (it shows again with its preview, and its Cancel still restores what its first opening found:
 * the editors keep their start values for the object's session).
 *
 * It is open for one object: [session] is the editor's object session (Compose state reads, null
 * while no object is open; another value is another object). It is never open on another object,
 * and the end of its object's session forgets it (a frame later), so an object opened again (the
 * same text) starts with the sheet closed.
 *
 * Undo (R2, J21): while the sheet is open or minimized, the app's Undo first closes it with Cancel
 * ([cancel], the editor's: the colors it opened with), then undoes as usual; when the colors were
 * the object's only change, that Undo closes the object as it was opened and goes no further (no
 * document step: the colors were what the user changed). For that the state is
 * [controller]'s `inToolHistory` while open ([UndoFirst]), handing every call on to the owner it
 * found there (a session that owns Undo keeps it); a session that takes `inToolHistory` over later
 * (a filter, a text path's nodes) simply goes first. Without [controller] (a stand-in editor) there
 * is no such hook.
 */
@Stable
internal class ObjectColorSheetState(
    private val scope: CoroutineScope,
    private val controller: EditorController? = null,
    private val cancel: () -> Unit = {},
    private val session: () -> Any?,
) {
    private var openFor: Any? by mutableStateOf(null)
    private var watch: Job? = null
    private var undoFirst: UndoFirst? = null

    /** The object open now (Compose state reads), or null. */
    val current: Any? get() = session()

    /** The sheet is open on the object open now (Compose state). */
    val isOpen: Boolean
        get() {
            val s = openFor ?: return false
            return s == session()
        }

    /** Opens the sheet on the object open now (nothing while none is). */
    fun open() {
        val s = session() ?: return
        openFor = s
        takeUndo()
        watch?.cancel()
        watch = scope.launch {
            snapshotFlow { session() }.first { it != s }
            if (openFor == s) {
                openFor = null
                giveUndoBack()
            }
        }
    }

    /** Closes the sheet (its OK, Cancel or Back). */
    fun close() {
        openFor = null
        watch?.cancel()
        watch = null
        giveUndoBack()
    }

    private fun takeUndo() {
        val c = controller ?: return
        if (undoFirst != null && c.inToolHistory === undoFirst) return
        undoFirst = UndoFirst(c.inToolHistory).also { c.inToolHistory = it }
    }

    /** Puts back the owner found at the opening, unless another one took over meanwhile. */
    private fun giveUndoBack() {
        val c = controller ?: return
        val u = undoFirst ?: return
        undoFirst = null
        if (c.inToolHistory === u) {
            c.inToolHistory = u.next
            // (The owner found may be the wait guard of renders that landed meanwhile.)
            c.refreshDeferredWaitGuard()
        }
    }

    /**
     * Undo's first stop while the sheet is open: its Cancel, then [next] (or the usual Undo).
     *
     * When the previewed colors were the open object's only change, the usual Undo would find an
     * untouched reopened object after the Cancel, close it and undo the document's step before it
     * too (v1.9 R17: an untouched object does not swallow an Undo). The colors were the user's
     * change, so Undo stops there instead, as it does for colors kept by OK (design 4.4 A2 J21: at
     * the bottom of the object's steps Undo closes it without its colors and undoes no document
     * step): the object closes as it was opened, and that is the whole Undo.
     */
    private inner class UndoFirst(val next: InToolHistoryOwner?) : InToolHistoryOwner {
        override val canUndo: Boolean get() = next?.canUndo == true
        override val canRedo: Boolean get() = next?.canRedo == true
        override val undoLabel: String? get() = next?.undoLabel
        override val redoLabel: String? get() = next?.redoLabel

        override fun undoStep(): Boolean {
            val n = next
            if (!isOpen) {
                giveUndoBack()
                return n?.undoStep() == true
            }
            val c = controller
            val tool = c?.currentTool
            val changed = tool != null && tool.hasPendingWork && tool.hasUserChanges
            cancel()
            close()
            if (n?.undoStep() == true) return true
            if (c == null || tool == null || !changed || c.currentTool !== tool) return false
            if (!tool.hasPendingWork || tool.hasUserChanges) return false
            // The Cancel took back the object's only change: close it as the usual Undo closes an
            // object at the bottom of its steps (EditorController.undo), without a document step.
            c.flushDeferredSteps()
            c.landSavedSelections()
            c.settleVectorWork()
            tool.discard()
            c.invalidateOverlay()
            return true
        }

        override fun redoStep(): Boolean = next?.redoStep() == true
    }
}

/** The name of [t]: its tab's long-press name and content description. */
internal fun objectColorName(t: ObjectColorTarget): String = when (t) {
    ObjectColorTarget.FILL -> ObjectColorLabels110.FILL
    ObjectColorTarget.STROKE -> ObjectColorLabels110.STROKE
    ObjectColorTarget.TEXT -> ObjectColorLabels110.TEXT
    ObjectColorTarget.OUTLINE -> ObjectColorLabels110.OUTLINE
}

/** The first of [targets] that [editor] takes now (none refused by `unavailable`), else the first. */
private fun firstAvailable(editor: ObjectColorEditor, targets: List<ObjectColorTarget>): ObjectColorTarget =
    targets.firstOrNull { editor.unavailable(it) == null } ?: targets.first()

/** The "none" of [t] (Fill: no fill, Outline: no outline), or null when [t] has none. */
private fun noneLabel(t: ObjectColorTarget): String? = when (t) {
    ObjectColorTarget.FILL -> ObjectColorLabels110.NO_FILL
    ObjectColorTarget.OUTLINE -> ObjectColorLabels110.NO_OUTLINE
    else -> null
}

/**
 * The color [t]'s picker starts from: its solid color, a kept gradient's first stop, or for none
 * the color the target keeps ([ObjectColorExtras.colorOf]; another editor: the other target's).
 */
internal fun startColorOf(editor: ObjectColorEditor, t: ObjectColorTarget): Int = when (val f = editor.face(t)) {
    is ObjectColorFace.Solid -> f.argb
    is ObjectColorFace.Gradient -> f.stops.firstOrNull() ?: OPAQUE_BLACK
    ObjectColorFace.None -> (editor as? ObjectColorExtras)?.colorOf(t)
        ?: editor.targets.firstNotNullOfOrNull { o -> (editor.face(o) as? ObjectColorFace.Solid)?.argb } ?: OPAQUE_BLACK
}

/** One opening of the sheet: still open (OK and Cancel end it), and the last color delivered per tab. */
private class SheetSession {
    var open = true
    val delivered = HashMap<ObjectColorTarget, Int>()
}

/** The height of the sheet's row above the color body (design: 48 dp; `ColorDialogBody`'s `extraAbove`). */
internal val OBJECT_COLORS_ROW: Dp = 48.dp

private val tabShape = RoundedCornerShape(8.dp)

/** The swatches' 1 dp light outer frame ring (the sheet's tabs, the pill's COLOR cell). */
internal val FRAME_LIGHT = Color(0xFFE8EAED)

/** The swatches' 1 dp dark inner frame ring. */
internal val FRAME_DARK = Color(0xFF202124)

/** The slash across a "none" swatch (no fill, no outline). */
internal val SLASH = Color(0xFFD93025)
private const val OPAQUE_BLACK = 0xFF000000.toInt()
