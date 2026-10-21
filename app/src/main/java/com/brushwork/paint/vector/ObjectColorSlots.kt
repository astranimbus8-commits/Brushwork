package com.brushwork.paint.vector

import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextLayerData
import com.brushwork.paint.tools.vector.ShapeObject

/**
 * v1.10 (I35, C2): the color slots of an object. [STROKE] is a stroke's color, a path's stroke and
 * a shape's outline; [FILL] a path's or shape's fill; [TEXT], [OUTLINE], [BOX_FILL] and
 * [BOX_BORDER] are a text's color, outline color and box colors.
 */
enum class ColorSlot { STROKE, FILL, TEXT, OUTLINE, BOX_FILL, BOX_BORDER }

/**
 * v1.10 (I35, C2, C4; complete in F0): every per-object color change (the Object colors sheet R2,
 * the vector recolor R5, Change Drawing Color on objects R6, the live previews R7) reads and writes
 * colors through this walker. Pure data: no tool state, no undo, no drawing color.
 *
 * Slots per object (R6):
 * - [VStroke]: [ColorSlot.STROKE] (its color).
 * - [VPath]: [ColorSlot.FILL] while it has a fill (Solid; a Linear or Radial gradient reads as null
 *   and maps every stop), [ColorSlot.STROKE] while it has a stroke.
 * - [VShape]: [ColorSlot.STROKE] and, for closed shapes, [ColorSlot.FILL]. Both color fields are
 *   kept whatever the shape style is (a stroke-only shape keeps the fill it shows when filled).
 * - [TextItem]: color, outline color, box fill and box border (UQ7), whether drawn or not.
 *
 * The fill-follows rule (C4): `ShapeObject.fillFollowsColor` says that a reopened shape's fill
 * follows the main color. [set] of the stroke pins the fill: the fill keeps its current color and
 * follows only while it equals the new stroke color; [set] of the fill sets it and follows when it
 * equals the stroke color; a map keeps `fillFollowsColor = (fill' == stroke')`. All three are the
 * equality rule of v1.9's object recolor (`VectorFill.recolored`).
 */
object ObjectColorSlots {

    /** The slots [o] has, in a fixed order (stroke first). */
    fun slotsOf(o: VObject): List<ColorSlot> = when (o) {
        is VStroke -> listOf(ColorSlot.STROKE)
        is VPath -> listOfNotNull(ColorSlot.STROKE.takeIf { o.stroke != null }, ColorSlot.FILL.takeIf { o.fill != null })
        is VShape -> if (o.shape.type.isLineLike) listOf(ColorSlot.STROKE) else listOf(ColorSlot.STROKE, ColorSlot.FILL)
    }

    /** The color of [slot] on [o]; null when [o] has no such slot, or the slot holds a gradient. */
    fun colorOf(o: VObject, slot: ColorSlot): Int? {
        if (slot !in slotsOf(o)) return null
        return when (o) {
            is VStroke -> o.color
            is VPath -> when (slot) {
                ColorSlot.STROKE -> o.stroke?.color
                else -> (o.fill as? VPaint.Solid)?.color
            }
            is VShape -> when (slot) {
                ColorSlot.STROKE -> o.shape.strokeColor
                else -> o.shape.fillColor
            }
        }
    }

    /**
     * [o] with [slot] painted [argb]; [o] itself when it has no such slot ([slotsOf]) or nothing
     * changes. A gradient fill becomes Solid. On a shape the C4 pin applies (see the class docs);
     * the shape style is not changed (turning a fill on is the editor's `setPaintMode`).
     */
    fun set(o: VObject, slot: ColorSlot, argb: Int): VObject {
        if (slot !in slotsOf(o)) return o
        return when (o) {
            is VStroke -> if (o.color == argb) o else o.copy(color = argb)
            is VPath -> when (slot) {
                ColorSlot.STROKE -> o.stroke?.let { st -> if (st.color == argb) o else o.copy(stroke = st.copy(color = argb)) } ?: o
                else -> if ((o.fill as? VPaint.Solid)?.color == argb) o else o.copy(fill = VPaint.Solid(argb))
            }
            is VShape -> {
                val s = setShape(o.shape, slot, argb)
                if (s === o.shape) o else o.copy(shape = s)
            }
        }
    }

