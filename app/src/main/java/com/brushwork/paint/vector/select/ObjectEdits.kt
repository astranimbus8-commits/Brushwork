package com.brushwork.paint.vector.select

import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps

/**
 * The content math of the Object bar (v1.5 §4.9, A2): delete, duplicate, arrange and recolor the
 * selected objects of a vector layer. Every function returns the new [VectorContent] (which the
 * caller applies with ONE `vectors.update` step) or null when nothing would change; ids that are
 * not in the content are ignored. Objects keep their ids (duplicates get new ones).
 */
object ObjectEdits {
    /** Where the selected objects go in the stacking order. */
    enum class Arrange(val label: String) {
        /** Above the nearest object above them that they overlap (one visible step up). */
        FORWARD("Bring forward"),
        /** Below the nearest object below them that they overlap (one visible step down). */
        BACKWARD("Send backward"),
        /** Above everything. */
        FRONT("Bring to front"),
        /** Below everything. */
        BACK("Send to back"),
    }

    /** Without the objects [ids] (null when none of them is there). */
    fun delete(content: VectorContent, ids: Set<Long>): VectorContent? {
        val after = content.without(ids)
        return if (after === content) null else after
    }

    /**
     * Copies of the objects [ids] moved by ([dx], [dy]) document px, stacked right above the
     * topmost of them in their own order, with new ids. Returns the content and the copies' ids
     * (null when none of [ids] is there).
     */
    fun duplicate(content: VectorContent, ids: Set<Long>, dx: Float, dy: Float): Pair<VectorContent, Set<Long>>? {
        val objs = content.objects
        val top = objs.indexOfLast { it.id in ids }
        if (top < 0) return null
        val m = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)
        var next = content.nextId
        val copies = ArrayList<VObject>()
        for (o in objs) if (o.id in ids) copies += VectorOps.transformed(o, m).withId(next++)
        val out = ArrayList<VObject>(objs.size + copies.size)
        for (i in objs.indices) {
            out += objs[i]
            if (i == top) out += copies
        }
        return content.copy(objects = out, nextId = next) to copies.mapTo(LinkedHashSet()) { it.id }
    }

    /**
     * The objects [ids] moved in the stacking order ([how]); they keep their order among
     * themselves. [overlaps] tells whether two objects can cover each other (their paint bounds
     * meet): [Arrange.FORWARD] moves the selection right above the nearest unselected object
     * above its topmost member that overlaps one of them (objects in between that overlap none
     * of them don't count, so every press makes a visible change), [Arrange.BACKWARD] right
     * below the nearest one under its bottom member. Null when nothing would change (already
     * in front / at the back).
     */
    fun arrange(content: VectorContent, ids: Set<Long>, how: Arrange, overlaps: (VObject, VObject) -> Boolean): VectorContent? {
        val objs = content.objects
        val selected = objs.filter { it.id in ids }
        if (selected.isEmpty()) return null
        val rest = objs.filter { it.id !in ids }
        val out: List<VObject> = when (how) {
            Arrange.FRONT -> rest + selected
            Arrange.BACK -> selected + rest
            Arrange.FORWARD -> {
                val top = objs.indexOfLast { it.id in ids }
                var j = -1
                for (k in top + 1 until objs.size) {
                    val o = objs[k]
                    if (o.id !in ids && selected.any { overlaps(it, o) }) { j = k; break }
                }
                if (j < 0) return null
                val anchor = objs[j]
                val at = rest.indexOfFirst { it === anchor }
                rest.subList(0, at + 1) + selected + rest.subList(at + 1, rest.size)
            }
            Arrange.BACKWARD -> {
                val bottom = objs.indexOfFirst { it.id in ids }
                var j = -1
                for (k in bottom - 1 downTo 0) {
                    val o = objs[k]
                    if (o.id !in ids && selected.any { overlaps(it, o) }) { j = k; break }
                }
                if (j < 0) return null
                val anchor = objs[j]
                val at = rest.indexOfFirst { it === anchor }
                rest.subList(0, at) + selected + rest.subList(at, rest.size)
            }
        }
        if (out.size != objs.size || out.indices.all { out[it] === objs[it] }) return null
        return content.copy(objects = out)
    }

    /**
     * The objects [ids] painted with [color]: lines (strokes, path outlines, shape outlines and
     * arrow heads) and, unless [linesOnly], fills (a gradient fill becomes the plain color).
     * Fills are never added where there were none. Null when nothing changes.
     */
    fun recolor(content: VectorContent, ids: Set<Long>, color: Int, linesOnly: Boolean): VectorContent? {
        var changed = false
        val out = content.objects.map { o ->
            if (o.id !in ids) return@map o
            val r = recolored(o, color, linesOnly)
            if (r != o) changed = true
            r
        }
        return if (changed) content.copy(objects = out) else null
    }

    /** [o] painted with [color] (see [recolor]). */
    fun recolored(o: VObject, color: Int, linesOnly: Boolean): VObject = when (o) {
        is VStroke -> if (o.color == color) o else o.copy(color = color)
        is VPath -> o.copy(
            fill = if (linesOnly) o.fill else o.fill?.let { VPaint.Solid(color) },
            stroke = o.stroke?.copy(color = color),
        )
        is VShape -> {
            val s = o.shape
            o.copy(
                shape = s.copy(
                    fillColor = if (!linesOnly && s.style.fill && !s.type.isLineLike) color else s.fillColor,
                    strokeColor = if (s.strokes) color else s.strokeColor,
                ),
            )
        }
    }

    /** True when [o] has something [recolor] would change with [linesOnly]. */
    fun hasLines(o: VObject): Boolean = when (o) {
        is VStroke -> true
        is VPath -> o.stroke != null
        is VShape -> o.shape.strokes
    }
}
