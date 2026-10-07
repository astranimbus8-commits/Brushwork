package com.brushwork.paint.vector.pathfinder

import android.graphics.Path
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VectorOps

/**
 * How a Pathfinder result looks: an operand's [opacity], [fill] and [stroke] (v1.7 item 20,
 * §3.20). A result never keeps a spline: it reopens as a Bézier path.
 */
data class PathfinderStyle(val opacity: Float, val fill: VPaint?, val stroke: VStrokeStyle?) {

    /**
     * Outline's edge colour: the fill's colour (a gradient's first stop), else the stroke's,
     * else opaque black.
     */
    val edgeColor: Int
        get() = when (val f = fill) {
            is VPaint.Solid -> f.color
            is VPaint.Linear -> f.stops.firstOrNull()?.color ?: stroke?.color ?: BLACK
            is VPaint.Radial -> f.stops.firstOrNull()?.color ?: stroke?.color ?: BLACK
            null -> stroke?.color ?: BLACK
        }

    /** A result object of this style filling [path] (null when nothing is left of it). */
    fun objectOf(path: Path): VPath? {
        val subpaths = PathConvert.subpaths(path)
        if (subpaths.isEmpty()) return null
        return VPath(0, opacity, subpaths, fillRule = PathConvert.ruleOf(path), fill = fill, stroke = stroke)
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()

        /** A path's own style; [opacityScale] multiplies its opacity (its layer's, when it moves to a new layer). */
        fun of(p: VPath, opacityScale: Float = 1f): PathfinderStyle = PathfinderStyle(p.opacity * opacityScale, p.fill, p.stroke)

        /**
         * A shape's style as the Shape tool draws it (`VectorOps.toPaths`): its fill and outline;
         * an arrow's filled heads give the fill.
         */
        fun of(s: VShape, opacityScale: Float = 1f): PathfinderStyle {
            val paths = VectorOps.toPaths(s)
            val main = paths.first()
            val fill = main.fill ?: paths.drop(1).firstNotNullOfOrNull { it.fill }
            return PathfinderStyle(s.opacity * opacityScale, fill, main.stroke)
        }

        /** A shape layer's shape, at its layer's [opacity]. */
        fun of(shape: ShapeObject, opacity: Float): PathfinderStyle = of(VShape(0, 1f, shape), opacity)
    }
}

/**
 * Which operand's style each operation keeps (Illustrator's rules, §3.20). Operands are in
 * picture order, bottom first: "top" is the last.
 */
object PathfinderStyles {
    /**
     * The operand whose style a shape mode's ONE result takes: Unite, Intersect and Exclude the
     * top object's; Minus front the back object's; Minus back the front object's.
     */
    fun shapeModeSource(op: PathfinderOp, count: Int): Int = when (op) {
        PathfinderOp.MINUS_FRONT -> 0
        else -> count - 1
    }

    /** Outline's stroke for an edge of a piece of [style]: plain, 1 px, the piece's fill colour. */
    fun edgeStroke(style: PathfinderStyle): VStrokeStyle = VStrokeStyle(kind = VStrokeKind.PLAIN, color = style.edgeColor, width = 1f)
}
