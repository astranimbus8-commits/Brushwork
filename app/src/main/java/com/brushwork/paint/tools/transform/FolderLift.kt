package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.MultiLayerRenderOverride
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.vector.LayerDataTransforms
import com.brushwork.paint.vector.lift.LiftGeometry
import kotlin.math.ceil
import kotlin.math.floor

/*
 * v1.7 (item 11, design §3.11 a): the Transform tool on a FOLDER. Every descendant layer is
 * transformed by its own rule (a text, shape or array kept as data through the data maps, vector
 * objects mapped exactly, pixels resampled once), as ONE step. While dragging, the preview shows
 * every child's lifted pixels moving together (a multi-layer render override); the folder is
 * moved, turned and scaled only (Distort and Free deform work on one layer).
 */

/** Why a child of a folder can't be transformed: its transparency is locked. */
internal fun alphaLockedMessage(l: Layer): String = "Transparency is locked on \"${l.name}\". Unlock it to transform."

/** How one child of a transformed folder is changed on ✓. */
internal enum class ChildRule { DATA, VECTOR, PIXELS }

/**
 * One child of a lifted folder: [rect] is where its pixels are (document px, its content
 * bounds), [crop] a copy of them (the preview), [kind] its data kind when [rule] is DATA.
 */
internal class FolderChild(val layer: Layer, val rule: ChildRule, val kind: DataKind?, val rect: Rect, val crop: Bitmap)

/**
 * A folder lifted by the Transform tool. [sourceRect] is the union of the children's content
 * bounds; [floating] is a 1 × 1 placeholder the tool never draws (the preview is [preview]).
 * [smooth]: the tool's interpolation for the children resampled as pixels.
 */
