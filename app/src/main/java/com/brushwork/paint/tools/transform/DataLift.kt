package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayTransforms
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextTransforms
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeTransforms
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.lift.LiftGeometry
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlin.math.ceil
import kotlin.math.floor

/*
 * v1.7 (item 11, design §3.11): "transforming a text, curve or shape object doesn't rasterize
 * it ... it will only allow for transform". The Transform tool lifts a text, shape or arrayed
 * layer as a DATA object: while dragging, the preview shows the layer's pixels moving as today;
 * ✓ maps the layer's data by the transform's affine map (`TextTransforms` [D], `ShapeTransforms`
 * [C], `ArrayTransforms` [E]) and re-renders it, as ONE step that keeps the layer a text, shape
 * or array layer (I1). A text is moved, turned and scaled proportionally only (no side handles,
 * no flips); Distort and Free deform ask to rasterize first.
 */

/**
 * The data maps a data lift uses (areas C, D and E own the bodies; on `main` before they merge
 * they decline). Each takes a row-major 3 × 3 affine matrix in document px (the order of
 * `android.graphics.Matrix.getValues`). Tests substitute fakes through `TransformTool.dataMaps`.
 */
internal interface DataMaps {
    /** True when [m] keeps a text a text (a similarity). */
    fun textCanMap(m: FloatArray): Boolean

    /** Text layer data (TextCodec JSON) mapped by [m], or null. */
    fun text(textData: String, m: FloatArray): String?

    /** Shape layer data (ShapeCodec JSON) mapped by [m], or null. */
    fun shape(shapeData: String, m: FloatArray): String?

    /** [layer]'s data with its array (spec and source) mapped by [m], or null. */
    fun array(layer: Layer, m: FloatArray): LayerData?
}

/** The real maps: the areas' `TextTransforms`, `ShapeTransforms` and `ArrayTransforms`. */
internal object RealDataMaps : DataMaps {
    override fun textCanMap(m: FloatArray): Boolean = TextTransforms.canMap(m)
    override fun text(textData: String, m: FloatArray): String? = TextTransforms.mapped(textData, m)
    override fun shape(shapeData: String, m: FloatArray): String? = ShapeTransforms.mapped(shapeData, m)
    override fun array(layer: Layer, m: FloatArray): LayerData? = ArrayTransforms.mapped(layer, m)
}

/** What a data lift maps: a text, a shape, or an array (its spec and its source, whatever the source is). */
internal enum class DataKind { TEXT, SHAPE, ARRAY }

/**
 * Re-rendering a layer from its (mapped) data, shared by [DataLift] and [FolderLift]: ONE
 * `updateLayerData` step whose pixels are a fresh rendering of the data (I1). A text is drawn by
 * `TextRenderer`, a shape as the `VShape` the vector renderer draws (tile by tile, so its pixels
 * equal a full render), vector content by `VectorLayerRenderer` over whole tiles, a raster
 * array by `ArrayDraw.drawPixels`; a text or shape array source is repeated per copy by
 * `updateLayerData` itself (`ArrayDraw.drawWithArray`).
 */
internal object DataRender {
    /** The identity map (probes whether a map is available at all). */
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /**
     * Sets [layer]'s data to [after] and re-renders it, as one step [label]. [oldArea]: the
     * pixels the layer showed before (document px; they are cleared). [allowHidden]: a hidden
     * child of a transformed folder moves with it. False when nothing could be rendered or the
     * layer refused (locked, gone).
     */
    fun apply(c: EditorController, layer: Layer, after: LayerData, label: String, oldArea: Rect, allowHidden: Boolean): Boolean {
        val before = layer.dataSnapshot()
        val doc = Rect(0, 0, c.doc.width, c.doc.height)
        val dirty = Rect(oldArea)
        for (d in listOf(before, after)) paintBounds(d)?.let { b -> if (!b.isEmpty) dirty.union(roundOut(b)) }
        dirty.inset(-2, -2)
        val array = after.array
        val draw: (Canvas) -> Unit = when {
            array?.pixels != null -> { cv -> ArrayDraw.drawPixels(cv, array) }
            after.vector != null -> {
                val content = ArrayDraw.effectiveVector(after) ?: return false
                // Whole tiles: the vector renderer's pixels then equal a full render's (I1).
                tiles(dirty)
                val region = Rect(dirty)
                if (!region.intersect(doc)) region.setEmpty()
                val tips = TipCache(TIP_BYTES)
                ({ cv -> if (!region.isEmpty) VectorLayerRenderer.render(cv, content, region, tips = tips, document = doc) })
            }
            after.text != null -> {
                val item = TextCodec.decode(after.text) ?: return false
                val prep = TextRenderer.prepare(item)
                ({ cv -> TextRenderer.drawItem(cv, item, prep, null) })
            }
            after.shape != null -> {
                val o = ShapeCodec.decode(after.shape) ?: return false
                val content = VectorContent(objects = listOf(VShape(0L, shape = o)))
                tiles(dirty)
                val region = Rect(dirty)
                if (!region.intersect(doc)) region.setEmpty()
                val tips = TipCache(TIP_BYTES)
                ({ cv -> if (!region.isEmpty) VectorLayerRenderer.render(cv, content, region, tips = tips, document = doc) })
            }
            else -> return false
        }
        return c.updateLayerData(layer, after, label, dirty, allowHidden = allowHidden, draw = draw)
    }

