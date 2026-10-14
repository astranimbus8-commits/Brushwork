package com.brushwork.paint.ui.gallery

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.AppSettings
import com.brushwork.paint.storage.CanvasStart
import com.brushwork.paint.ui.common.CanvasLabels19
import com.brushwork.paint.ui.common.V19Tags

/**
 * v1.9 (R15; owner A8): the four choices of the New canvas dialog's "Background" row, in their
 * order, with the value the dialog remembers ([AppSettings.newCanvasBackground]), the one-line
 * caption under the row and the [CanvasStart] the canvas is made with. Every choice makes one
 * layer, "Layer 1".
 */
internal enum class NewCanvasBackground(val setting: String, val start: CanvasStart) {
    WHITE(AppSettings.NEW_CANVAS_WHITE, CanvasStart.FILL),
    TRANSPARENT(AppSettings.NEW_CANVAS_TRANSPARENT, CanvasStart.TRANSPARENT),
    CUSTOM(AppSettings.NEW_CANVAS_CUSTOM, CanvasStart.FILL),
    PAPER(AppSettings.NEW_CANVAS_PAPER, CanvasStart.PAPER);

    /** The caption under the row while this choice is selected. */
    val caption: String
        get() = when (this) {
            WHITE, CUSTOM -> CanvasLabels19.WHITE_NOTE
            TRANSPARENT -> CanvasLabels19.TRANSPARENT_NOTE
            PAPER -> CanvasLabels19.PAPER_NOTE
        }

    /** The test tag of this choice's option ([V19Tags.NEW_CANVAS_PREFIX] + the name). */
    val tag: String get() = V19Tags.NEW_CANVAS_PREFIX + name

    /**
     * The layer's fill the canvas is made with ([com.brushwork.paint.storage.NewCanvasSpec.background]):
     * white, [customColor], or none (transparent, alone or over the paper).
     */
    fun background(customColor: Int): Int? = when (this) {
        WHITE -> WHITE_COLOR
        CUSTOM -> customColor
        TRANSPARENT, PAPER -> null
    }

    companion object {
        private const val WHITE_COLOR = 0xFFFFFFFF.toInt()

        /** The choice a remembered [setting] names; "White" for anything else (the first default). */
        fun fromSetting(setting: String?): NewCanvasBackground = entries.firstOrNull { it.setting == setting } ?: WHITE
    }
}

/**
 * v1.9 (R15; owner A8): the "Paper" swatch: a white page with its top-right corner folded over,
 * and the checker of a transparent background in the corner the fold uncovers, so it never looks
 * like the White swatch. Drawn with plain [Canvas] calls, so a test can render it into a bitmap;
 * the dialog draws it through `nativeCanvas`. Main thread only (its paints are shared).
 */
internal object PaperSwatchArt {
    private const val PAGE = 0xFFFFFFFF.toInt()
    private const val CHECK_DARK = 0xFFCCCCCC.toInt()
    private const val FLAP = 0xFFE4E4E4.toInt()
    private const val EDGE = 0xFF9E9E9E.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = EDGE }
    private val path = Path()

    /** Draws the swatch into the [width] by [height] px rect at the origin of [canvas]; [stroke] is the fold line's width in px. */
    fun draw(canvas: Canvas, width: Float, height: Float, stroke: Float) {
        if (width <= 0f || height <= 0f) return
        fill.color = PAGE
        canvas.drawRect(0f, 0f, width, height, fill)
        val fold = minOf(width, height) * FOLD
        val left = width - fold
        // The uncovered corner: the checker of ColorSwatch (cells of a fifth of the swatch).
        path.rewind()
        path.moveTo(left, 0f)
        path.lineTo(width, 0f)
        path.lineTo(width, fold)
        path.close()
        val save = canvas.save()
        canvas.clipPath(path)
        val cell = (width / 5f).coerceAtLeast(1f)
        fill.color = CHECK_DARK
        var row = 0
        var y = 0f
        while (y < fold) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < width) {
                canvas.drawRect(x, y, x + cell, y + cell, fill)
                x += 2 * cell
            }
            y += cell
            row++
        }
        canvas.restoreToCount(save)
        // The flap, folded down onto the page, and its edges.
        path.rewind()
        path.moveTo(left, 0f)
        path.lineTo(left, fold)
        path.lineTo(width, fold)
        path.close()
        fill.color = FLAP
        canvas.drawPath(path, fill)
        edge.strokeWidth = stroke
        canvas.drawPath(path, edge)
    }

    /** The fold's leg as a share of the swatch's shorter side. */
    private const val FOLD = 0.42f
}
