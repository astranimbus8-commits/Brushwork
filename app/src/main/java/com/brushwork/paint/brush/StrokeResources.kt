package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import com.brushwork.paint.EditorController
import java.util.WeakHashMap

/**
 * Per-editor brush engine resources shared by the brush, eraser, smudge and blur tools: one
 * document-sized ALPHA_8 coverage buffer (allocated lazily, reused, cleared only in dirty
 * regions) and the tip cache. Main thread only. Freed when the editor's coroutine scope is
 * cancelled (the editor session closes); holds no reference to the controller, so the weak
 * registry never keeps an editor alive.
 */
class StrokeResources {
    val tips = TipCache()
    val stamper = DabStamper(tips)
    val painter = CoveragePainter()

    private var buffer: Bitmap? = null
    private var bufferCanvas: Canvas? = null

    /** True while the coverage buffer is allocated. */
    internal val hasCoverage: Boolean get() = buffer != null

    /** The coverage buffer for a [width] x [height] document (reallocated when the size changes). */
    fun coverage(width: Int, height: Int): Bitmap {
        val b = buffer
        if (b != null && !b.isRecycled && b.width == width && b.height == height) return b
        b?.recycle()
        buffer = null
        bufferCanvas = null
        val n = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        buffer = n
        bufferCanvas = Canvas(n)
        return n
    }

    /** Canvas drawing into the current coverage buffer. */
    fun coverageCanvas(): Canvas = requireNotNull(bufferCanvas) { "coverage() not allocated" }

    /** Clears [rect] of the coverage buffer. */
    fun clear(rect: Rect) {
        val c = bufferCanvas ?: return
        if (rect.isEmpty) return
        c.save()
        c.clipRect(rect)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        c.restore()
    }

    /** Frees the coverage buffer and the tips (they are recreated on demand). */
    fun release() {
        buffer?.recycle()
        buffer = null
        bufferCanvas = null
        tips.clear()
    }

    companion object {
        private val registry = WeakHashMap<EditorController, StrokeResources>()

        /** The resources of [controller]'s editor (created on first use). */
        fun of(controller: EditorController): StrokeResources =
            registry.getOrPut(controller) { StrokeResources() }

        /**
         * Frees [controller]'s buffer and tips right away (called from Tool.onDispose when the
         * editor closes) instead of whenever the weak entry is purged. Safe to call repeatedly.
         */
        fun releaseFor(controller: EditorController) {
            registry.remove(controller)?.release()
        }
    }
}