    /**
     * Everything the rendering of [d] paints (document px, a new RectF): a live array's cache
     * bounds (the copies), else the vector content's paint bounds, else the shape's, else the
     * text's; null when [d] has no editable content.
     */
    fun paintBounds(d: LayerData): RectF? {
        ArrayDraw.cacheBounds(d)?.let { return it }
        d.vector?.let { return ObjectIndex.of(it).unionBounds() }
        d.shape?.let { s -> return ShapeCodec.decode(s)?.let { VectorOps.bounds(VShape(0L, shape = it)) } }
        d.text?.let { return ArrayDraw.sourceBounds(d) }
        return null
    }

    /** [r] grown to the vector renderer's tile grid. */
    private fun tiles(r: Rect) {
        val t = VectorLayerRenderer.TILE
        r.set(Math.floorDiv(r.left, t) * t, Math.floorDiv(r.top, t) * t, Math.floorDiv(r.right + t - 1, t) * t, Math.floorDiv(r.bottom + t - 1, t) * t)
    }

    fun roundOut(b: RectF): Rect {
        fun lo(v: Float) = floor(v.coerceIn(-LIMIT, LIMIT)).toInt()
        fun hi(v: Float) = ceil(v.coerceIn(-LIMIT, LIMIT)).toInt()
        return Rect(lo(b.left), lo(b.top), hi(b.right), hi(b.bottom))
    }

    /**
     * Where [layer]'s pixels are (document px): its content bounds, looked for within its data's
     * paint bounds (grown by 2 px) when it has data; null when it shows nothing.
     */
    fun contentRect(layer: Layer): Rect? {
        val bmp = layer.bitmap
        val region = paintBounds(layer.dataSnapshot())?.takeIf { !it.isEmpty }?.let { roundOut(it).apply { inset(-2, -2) } }
        return ContentBounds.of(bmp, null, region)
    }

    /** A copy of [rect] of [src] (a new bitmap of its size). */
    fun crop(src: Bitmap, rect: Rect): Bitmap {
        val out = BitmapUtils.createLayerBitmap(rect.width(), rect.height())
        Canvas(out).drawBitmap(src, -rect.left.toFloat(), -rect.top.toFloat(), null)
        return out
    }

    /** Bytes of brush tips one re-render may cache. */
    private const val TIP_BYTES = 8L shl 20
    private const val LIMIT = 1e8f
}

/**
 * A text, shape or arrayed layer lifted by the Transform tool as data (v1.7, design §3.11 a):
 * [floating] is a copy of its pixels in [sourceRect] (its content bounds), the base is the layer
 * without them. ✓ maps the data by the transform ([mapped]; one step [DataRender.apply]); when
 * the map is not available the tool resamples the pixels as in v1.6 instead (the layer then
 * becomes a raster layer, undoably, with the controller's message). The whole object is lifted,
 * whatever the pixel selection is.
 */
