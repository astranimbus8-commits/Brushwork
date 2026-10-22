package com.brushwork.paint.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import java.util.Random

/**
 * v1.10 F1 (design 2.16; lead, frozen kit): seeded random documents for the engine's parity
 * tests (A8 `TileOpsParityTest`, A9's compositor goldens through [TiledGoldenKit], A11's
 * save-and-load round trips). The same seed and [Spec] always give the same document, layer for
 * layer and pixel for pixel, on every machine (`java.util.Random`, Robolectric NATIVE graphics).
 *
 * A stack mixes, as [Spec] allows: painted layers (anti-aliased shapes and a patch of noise) with
 * every [LayerBlendMode] and several opacities; clipping layers over a painted base; masks
 * (painted gray shapes on white, some disabled); folders (pass-through and isolated, nested up to
 * three deep, children directly below their folder as `LayerTree` wants them); adjustment layers
 * (Invert, Grayscale, Tone, over an empty bitmap); blank layers (an empty bitmap: `BlankPixels`
 * after [TiledGoldenKit.toBlank]); sparse layers (one small shape in one tile, so a cut gives
 * mostly null tiles); hidden layers. Every content and mask is a `FlatPixels` store as built;
 * [Spec.width] and [Spec.height] stay at or below 512 (the test size rule).
 *
 * The active index is left at 0 (setting it tells the residency, section 2.7): a test that needs
 * an active layer sets it. The caller owns the bitmaps (`Layer.recycleBitmaps` at the end).
 */
object RandomStacks {
    /** What a random stack may hold; [layers] counts every row, folders included. */
    data class Spec(
        val width: Int = 320,
        val height: Int = 288,
        val layers: Int = 10,
        val blends: Boolean = true,
        val clips: Boolean = true,
        val masks: Boolean = true,
        val folders: Boolean = true,
        val adjustments: Boolean = true,
        val blanks: Boolean = true,
        val sparse: Boolean = true,
        val hidden: Boolean = true,
    ) {
        init {
            require(width in 1..512 && height in 1..512) { "test documents stay at or below 512 x 512 ($width x $height)" }
            require(layers >= 1) { "at least one layer" }
        }
    }

    /** The kinds of row a stack is made of. */
    enum class Kind { PAINTED, CLIP, SPARSE, BLANK, ADJUSTMENT, FOLDER }

    /** The adjustment effects a stack uses (each works with its default values). */
    val ADJUSTMENTS = listOf("adjust.invert", "adjust.grayscale", "adjust.tone")

    private val OPACITIES = floatArrayOf(1f, 1f, 0.8f, 0.55f, 0.3f)

    /** The document of [seed] under [spec]. */
    fun document(seed: Long, spec: Spec = Spec()): Document {
        val r = Random(seed)
        val doc = Document("random-$seed", "Random $seed", spec.width, spec.height)
        Builder(doc, r, spec).fill(Layer.ROOT_ID, spec.layers, depth = 0)
        return doc
    }

    /** The [Kind] of every row of [doc] that [document] made, bottom first (from names, which carry the kind). */
    fun kinds(doc: Document): List<Kind> = doc.layers.map { l -> Kind.valueOf(l.name.substringBefore(' ')) }

    private class Builder(val doc: Document, val r: Random, val spec: Spec) {
        private val w = spec.width
        private val h = spec.height

        /** Appends up to [budget] rows under [parent]; returns how many it appended. */
        fun fill(parent: Long, budget: Int, depth: Int): Int {
            var left = budget
            var previous: Kind? = null
            while (left > 0) {
                val kind = pick(previous, left, depth)
                val used = when (kind) {
                    Kind.FOLDER -> folder(parent, left, depth)
                    else -> { doc.layers += row(kind, parent); 1 }
                }
                left -= used
                previous = kind
            }
            return budget
        }

        private fun pick(previous: Kind?, left: Int, depth: Int): Kind {
            val choices = ArrayList<Kind>()
            repeat(5) { choices += Kind.PAINTED }
            // A clip needs a painted base (or clip chain) right below in the same folder.
            if (spec.clips && (previous == Kind.PAINTED || previous == Kind.CLIP)) repeat(2) { choices += Kind.CLIP }
            if (spec.sparse) choices += Kind.SPARSE
            if (spec.blanks) choices += Kind.BLANK
            if (spec.adjustments && previous != null) choices += Kind.ADJUSTMENT
            if (spec.folders && depth < 3 && left >= 3) repeat(2) { choices += Kind.FOLDER }
            return choices[r.nextInt(choices.size)]
        }

