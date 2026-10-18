package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.model.Layer

/**
 * v1.9 (R14): drawing part of a text layer of several texts again. An update clears and redraws
 * one area of the layer (`EditorController.updateTextLayer` clips to it), so only the texts that
 * meet it are drawn, in picture order; the others keep their pixels. A layer of
 * [TextCodec.MAX_ITEMS] texts then redraws only around the text that changed (R14 c).
 */
internal object TextLayerDraw {
    /** Bounds are grown by this much before they are tested against an area (anti-aliased edges). */
    const val SLACK_PX = 2

    /** [prep]'s bounds of [item] in whole document px, grown by [SLACK_PX]; empty for an empty layout. */
    fun inkRect(item: TextItem, prep: PreparedText, out: Rect = Rect()): Rect {
        if (prep.isEmpty) {
            out.setEmpty()
            return out
        }
        prep.docBounds(item).roundOut(out)
        out.inset(-SLACK_PX, -SLACK_PX)
        return out
    }

    /**
     * Draws [items] (laid out as [preps]) in picture order, only those that meet [area]; all of
     * them when [layer] has a live array, whose copies are redrawn over a larger area.
     */
    fun drawMeeting(c: Canvas, layer: Layer, area: Rect, items: List<TextItem>, preps: List<PreparedText>) {
        val every = layer.array != null
        val r = Rect()
        for (k in items.indices) {
            val prep = preps[k]
            if (!every && (prep.isEmpty || !Rect.intersects(inkRect(items[k], prep, r), area))) continue
            TextRenderer.drawItem(c, items[k], prep, null)
        }
    }
}
