package com.brushwork.paint.vector.lift

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.transform.TransformState
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.copies.CopyEdit
import com.brushwork.paint.vector.edit.VectorEditSession

/**
 * v1.10 (R1 (a), J18, LA4): one side (copy [side]) of the mirrored object [id], lifted alone by the
 * Transform tool. The session floats that copy and its hole shows the object without it
 * (`VectorLayers.beginEdit` with a side), so the other sides stay where they are drawn.
 *
 * ✓ keeps the side linked: `CopyEdit.offset` changes only the maps (k ≥ 1), or moves the source
 * and rebases the other maps (k = 0), as one step. The transform is always an isometry (resizing a
 * side is blocked, LA4): a residual drift is snapped to the nearest one about the side's box centre.
 * A result the copy limits refuse ([refused]) changes nothing. Delete drops this copy only
 * (`CopyEdit.dropCopy`); the object stays selected with its other sides.
 */
internal class CopyLift(
    c: EditorController,
    session: VectorEditSession,
    /** The object one side of which is lifted. */
    val id: Long,
    override val side: Int,
    update: (Layer, VectorContent, String, VectorLayers.ShiftHint?, (Boolean) -> Unit) -> Unit,
    onEnded: (VectorObjectLift) -> Unit,
) : VectorObjectLift(c, session, setOf(id), update, onEnded) {

    override fun committed(m: FloatArray): FloatArray =
        if (CopyEdit.isIsometry(m)) m else CopyEdit.nearestIsometry(m, Vec2(sourceRect.exactCenterX(), sourceRect.exactCenterY()))

    /** The commit put this side onto another one (or the source): the maps merged, so it is gone as a side. */
    private var mergedAway = false

    override fun mapped(o: VObject, m: FloatArray): List<VObject>? {
        val moved = CopyEdit.offset(o, side, m) ?: return null
        mergedAway = ObjectCopies.of(moved).maps.size < ObjectCopies.of(o).maps.size
        return listOf(moved)
    }

    override val shiftsCache: Boolean get() = false

    /** A merged side is no longer picked (the next lift of the object is all of it). */
    override fun afterCommit() {
        if (mergedAway) VectorLift.sideDeleted(c)
    }

    override fun deleted(content: VectorContent): VectorContent? {
        val o = content.byId(id) ?: return null
        val rest = CopyEdit.dropCopy(o, side) ?: return null
        return content.replaced(mapOf(id to listOf(rest)))
    }

    /** The object keeps its other sides and stays selected; the deleted side is no longer picked. */
    override fun afterDelete() = VectorLift.sideDeleted(c)

    /**
     * True when ✓ with [state] would leave the object unusable (`StrokeCopies.isUsable` /
     * `CopyMaps` limits): the Transform tool then shows `MirrorLabels110.SIDE_REFUSED` and keeps
     * the lift open instead of committing.
     */
    fun refused(state: TransformState): Boolean {
        if (!isOpen) return false
        val m0 = LiftGeometry.matrix(state, sourceRect.left, sourceRect.top) ?: return false
        val shift = LiftGeometry.wholePixelShift(m0)
        if (shift != null && shift[0] == 0 && shift[1] == 0) return false
        val m = if (shift != null) LiftGeometry.translation(shift[0].toFloat(), shift[1].toFloat()) else committed(m0)
        val o = currentContent()?.byId(id) ?: return false
        return CopyEdit.offset(o, side, m) == null
    }
}
