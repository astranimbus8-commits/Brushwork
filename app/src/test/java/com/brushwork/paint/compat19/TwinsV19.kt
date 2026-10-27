package com.brushwork.paint.compat19

import com.brushwork.paint.model.ArraySpec

/*
 * v1.10 G2: NOT v1.9's code. Helpers of the compat tests that build the kit's value holding the
 * same values as a v1.10 one, field by field (never through JSON), so a test can compare what
 * v1.10 writes with what v1.9 writes for the same values. A3 kept [v19Twin] in
 * `v110/a3/ArraySpecV19.kt` until the kit landed.
 */

/**
 * The v1.9 spec holding the same values as v1.10's [s], field by field; v1.10's `hideSource` and
 * the look's `colorMode` have no v1.9 field and are left out.
 */
internal fun v19Twin(s: ArraySpec): ArraySpecV19 = ArraySpecV19(
    mode = ArrayModeV19.valueOf(s.mode.name),
    count = s.count,
    relativeX = s.relativeX,
    relativeY = s.relativeY,
    constantX = s.constantX,
    constantY = s.constantY,
    centerX = s.centerX,
    centerY = s.centerY,
    sweepDeg = s.sweepDeg,
    rotateCopies = s.rotateCopies,
    guide = s.guide?.let { g -> VSubpathV19(g.anchors.map { a -> VAnchorV19(a.x, a.y, a.sharp, a.inX, a.inY, a.outX, a.outY, a.width) }, g.closed) },
    spacing = s.spacing,
    alignToCurve = s.alignToCurve,
    moveX = s.moveX,
    moveY = s.moveY,
    turnDeg = s.turnDeg,
    scale = s.scale,
    pivotX = s.pivotX,
    pivotY = s.pivotY,
    editingSource = s.editingSource,
    look = s.look?.let { l ->
        ArrayLookV19(
            opacityStart = l.opacityStart,
            opacityEnd = l.opacityEnd,
            sizeStart = l.sizeStart,
            sizeEnd = l.sizeEnd,
            turnStart = l.turnStart,
            turnEnd = l.turnEnd,
            easing = ArrayEasingV19.valueOf(l.easing.name),
            tint = l.tint.map { VStopV19(it.offset, it.color) },
            tintStrength = l.tintStrength,
        )
    },
)
