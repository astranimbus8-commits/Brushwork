package com.brushwork.paint.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.Document

/**
 * v1.10 F1 (design 2.5 and 2.16; lead, frozen kit): a frame without projections, the reference
 * A9's below and above projections (and Classic's `StrokeBelowCache`) are compared with. [frame]
 * draws a region of a [Document] with a fresh `Compositor` straight from the layers' stores (no cache, no
 * projection, no display tile reuse) with the projection switches off for the draw
 * (`EngineConfig.aboveProjection` false, `strokeBelowMinLayers` null; both put back after), so
 * whatever the engine's level, the oracle is the plain layer-by-layer composite.
 *
 * [override] is the tool preview the frame shows (a stroke in progress, a transform), drawn as the
 * canvas draws it (`useOverrides`); [display] draws as a display tile (the "Safe compositing"
 * switch then applies, as on the canvas). [assertFrame] compares a frame the engine drew with the
 * oracle's, bit for bit or within a channel tolerance. Main thread (the compositor's).
 */
object ProjectionOracle {
    /** The oracle frame of [region] of [doc] (document pixels; default the whole document). The caller owns the bitmap. */
    fun frame(doc: Document, region: Rect = Rect(0, 0, doc.width, doc.height), override: LayerRenderOverride? = null, display: Boolean = false): Bitmap {
        require(!region.isEmpty) { "empty region $region" }
        val out = BitmapUtils.createLayerBitmap(region.width(), region.height())
        val c = Canvas(out)
        c.translate(-region.left.toFloat(), -region.top.toFloat())
        c.clipRect(region)
        val target = if (display) {
            CompositeTarget.displayTile(out, region.left, region.top)
        } else {
            CompositeTarget.translate(out, region.left, region.top)
        }
        withoutProjections {
            Compositor(doc) { override }.drawDocument(c, Rect(region), useOverrides = override != null, target = target)
        }
        return out
    }

    /** [actual] (a frame of [region] the engine drew) equals the oracle's frame within [tolerance] per channel. */
    fun assertFrame(
        message: String,
        doc: Document,
        actual: Bitmap,
        region: Rect = Rect(0, 0, doc.width, doc.height),
        override: LayerRenderOverride? = null,
        tolerance: Int = 0,
    ) {
        val expected = frame(doc, region, override)
        try {
            TiledGoldenKit.assertPixels(message, expected, actual, tolerance)
        } finally {
            expected.recycle()
        }
    }

    /** Runs [block] with `EngineConfig.aboveProjection` false and `strokeBelowMinLayers` null, then puts both back. */
    fun <T> withoutProjections(block: () -> T): T {
        val above = field("aboveProjection")
        val below = field("strokeBelowMinLayers")
        val savedAbove = above.get(null)
        val savedBelow = below.get(null)
        above.set(null, false)
        below.set(null, null)
        try {
            return block()
        } finally {
            above.set(null, savedAbove)
            below.set(null, savedBelow)
        }
    }

    private fun field(name: String) = EngineConfig::class.java.getDeclaredField(name).apply { isAccessible = true }
}