internal class DataLift(
    private val c: EditorController,
    override val layer: Layer,
    val kind: DataKind,
    private val maps: DataMaps,
    override val sourceRect: Rect,
    override val floating: Bitmap,
) : ObjectLift {
    override val floatingScale: Float get() = 1f

    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    /**
     * A text, arrayed or not: move, turn and proportional scale only (no side handles, no flips;
     * §3.11 a). An arrayed text maps only as its text does (`ArrayTransforms.mapped`).
     */
    val uniformOnly: Boolean get() = kind == DataKind.TEXT || (kind == DataKind.ARRAY && layer.textData != null)

    /** The last [mapped] answer, kept for the commit that follows it. */
    private var memoState: TransformState? = null
    private var memo: LayerData? = null

    override fun drawBase(canvas: Canvas) {
        if (layer.bitmap.isRecycled) return
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        canvas.drawRect(sourceRect, clear)
    }

    /**
     * The layer's data mapped by [state] (an affine map: a distorted state never is), or null
     * when it can't be kept as data.
     */
    fun mapped(state: TransformState): LayerData? {
        if (state == memoState) return memo
        val after = compute(state)
        memoState = state
        memo = after
        return after
    }

    private fun compute(state: TransformState): LayerData? {
        if (state.isDistorted) return null
        val m = LiftGeometry.matrix(state, sourceRect.left, sourceRect.top) ?: return null
        val d = layer.dataSnapshot()
        return when (kind) {
            DataKind.TEXT -> d.text?.takeIf { maps.textCanMap(m) }?.let { maps.text(it, m) }?.let { d.copy(text = it) }
            DataKind.SHAPE -> d.shape?.let { maps.shape(it, m) }?.let { d.copy(shape = it) }
            DataKind.ARRAY -> if (d.array == null) null else maps.array(layer, m)
        }
    }

    /**
     * Maps the data by [state] and re-renders it (one step [label]). A text or shape whose map
     * declines never gets here (the tool resamples its pixels instead); an array is left as it
     * was, with [TransformTool.ARRAY_REFUSAL].
     */
    override fun commit(state: TransformState, label: String): Boolean {
        val after = mapped(state) ?: run {
            if (kind == DataKind.ARRAY) c.toast(TransformTool.ARRAY_REFUSAL)
            return false
        }
        return DataRender.apply(c, layer, after, label, sourceRect, allowHidden = false)
    }

    /** The object goes: its pixels are cleared and its data dropped (one step [label]; undo brings both back). */
    override fun delete(label: String): Boolean {
        val d = layer.dataSnapshot()
        val area = Rect(sourceRect)
        DataRender.paintBounds(d)?.let { if (!it.isEmpty) area.union(DataRender.roundOut(it)) }
        area.inset(-2, -2)
        return c.updateLayerData(layer, d.copy(text = null, shape = null, vector = null, array = null), label, area, draw = {})
    }

    override fun release() {
        if (!floating.isRecycled) floating.recycle()
    }
}

/**
 * Lifts text, shape and arrayed layers as data ([DataLift]). [kindOf] decides the routing: an
 * array always (refused with [TransformTool.ARRAY_REFUSAL] while its map is not available), a
 * text or a shape only while its map is (otherwise the tool lifts pixels, as in v1.6).
 */
internal class DataLiftProvider(private val c: EditorController, private val maps: () -> DataMaps) : ObjectLiftProvider {
    /**
     * What a data lift of [layer] takes, or null to lift its pixels (also a raster array in
     * "Edit source pixels" mode: its pixels are the source, transformed without baking, §3.3).
     */
    fun kindOf(layer: Layer): DataKind? {
        val m = maps()
        return when {
            layer.array != null -> DataKind.ARRAY.takeUnless { layer.array?.spec?.editingSource == true }
            layer.textData != null -> DataKind.TEXT.takeIf { m.textCanMap(DataRender.IDENTITY) }
            layer.shapeData != null -> DataKind.SHAPE.takeIf { layer.shapeData?.let { s -> m.shape(s, DataRender.IDENTITY) } != null }
            else -> null
        }
    }

    override fun lift(layer: Layer, onReady: (ObjectLift?) -> Unit): Boolean {
        val kind = kindOf(layer) ?: return false
        val m = maps()
        if (kind == DataKind.ARRAY && m.array(layer, DataRender.IDENTITY) == null) {
            c.toast(TransformTool.ARRAY_REFUSAL)
            return false
        }
        val rect = DataRender.contentRect(layer)
        if (rect == null || !rect.intersect(0, 0, c.doc.width, c.doc.height)) {
            c.toast("Nothing to transform on this layer")
            onReady(null)
            return true
        }
        val floating = try {
            DataRender.crop(layer.bitmap, rect)
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory to transform this")
            return false
        }
        onReady(DataLift(c, layer, kind, m, rect, floating))
        return true
    }

    override fun tapped(p: Vec2): Boolean = false

    override fun liftBox(layer: Layer): RectF? = DataRender.paintBounds(layer.dataSnapshot())?.takeIf { !it.isEmpty }
}
