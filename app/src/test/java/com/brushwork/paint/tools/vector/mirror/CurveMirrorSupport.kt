package com.brushwork.paint.tools.vector.mirror

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.H
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.W
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import kotlin.math.max

/**
 * Shared set-up of the v1.8 curve mirror tests (item 1a, design §3.1): one ruler of each kind on
 * the 400 × 300 test document (centre 200, 150), a three-point path left of the centre, and the
 * pixel probes.
 */
internal object CurveMirrorSupport {
    /** One ruler of each kind; every one but the last is affine. */
    val rulers: List<Pair<String, SymmetrySettings>> = listOf(
        "mirror" to SymmetrySettings(type = SymmetryType.MIRROR),
        "kaleidoscope" to SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 3),
        "rotation" to SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4),
        "array" to SymmetrySettings(type = SymmetryType.ARRAY, spacingX = 150f, spacingY = 150f, angleDeg = 0f),
        "perspective" to SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY),
    )

    val affineRulers get() = rulers.filter { it.second.type != SymmetryType.PERSPECTIVE_ARRAY }

    val perspective get() = rulers.single { it.second.type == SymmetryType.PERSPECTIVE_ARRAY }.second

    /** The path's points (document px): its first point is where the copies' maps are taken. */
    val points = listOf(Vec2(60f, 70f), Vec2(110f, 100f), Vec2(150f, 80f))

    /** Taps [points] with the open tool (a new path). */
    fun EditorController.drawPath(pts: List<Vec2> = points) {
        for (p in pts) tap(p)
    }

    /** [p] mapped by [m] (row-major 3 × 3), or null at or behind the horizon. */
    fun map(m: FloatArray, p: Vec2): Vec2? {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        if (!(w > 0f)) return null
        return Vec2((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    /** True when [p] lies on the test document, [margin] px in. */
    fun inside(p: Vec2, margin: Float = 3f) = p.x >= margin && p.y >= margin && p.x < W - margin && p.y < H - margin

    /** The largest alpha of [b] within [r] px of [p]. */
    fun alphaNear(b: Bitmap, p: Vec2, r: Int = 2): Int {
        var a = 0
        val x0 = p.x.toInt()
        val y0 = p.y.toInt()
        for (y in y0 - r..y0 + r) for (x in x0 - r..x0 + r) {
            if (x < 0 || y < 0 || x >= b.width || y >= b.height) continue
            a = max(a, b.getPixel(x, y) ushr 24)
        }
        return a
    }

    /** The open path's first point (document px). */
    fun CurveTool.first(): Vec2 = pointAt(0)
}