    private fun setShape(s: ShapeObject, slot: ColorSlot, argb: Int): ShapeObject = when (slot) {
        // C4: the fill keeps its color (pinned) and follows only while it equals the stroke.
        ColorSlot.STROKE -> {
            val follows = s.fillColor == argb
            if (s.strokeColor == argb && s.fillFollowsColor == follows) s else s.copy(strokeColor = argb, fillFollowsColor = follows)
        }
        else -> {
            val follows = argb == s.strokeColor
            if (s.fillColor == argb && s.fillFollowsColor == follows) s else s.copy(fillColor = argb, fillFollowsColor = follows)
        }
    }

    /** [o] with [f] applied to every color slot, gradient stops included; [o] itself when no color changes. */
    fun mapObject(o: VObject, f: (Int) -> Int): VObject = when (o) {
        is VStroke -> f(o.color).let { c -> if (c == o.color) o else o.copy(color = c) }
        is VPath -> {
            val fill = o.fill?.let { mapPaint(it, f) }
            val stroke = o.stroke?.let { st -> f(st.color).let { c -> if (c == st.color) st else st.copy(color = c) } }
            if (fill === o.fill && stroke === o.stroke) o else o.copy(fill = fill, stroke = stroke)
        }
        is VShape -> mapShape(o.shape, f).let { s -> if (s === o.shape) o else o.copy(shape = s) }
    }

    private fun mapPaint(p: VPaint, f: (Int) -> Int): VPaint = when (p) {
        is VPaint.Solid -> f(p.color).let { c -> if (c == p.color) p else VPaint.Solid(c) }
        is VPaint.Linear -> mapStops(p.stops, f)?.let { p.copy(stops = it) } ?: p
        is VPaint.Radial -> mapStops(p.stops, f)?.let { p.copy(stops = it) } ?: p
    }

    /** The mapped stops, or null when no stop changes. */
    private fun mapStops(stops: List<VStop>, f: (Int) -> Int): List<VStop>? {
        var changed = false
        val out = stops.map { st -> f(st.color).let { c -> if (c == st.color) st else { changed = true; st.copy(color = c) } } }
        return if (changed) out else null
    }

    /**
     * [v] with [mapObject] applied to the objects whose id is in [ids] (null = every object); [v]
     * itself when no color changes. An array on the layer draws from these objects, so its source
     * is mapped through them; the array's tint stops are not object colors and are kept.
     */
    fun map(v: VectorContent, ids: Set<Long>?, f: (Int) -> Int): VectorContent {
        var changed = false
        val out = v.objects.map { o ->
            if (ids != null && o.id !in ids) o else mapObject(o, f).also { if (it !== o) changed = true }
        }
        return if (changed) v.copy(objects = out) else v
    }

    /** [s] (a shape of a legacy shape layer, or of a [VShape]) with its stroke and fill mapped; `fillFollowsColor = (fill' == stroke')` (C4). [s] itself when nothing changes. */
    fun mapShape(s: ShapeObject, f: (Int) -> Int): ShapeObject {
        val stroke = f(s.strokeColor)
        val fill = f(s.fillColor)
        val follows = if (stroke == s.strokeColor && fill == s.fillColor) s.fillFollowsColor else fill == stroke
        return if (stroke == s.strokeColor && fill == s.fillColor && follows == s.fillFollowsColor) s
        else s.copy(strokeColor = stroke, fillColor = fill, fillFollowsColor = follows)
    }

    /**
     * [t] with the texts whose `TextItem.id` is in [items] (null = every text) mapped: color,
     * outline color, box fill and box border (UQ7). [t] itself when nothing changes.
     */
    fun mapText(t: TextLayerData, items: Set<Int>?, f: (Int) -> Int): TextLayerData {
        fun one(i: TextItem): TextItem = if (items != null && i.id !in items) i else mapItem(i, f)
        val item = one(t.item)
        var changed = item !== t.item
        val more = t.more.map { i -> one(i).also { if (it !== i) changed = true } }
        return if (changed) t.copy(item = item, more = more) else t
    }

    private fun mapItem(i: TextItem, f: (Int) -> Int): TextItem {
        val sp = i.spec
        val color = f(sp.color)
        val outline = f(sp.strokeColor)
        val boxFill = f(sp.box.fillColor)
        val border = f(sp.box.borderColor)
        if (color == sp.color && outline == sp.strokeColor && boxFill == sp.box.fillColor && border == sp.box.borderColor) return i
        val box = if (boxFill == sp.box.fillColor && border == sp.box.borderColor) sp.box else sp.box.copy(fillColor = boxFill, borderColor = border)
        return i.copy(spec = sp.copy(color = color, strokeColor = outline, box = box))
    }
}