internal class FolderLift(
    private val c: EditorController,
    override val layer: Layer,
    val children: List<FolderChild>,
    private val maps: DataMaps,
    override val sourceRect: Rect,
    private val smooth: () -> Boolean,
) : ObjectLift {
    override val floating: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override val floatingScale: Float get() = 1f

    /** A text kept as text is among the children: the folder scales proportionally only (§3.11 a). */
    val uniformOnly: Boolean get() = children.any { it.rule == ChildRule.DATA && it.kind == DataKind.TEXT }

    /** The layers the preview draws (the children). */
    val layers: Set<Layer> = children.mapTo(LinkedHashSet()) { it.layer }

    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    override fun drawBase(canvas: Canvas) {}

    /**
     * The preview while the transform is pending: each child without its lifted pixels plus
     * those pixels drawn by [matrix] (the tool's source px -> document map of the whole box).
     */
    fun preview(matrix: Matrix, paint: () -> Paint): LayerRenderOverride = object : MultiLayerRenderOverride {
        override val layer: Layer get() = this@FolderLift.layer
        override val layers: Set<Layer> get() = this@FolderLift.layers
        private val m = Matrix()

        override fun drawContent(canvas: Canvas): Boolean = false

        override fun drawContentFor(target: Layer, canvas: Canvas): Boolean {
            val child = children.firstOrNull { it.layer === target } ?: return false
            if (target.bitmap.isRecycled || child.crop.isRecycled) return false
            canvas.drawBitmap(target.bitmap, 0f, 0f, null)
            canvas.drawRect(child.rect, clear)
            m.set(matrix)
            m.preTranslate((child.rect.left - sourceRect.left).toFloat(), (child.rect.top - sourceRect.top).toFloat())
            canvas.drawBitmap(child.crop, m, paint())
            return true
        }
    }

    /**
     * Applies [state] to every child as ONE step [label] (a folder never distorts). A child that
     * went away meanwhile is skipped. Refused as a whole, with the reason and nothing changed,
     * when a child was locked (or its folder, or its transparency) while the transform was
     * pending, or when an arrayed child's map declines this transform (its copies would be baked).
     * False when nothing changed.
     */
    override fun commit(state: TransformState, label: String): Boolean {
        if (state.isDistorted || c.doc.indexOf(layer) < 0) return false
        val m = LiftGeometry.matrix(state, sourceRect.left, sourceRect.top) ?: return false
        val live = children.filter { c.doc.indexOf(it.layer) >= 0 }
        for (child in live) {
            val l = child.layer
            if (!c.checkUsable(l, allowHidden = true)) return false
            if (l.alphaLocked) { c.toast(alphaLockedMessage(l)); return false }
        }
        // Planned before anything changes: an arrayed child that can't take [m] refuses it all.
        val arrays = HashMap<Layer, LayerData>()
        for (child in live) {
            if (child.rule != ChildRule.DATA || child.kind != DataKind.ARRAY || child.layer.array == null) continue
            arrays[child.layer] = maps.array(child.layer, m) ?: run { c.toast(TransformTool.ARRAY_REFUSAL); return false }
        }
        val steps = c.undoManager.undoCount
        c.groupUndo(label) {
            for (child in live) {
                if (c.doc.indexOf(child.layer) < 0) continue
                commitChild(child, m, label, arrays[child.layer])
            }
        }
        return c.undoManager.undoCount > steps
    }

    private fun commitChild(child: FolderChild, m: FloatArray, label: String, array: LayerData?) {
        val l = child.layer
        val d = l.dataSnapshot()
        when (child.rule) {
            ChildRule.DATA -> {
                val after = when (child.kind) {
                    DataKind.TEXT -> d.text?.takeIf { maps.textCanMap(m) }?.let { maps.text(it, m) }?.let { d.copy(text = it) }
                    DataKind.SHAPE -> d.shape?.let { maps.shape(it, m) }?.let { d.copy(shape = it) }
                    DataKind.ARRAY -> array
                    null -> null
                }
                // A text or shape map that declines: the pixels are resampled (the layer becomes
                // a raster layer in the same step). An array was mapped up front (see [commit]).
                if (after != null) DataRender.apply(c, l, after, label, child.rect, allowHidden = true)
                else if (child.kind != DataKind.ARRAY) resample(child, m, label)
            }
            ChildRule.VECTOR -> {
                val content = d.vector ?: return
                DataRender.apply(c, l, d.copy(vector = LayerDataTransforms.mapped(content, m)), label, child.rect, allowHidden = true)
            }
            ChildRule.PIXELS -> resample(child, m, label)
        }
    }

    /** The child's lifted pixels drawn by [m] (one pixel edit). */
    private fun resample(child: FolderChild, m: FloatArray, label: String) {
        val l = child.layer
        if (child.crop.isRecycled || l.bitmap.isRecycled) return
        val mm = Matrix().apply {
            setValues(m)
            preTranslate(child.rect.left.toFloat(), child.rect.top.toFloat())
        }
        val dst = RectF(0f, 0f, child.crop.width.toFloat(), child.crop.height.toFloat())
        mm.mapRect(dst)
        val out = Rect(floor(dst.left).toInt() - 2, floor(dst.top).toInt() - 2, ceil(dst.right).toInt() + 2, ceil(dst.bottom).toInt() + 2)
        val rec = c.beginEdit(l, EditTarget.CONTENT)
        try {
            rec.touch(child.rect)
            if (out.intersect(0, 0, l.bitmap.width, l.bitmap.height)) rec.touch(out)
            val canvas = Canvas(l.bitmap)
            canvas.drawRect(child.rect, clear)
            val s = smooth()
            canvas.drawBitmap(child.crop, mm, Paint().apply { isFilterBitmap = s; isAntiAlias = s })
        } catch (e: OutOfMemoryError) {
            rec.abort()
            c.toast("Not enough memory to apply the transform")
            return
        }
        c.commitEdit(rec, label)
    }

    /**
     * Every child's content goes (one step [label]): data children keep their kind, empty.
     * Refused as a whole, with the reason, when a child was locked meanwhile.
     */
    override fun delete(label: String): Boolean {
        for (child in children) {
            val l = child.layer
            if (c.doc.indexOf(l) < 0) continue
            if (!c.checkUsable(l, allowHidden = true)) return false
            if (l.alphaLocked) { c.toast(alphaLockedMessage(l)); return false }
        }
        val steps = c.undoManager.undoCount
        c.groupUndo(label) {
            for (child in children) {
                val l = child.layer
                if (c.doc.indexOf(l) < 0) continue
                val d = l.dataSnapshot()
                val area = Rect(child.rect)
                DataRender.paintBounds(d)?.let { if (!it.isEmpty) area.union(DataRender.roundOut(it)) }
                area.inset(-2, -2)
                when (child.rule) {
                    ChildRule.DATA -> c.updateLayerData(l, d.copy(text = null, shape = null, vector = null, array = null), label, area, allowHidden = true, draw = {})
                    ChildRule.VECTOR -> c.updateLayerData(l, d.copy(vector = d.vector?.copy(objects = emptyList())), label, area, allowHidden = true, draw = {})
                    ChildRule.PIXELS -> {
                        val rec = c.beginEdit(l, EditTarget.CONTENT)
                        rec.touch(child.rect)
                        Canvas(l.bitmap).drawRect(child.rect, clear)
                        c.commitEdit(rec, label)
                    }
                }
            }
        }
        return c.undoManager.undoCount > steps
    }

    override fun release() {
        for (child in children) if (!child.crop.isRecycled) child.crop.recycle()
        if (!floating.isRecycled) floating.recycle()
    }
}

