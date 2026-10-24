package com.brushwork.paint.v110.a6

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec

/**
 * Paths of each kind for the A6 tests (v1.10, R10, R11): what the Curve, Polyline and Path tools
 * store, and the paths Edit refuses (several parts, one point). Coordinates fit a 400 × 300
 * document.
 */
internal object A6Paths {
    private val line = VStrokeStyle(color = 0xFF203080.toInt(), width = 4f)

    /** A Curve-tool path: smooth anchors, automatic handles. */
    fun curve(closed: Boolean = false) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(60f, 150f, false), VAnchor(120f, 80f, false), VAnchor(180f, 150f, false), VAnchor(120f, 210f, false)), closed)),
        stroke = line,
    )

    /** A Polyline-tool path: straight segments. */
    fun polyline(closed: Boolean = false) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(220f, 60f, true), VAnchor(300f, 90f, true), VAnchor(260f, 160f, true)), closed)),
        polyline = true,
        stroke = line,
    )

    /** The control points of [spline]'s path. */
    val splinePoints = listOf(VSplinePoint(60f, 230f), VSplinePoint(120f, 170f), VSplinePoint(180f, 260f), VSplinePoint(240f, 190f), VSplinePoint(300f, 250f))

    /** A Path-tool path: a spline and its Bézier form (it passes the I9 check). */
    fun spline(cyclic: Boolean = false): VPath {
        val sp = VSpline(splinePoints, cyclic = cyclic)
        return VPath(0, subpaths = listOf(SplineBezier.toSubpath(sp)), spline = sp, stroke = line)
    }

    /** A path of two separate parts. */
    fun twoParts() = VPath(
        0,
        subpaths = listOf(
            VSubpath(listOf(VAnchor(40f, 40f, true), VAnchor(100f, 40f, true), VAnchor(70f, 90f, true)), closed = true),
            VSubpath(listOf(VAnchor(200f, 40f, true), VAnchor(260f, 40f, true), VAnchor(230f, 90f, true)), closed = true),
        ),
        fill = VPaint.Solid(0xFFE04020.toInt()),
        stroke = line,
    )

    /** A path of a single point. */
    fun onePoint() = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(150f, 150f, false)))), stroke = line)

    /** Adds [objects] to the kit's vector layer (one step) and waits for the render; their ids. */
    fun add(kit: ObjectTestKit, c: EditorController, vararg objects: VObject): List<Long> {
        val ids = c.vectors.addObjects(c.vec, objects.toList(), "Add")
        kit.idleUntil { !c.vectors.isRendering }
        return ids
    }

    /** Selects object [id] of the kit's vector layer alone. */
    fun select(c: EditorController, id: Long) = c.vectors.setSelection(c.vec, setOf(id))
}