        private fun folder(parent: Long, left: Int, depth: Int): Int {
            val id = doc.newLayerId()
            val f = Layer.newFolder(id, "FOLDER $id", FolderSpec(passThrough = r.nextBoolean()))
            f.parentId = parent
            val children = 1 + r.nextInt(minOf(left - 1, 4))
            fill(f.id, children, depth + 1)
            if (f.folder?.passThrough == false) {
                f.opacity = OPACITIES[r.nextInt(OPACITIES.size)]
                if (spec.blends) f.blendMode = blend()
            }
            if (spec.hidden && r.nextInt(12) == 0) f.visible = false
            doc.layers += f
            return children + 1
        }

        private fun row(kind: Kind, parent: Long): Layer {
            val bitmap = when (kind) {
                Kind.PAINTED, Kind.CLIP -> painted()
                Kind.SPARSE -> sparse()
                else -> BitmapUtils.createLayerBitmap(w, h)
            }
            val id = doc.newLayerId()
            val l = Layer(id, "$kind $id", bitmap)
            l.parentId = parent
            when (kind) {
                Kind.ADJUSTMENT -> {
                    l.adjustment = AdjustmentSpec(filterId = ADJUSTMENTS[r.nextInt(ADJUSTMENTS.size)])
                    l.opacity = OPACITIES[r.nextInt(OPACITIES.size)]
                }
                Kind.BLANK -> if (spec.blends) l.blendMode = blend()
                else -> {
                    l.clipping = kind == Kind.CLIP
                    l.opacity = OPACITIES[r.nextInt(OPACITIES.size)]
                    if (spec.blends && r.nextInt(3) > 0) l.blendMode = blend()
                    if (spec.masks && r.nextInt(4) == 0) {
                        l.mask = mask()
                        l.maskEnabled = r.nextInt(5) > 0
                    }
                }
            }
            if (spec.hidden && r.nextInt(12) == 0) l.visible = false
            return l
        }

        private fun blend(): LayerBlendMode = LayerBlendMode.entries[r.nextInt(LayerBlendMode.entries.size)]

        private fun color(alpha: Int = 40 + r.nextInt(216)): Int = Color.argb(alpha, r.nextInt(256), r.nextInt(256), r.nextInt(256))

        /** Three to seven anti-aliased shapes and one patch of noise. */
        private fun painted(): Bitmap {
            val b = BitmapUtils.createLayerBitmap(w, h)
            val c = Canvas(b)
            val p = Paint().apply { isAntiAlias = true }
            repeat(3 + r.nextInt(5)) {
                p.color = color()
                val x = r.nextInt(w).toFloat()
                val y = r.nextInt(h).toFloat()
                val s = 8f + r.nextInt(maxOf(9, minOf(w, h) / 2))
                when (r.nextInt(3)) {
                    0 -> c.drawCircle(x, y, s / 2f, p)
                    1 -> c.drawRect(x - s / 2f, y - s / 3f, x + s / 2f, y + s / 3f, p)
                    else -> c.drawOval(x - s / 2f, y - s / 4f, x + s / 2f, y + s / 4f, p)
                }
            }
            val pw = minOf(w, 16 + r.nextInt(64))
            val ph = minOf(h, 16 + r.nextInt(64))
            val px = IntArray(pw * ph) { r.nextInt() }
            b.setPixels(px, 0, pw, r.nextInt(w - pw + 1), r.nextInt(h - ph + 1), pw, ph)
            return b
        }

        /** One small shape somewhere: most tiles of a cut stay null. */
        private fun sparse(): Bitmap {
            val b = BitmapUtils.createLayerBitmap(w, h)
            val p = Paint().apply { isAntiAlias = true; color = color(255) }
            Canvas(b).drawCircle(r.nextInt(w).toFloat(), r.nextInt(h).toFloat(), 3f + r.nextInt(12), p)
            return b
        }

        /** White (shows everything) with gray and black shapes. */
        private fun mask(): Bitmap {
            val b = BitmapUtils.createLayerBitmap(w, h)
            val c = Canvas(b)
            c.drawColor(Color.WHITE)
            val p = Paint().apply { isAntiAlias = true }
            repeat(1 + r.nextInt(4)) {
                val v = r.nextInt(256)
                p.color = Color.argb(255, v, v, v)
                c.drawCircle(r.nextInt(w).toFloat(), r.nextInt(h).toFloat(), 10f + r.nextInt(maxOf(11, minOf(w, h) / 3)), p)
            }
            return b
        }
    }
}