/**
 * Lifts a folder ([FolderLift]): its descendant layers (not the folders inside it, nor
 * adjustment layers, whose effect has no place). Refused, with the reason, when a child is
 * locked or its transparency is, or when an arrayed child can't be mapped.
 */
internal class FolderLiftProvider(
    private val c: EditorController,
    private val maps: () -> DataMaps,
    private val data: DataLiftProvider,
    private val smooth: () -> Boolean,
) : ObjectLiftProvider {
    /** The layers a transform of [folder] changes, bottom first. */
    fun members(folder: Layer): List<Layer> {
        val doc = c.doc
        val idx = doc.indexOf(folder)
        if (idx < 0 || !folder.isFolder) return emptyList()
        return LayerTree.block(doc.layers, idx).filter { it != idx }.map { doc.layers[it] }.filter { !it.isFolder && !it.isAdjustmentLayer }
    }

    override fun lift(layer: Layer, onReady: (ObjectLift?) -> Unit): Boolean {
        val members = members(layer)
        val m = maps()
        for (l in members) {
            // Its own lock or that of a folder inside this one (I11), with the standard message.
            if (!c.checkUsable(l, allowHidden = true)) return false
            if (l.alphaLocked) { c.toast(alphaLockedMessage(l)); return false }
            if (l.array != null && m.array(l, DataRender.IDENTITY) == null) { c.toast(TransformTool.ARRAY_REFUSAL); return false }
        }
        // A vector render still running lands first: the crops are then what the layers show.
        c.settleVectorWork()
        val doc = Rect(0, 0, c.doc.width, c.doc.height)
        val children = ArrayList<FolderChild>(members.size)
        try {
            for (l in members) {
                val kind = data.kindOf(l)
                val rule = when {
                    kind != null -> ChildRule.DATA
                    l.isVectorLayer -> ChildRule.VECTOR
                    else -> ChildRule.PIXELS
                }
                val rect = (if (rule == ChildRule.PIXELS) ContentBounds.of(l.bitmap) else DataRender.contentRect(l)) ?: continue
                if (!rect.intersect(doc)) continue
                children += FolderChild(l, rule, kind, rect, DataRender.crop(l.bitmap, rect))
            }
        } catch (e: OutOfMemoryError) {
            for (ch in children) ch.crop.recycle()
            c.toast("Not enough memory to transform this")
            return false
        }
        if (children.isEmpty()) {
            c.toast("Nothing to transform in this folder")
            onReady(null)
            return true
        }
        val box = Rect(children[0].rect)
        for (ch in children) box.union(ch.rect)
        onReady(FolderLift(c, layer, children, m, box, smooth))
        return true
    }

    override fun tapped(p: Vec2): Boolean = false

    /** The union of the children's known content bounds (data bounds, or a small layer's scan). */
    override fun liftBox(layer: Layer): RectF? {
        var out: RectF? = null
        for (l in members(layer)) {
            val b = DataRender.paintBounds(l.dataSnapshot())?.takeIf { !it.isEmpty }
                ?: (c.snapping.bounds(l) ?: if (l.bitmap.width.toLong() * l.bitmap.height <= SMALL) ContentBounds.of(l.bitmap) else null)?.let { RectF(it) }
                ?: continue
            out = out?.apply { union(b) } ?: RectF(b)
        }
        return out
    }

    private companion object {
        /** Layers up to this many pixels are scanned on the main thread for [liftBox]. */
        const val SMALL = 2_000_000L
    }
}
