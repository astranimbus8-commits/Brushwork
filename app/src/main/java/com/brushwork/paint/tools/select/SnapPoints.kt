package com.brushwork.paint.tools.select

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.snap.SnapService
import com.brushwork.paint.snap.SnapSession
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapHit
import com.brushwork.paint.tools.transform.SnapLine

/*
 * "Snap to objects" helpers for the tools that place or drag single points: curve / polyline
 * anchors and tangent handles, curve-lasso points, polygon-lasso corners, marquee corners, the
 * handles of text on a path, ruler handles and frame cuts. Each tool keeps one [SnapSession]
 * (begin on the gesture, end on lift / cancel / deactivate) and snaps what the finger alone
 * gives on every move, so moving farther than the snap distance lets go of a guide.
 */

/** Guides are shown for target lines this close (document px) to a snapped point. */
internal const val POINT_GUIDE_EPS = 1e-3f

/** A zero-size box at [p] (a point's "box" for guides and labels). */
internal fun pointBox(p: Vec2): DocBox = DocBox(p.x, p.y, p.x, p.y)

/** The two lines through each of [points] (see [SnapLine.point]), labelled [name]. */
internal fun pointLines(points: List<Vec2>, name: String): List<SnapLine> {
    val out = ArrayList<SnapLine>(points.size * 2)
    for (p in points) out += SnapLine.point(p, name)
    return out
}

/**
 * [p] snapped to objects only: each axis to the closest target line within reach, never to the
 * grid. Unchanged (no guides) while "Snap to objects" is off. For points that never followed the
 * grid and shouldn't start to (e.g. tangent handles: a grid would make their length jump).
 */
fun SnapSession.snapPointToObjects(p: Vec2): Vec2 {
    val hx = snapValue(p.x, SnapAxis.X)
    val hy = snapValue(p.y, SnapAxis.Y)
    return applyPointHits(p, hx, hy)
}

/**
 * [p] snapped while "Snap to objects" ([service]) is on: to objects per axis, and an axis that
 * didn't snap follows the square grid when grid snapping is on (like the vector tools' points).
 * Off, [p] is returned unchanged: tools whose points never followed the grid behave exactly as
 * before.
 */
fun SnapSession.snapPointWhenOn(p: Vec2, service: SnapService): Vec2 {
    if (!service.enabled) {
        clearGuides()
        return p
    }
    return snapPoint(p)
}

/** [p] with the axes of [hx] / [hy] moved onto their lines; guides of the result shown. */
internal fun SnapSession.applyPointHits(p: Vec2, hx: SnapHit?, hy: SnapHit?): Vec2 {
    if (hx == null && hy == null) {
        clearGuides()
        return p
    }
    val q = Vec2(hx?.pos ?: p.x, hy?.pos ?: p.y)
    showGuidesFor(pointBox(q), POINT_GUIDE_EPS)
    return q
}
