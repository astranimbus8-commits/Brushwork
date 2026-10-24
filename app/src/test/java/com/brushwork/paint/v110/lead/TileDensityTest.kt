package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.DisplayMetrics
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.tiles.SolidTiles
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1c (design 2.0 "Tile states", 3.3; lead): a tile's bitmap carries no density, so a tile
 * draws its own pixels one to one whatever default density it was made under. A shared
 * [SolidTiles] bitmap lives as long as one tile holds it, so it can be made under an earlier
 * screen's density (an xxhdpi one) and drawn on a canvas of today's; a cut of a flat bitmap made
 * under another density gives back its pixels too. Found by unit-full:
 * `Qa18CompatV17GoldensRobolectricTest` lost a mask's white tiles (drawn at a third of their size)
 * after an xxhdpi UI test in the same fork left a shared white bitmap behind. When an earlier test
 * in the fork already holds the white bitmap, only the density checks (after the pixel checks)
 * still tell the unfixed code apart.
 */
@RunWith(RobolectricTestRunner::class)
class TileDensityTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    /** A density other than the one a new bitmap gets now. */
    private fun otherDensity(): Int {
        val now = keep(BitmapUtils.createLayerBitmap(1, 1)).density
        return if (now == DisplayMetrics.DENSITY_XXHIGH) DisplayMetrics.DENSITY_MEDIUM else DisplayMetrics.DENSITY_XXHIGH
    }

    /** Runs [block] while a new bitmap gets [density] (as after a screen density change), then puts the default back. */
    private fun <T> underDefaultDensity(density: Int, block: () -> T): T {
        val get = Bitmap::class.java.getDeclaredMethod("getDefaultDensity").apply { isAccessible = true }
        val set = Bitmap::class.java.getDeclaredMethod("setDefaultDensity", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val was = get.invoke(null) as Int
        set.invoke(null, density)
        try {
            assertEquals("a new bitmap gets $density", density, keep(BitmapUtils.createLayerBitmap(1, 1)).density)
            return block()
        } finally {
            set.invoke(null, was)
        }
    }

    /** A 400 x 300 mask, white with a black rect: one ordinary tile and three sharing the white bitmap. */
    private fun mask(): Bitmap = keep(BitmapUtils.createMaskBitmap(400, 300)).also {
        Canvas(it).drawRect(40f, 40f, 90f, 130f, Paint().apply { color = Color.BLACK })
    }

    @Test
    fun aTileMadeUnderAnotherDensityDrawsOneToOne() {
        val src = mask()
        val other = otherDensity()
        val before = SolidTiles.refs(-1)
        assumeTrue("white can be shared", before > 0 || SolidTiles.colors() < SolidTiles.MAX_COLORS)
        // The tiles, the shared white bitmap among them, made while an earlier screen's density was the default.
        val s = underDefaultDensity(other) { TiledPixels.fromBitmap(src) }
        try {
            assertEquals("three tiles share the white bitmap", 3, s.tiles.count { it?.solidColor == -1 })
            F1Pixels.assertBitEqual("materialized", src, keep(s.materialize()))
            val dst = keep(BitmapUtils.createLayerBitmap(400, 300))
            s.drawTo(Canvas(dst), null, Rect(0, 0, 400, 300))
            F1Pixels.assertBitEqual("drawn", src, dst)
            val part = keep(BitmapUtils.createLayerBitmap(200, 100))
            s.copyRegion(Rect(200, 200, 400, 300), part, 0, 0)
            F1Pixels.assertBitEqual("copied", keep(Bitmap.createBitmap(src, 200, 200, 200, 100)), part)
            for (t in s.tiles) assertEquals("a tile's bitmap carries no density", Bitmap.DENSITY_NONE, t!!.bitmap!!.density)
            // A parked tile comes back without a density too.
            val t0 = s.tiles[0]!!
            val back = underDefaultDensity(other) { keep(TileOps.inflate(TileOps.pack(t0.bitmap!!), t0.w, t0.h)) }
            F1Pixels.assertBitEqual("inflated", t0.bitmap!!, back)
            assertEquals("an inflated tile carries no density", Bitmap.DENSITY_NONE, back.density)
        } finally {
            TileOps.releaseStore(s)
        }
        assertEquals("the white bitmap let go", before, SolidTiles.refs(-1))
    }

    @Test
    fun aFlatBitmapMadeUnderAnotherDensityCutsToItsPixels() {
        val src = mask()
        src.density = otherDensity()
        val s = TiledPixels.fromBitmap(src, detectBlank = false)
        try {
            F1Pixels.assertBitEqual("materialized", src, keep(s.materialize()))
        } finally {
            TileOps.releaseStore(s)
        }
    }
}
