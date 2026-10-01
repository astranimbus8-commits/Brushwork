package com.brushwork.paint.tools.text

import android.graphics.Rect
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.transform.ContentBounds
import kotlin.math.max

/** Pixel rect (rounded out) of everything [t] paints ([prep]: [t] laid out), or null when it paints nothing. */
internal fun textRectOf(t: TextItem, prep: PreparedText = TextRenderer.prepare(t)): Rect? {
    if (prep.isEmpty) return null
    val r = Rect()
    prep.docBounds(t).roundOut(r)
    return r.takeUnless { it.isEmpty }
}

/**
 * Where the pixels of the text layer [layer] (drawn from [item]) really are: fonts may differ
 * from the device that drew them (an imported font missing here), so the pixels are scanned.
 * Only the area around the computed bounds is read, unless the ink reaches its edge (then the
 * whole layer is). Used to clear the old text before a text layer is drawn again.
 */
internal fun textInkOf(layer: Layer, item: TextItem): Rect? {
    val guess = textRectOf(item)
    return try {
        if (guess != null) {
            val margin = max(64, max(guess.width(), guess.height()) / 4)
            val region = Rect(guess).apply { inset(-margin, -margin) }
            if (region.intersect(0, 0, layer.width, layer.height)) {
                val ink = ContentBounds.of(layer.bitmap, region = region)
                val atEdge = ink != null && (
                    (ink.left <= region.left && region.left > 0) || (ink.top <= region.top && region.top > 0) ||
                        (ink.right >= region.right && region.right < layer.width) || (ink.bottom >= region.bottom && region.bottom < layer.height)
                    )
                if (ink != null && !atEdge) return ink
                // Nothing drawn near the text: the layer may be empty there (e.g. text outside the canvas).
                if (ink == null && region.contains(Rect(0, 0, layer.width, layer.height))) return guess
            }
        }
        ContentBounds.of(layer.bitmap) ?: guess
    } catch (e: OutOfMemoryError) {
        guess
    }
}
