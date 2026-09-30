package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.floor
import kotlin.math.hypot

/** Text on a path rendered with real Skia: ink placement, both modes, outline, bounds, caching, speed. */
@RunWith(RobolectricTestRunner::class)
class TextOnPathRobolectricTest {

    private fun fill(size: Float = 60f, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.DEFAULT
    }

    private fun stroke(size: Float, width: Float, color: Int = Color.RED) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.DEFAULT
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private class Ink(val bmp: Bitmap) {
        val points: List<IntArray> by lazy {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val out = ArrayList<IntArray>()
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (Color.alpha(px[y * bmp.width + x]) > 0) out += intArrayOf(x, y)
            out
        }
    }

    private fun render(w: Int = 600, h: Int = 600, draw: (Canvas) -> Unit): Ink {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        draw(Canvas(bmp))
        return Ink(bmp)
    }

    private val circle = TextPathSpec(type = TextPathType.CIRCLE, cx = 300f, cy = 300f, radius = 150f, startAngleDeg = -90f)

    @Test
    fun helloOnACircleFollowsTheCircleAndStaysAwayFromTheCenter() {
        for (mode in TextPathMode.entries) {
            val ink = render { TextOnPath.draw(it, "HELLO", fill(), null, circle.copy(mode = mode)) }
            assertTrue("$mode draws (${ink.points.size} px)", ink.points.size > 800)
            var sumY = 0.0
            for ((x, y) in ink.points) {
                val d = hypot(x - 300.0, y - 300.0)
                assertTrue("$mode: ink at ($x, $y) is $d px from the center", d in 140.0..205.0)
                sumY += y
            }
            assertTrue("$mode: the text sits on top of the circle", sumY / ink.points.size < 300.0 - 120.0)
        }
    }

    @Test
    fun bendAndRotateBothRenderButDiffer() {
        val bend = render { TextOnPath.draw(it, "HELLO WORLD", fill(), null, circle.copy(radius = 120f)) }
        val rotate = render { TextOnPath.draw(it, "HELLO WORLD", fill(), null, circle.copy(radius = 120f, mode = TextPathMode.ROTATE)) }
        assertTrue(bend.points.size > 800 && rotate.points.size > 800)
        val a = bend.points.map { it[0] * 10_000 + it[1] }.toSet()
        val b = rotate.points.map { it[0] * 10_000 + it[1] }.toSet()
        val differ = (a - b).size + (b - a).size
        assertTrue("bent and rigid letters differ ($differ px)", differ > 100)
        // Similar amount of ink: the same letters, only shaped differently.
        assertEquals(1.0, bend.points.size.toDouble() / rotate.points.size, 0.35)
    }

    @Test
    fun lettersAtASharpCornerKeepTheirShape() {
        // Bending a letter across a sharp corner would smear it into a wedge (lots of extra ink)
        // or fold it; such letters are turned rigidly, so both modes use about the same ink.
        val square = TextPathSpec(type = TextPathType.RECT, cx = 300f, cy = 330f, width = 300f, height = 300f, offset = 105f)
        for (side in TextPathSide.entries) {
            val bend = render { TextOnPath.draw(it, "HOME", fill(90f), null, square.copy(side = side)) }
            val rotate = render { TextOnPath.draw(it, "HOME", fill(90f), null, square.copy(side = side, mode = TextPathMode.ROTATE)) }
            assertEquals("$side: ink ${bend.points.size} vs ${rotate.points.size}", 1.0, bend.points.size.toDouble() / rotate.points.size, 0.05)
        }
        // Round a generous corner the letters really bend.
        val rounded = square.copy(cornerRadius = 120f)
        val bent = render { TextOnPath.draw(it, "HOME", fill(60f), null, rounded) }
        val rigid = render { TextOnPath.draw(it, "HOME", fill(60f), null, rounded.copy(mode = TextPathMode.ROTATE)) }
        val a = bent.points.map { it[0] * 10_000 + it[1] }.toSet()
        val b = rigid.points.map { it[0] * 10_000 + it[1] }.toSet()
        assertTrue("bent differs from rotated round a rounded corner", (a - b).size + (b - a).size > 200)
    }

    @Test
    fun insideAndOutsideAndDirections() {
        fun meanDistance(spec: TextPathSpec): Double {
            val ink = render { TextOnPath.draw(it, "HELLO", fill(40f), null, spec) }
            assertTrue(ink.points.isNotEmpty())
            return ink.points.sumOf { (x, y) -> hypot(x - 300.0, y - 300.0) } / ink.points.size
        }
        val outside = meanDistance(circle)
        val inside = meanDistance(circle.copy(side = TextPathSide.INSIDE))
        val bottomInside = meanDistance(circle.copy(side = TextPathSide.INSIDE, clockwise = false, startAngleDeg = 90f))
        val bottomOutside = meanDistance(circle.copy(clockwise = false, startAngleDeg = 90f))
        assertTrue("outside: $outside", outside > 150.0)
        assertTrue("inside: $inside", inside < 150.0)
        assertTrue("bottom inside: $bottomInside", bottomInside < 150.0)
        assertTrue("bottom outside: $bottomOutside", bottomOutside > 150.0)
        // Counter-clockwise text sits at the bottom.
        val ink = render { TextOnPath.draw(it, "HELLO", fill(40f), null, circle.copy(clockwise = false, startAngleDeg = 90f, side = TextPathSide.INSIDE)) }
        assertTrue(ink.points.all { it[1] > 300 })
    }

    @Test
    fun boundsContainEveryDrawnPixel() {
        val specs = listOf(
            TextPathSpec(type = TextPathType.LINE, x1 = 80f, y1 = 420f, x2 = 520f, y2 = 250f),
            circle,
            circle.copy(side = TextPathSide.INSIDE, clockwise = false, startAngleDeg = 70f, baselineShift = 6f),
            TextPathSpec(type = TextPathType.RECT, cx = 300f, cy = 300f, width = 260f, height = 200f, cornerRadius = 0f, rotationDeg = 15f),
            TextPathSpec(type = TextPathType.RECT, cx = 300f, cy = 300f, width = 220f, height = 220f, cornerRadius = 50f, side = TextPathSide.INSIDE),
            TextPathSpec(type = TextPathType.CURVE, x1 = 60f, y1 = 400f, cx1 = 200f, cy1 = 100f, cx2 = 400f, cy2 = 500f, x2 = 540f, y2 = 200f, offset = 20f),
        )
        for (base in specs) for (mode in TextPathMode.entries) {
            val spec = base.copy(mode = mode)
            val f = fill(44f).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC) }
            val s = stroke(44f, 8f).apply { typeface = f.typeface }
            var drawn = RectF()
            val ink = render { drawn = TextOnPath.draw(it, "Wavy text, gjpqy!", f, s, spec) }
            assertTrue("${spec.type} $mode draws", ink.points.size > 300)
            val b = TextOnPath.bounds("Wavy text, gjpqy!", f, s, spec)
            assertEquals("draw returns the bounds", b, drawn)
            val l = floor(b.left).toInt(); val t = floor(b.top).toInt()
            val r = kotlin.math.ceil(b.right).toInt(); val btm = kotlin.math.ceil(b.bottom).toInt()
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            for ((x, y) in ink.points) {
                assertTrue("${spec.type} $mode: ink ($x, $y) outside $b", x >= l && x < r && y >= t && y < btm)
                minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
            // And the bounds are tight enough for invalidation (no more than the outline + a few px).
            val slack = 4f + 4f + 20f
            assertTrue("${spec.type} $mode: loose bounds $b for ink $minX,$minY - $maxX,$maxY",
                b.left > minX - slack && b.top > minY - slack && b.right < maxX + 1 + slack && b.bottom < maxY + 1 + slack)
        }
    }

    @Test
    fun outlineIsDrawnBehindTheFill() {
        val ink = render { TextOnPath.draw(it, "HELLO", fill(80f, Color.BLACK), stroke(80f, 10f, Color.RED), circle) }
        val px = IntArray(600 * 600)
        ink.bmp.getPixels(px, 0, 600, 0, 0, 600, 600)
        val red = px.count { Color.alpha(it) == 255 && Color.red(it) > 200 && Color.green(it) < 60 }
        val black = px.count { Color.alpha(it) == 255 && Color.red(it) < 40 && Color.green(it) < 40 && Color.blue(it) < 40 }
        assertTrue("outline visible ($red px)", red > 300)
        assertTrue("fill on top of the outline ($black px)", black > 300)
    }

    @Test
    fun rotatedLettersGetTheirOutlineEvenFromAPlainStrokePaint() {
        // A stroke paint that only sets style, width and color still outlines the letters at the
        // fill's font and size (not at the 12 px default), and the caller's paints are unchanged.
        val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = Color.RED }
        val f = fill(80f).apply { textAlign = Paint.Align.CENTER }
        val plainSize = plain.textSize
        assertTrue(plainSize < 20f)
        val spec = circle.copy(mode = TextPathMode.ROTATE)
        val ink = render { TextOnPath.draw(it, "HELLO", f, plain, spec) }
        val px = IntArray(600 * 600)
        ink.bmp.getPixels(px, 0, 600, 0, 0, 600, 600)
        assertTrue("outline visible", px.count { Color.alpha(it) == 255 && Color.red(it) > 200 && Color.green(it) < 60 } > 300)
        val b = TextOnPath.bounds("HELLO", f, plain, spec)
        for ((x, y) in ink.points) assertTrue(b.contains(x + 0.5f, y + 0.5f))
        assertEquals(plainSize, plain.textSize)
        assertEquals(Paint.Align.CENTER, f.textAlign)
    }

    @Test
    fun textRunsOffAnOpenPathAlongItsEndAndWrapsAClosedOne() {
        val line = TextPathSpec(type = TextPathType.LINE, x1 = 50f, y1 = 300f, x2 = 150f, y2 = 300f, align = TextPathAlign.START)
        val ink = render { TextOnPath.draw(it, "A long text on a short line", fill(40f), null, line) }
        assertTrue("continues past the end of the line", ink.points.any { it[0] > 400 })
        assertTrue("along the line's direction", ink.points.all { it[1] in 250..320 })
        val small = circle.copy(radius = 60f)
        val wrapped = render { TextOnPath.draw(it, "This text is far longer than the circle", fill(30f), null, small) }
        val quadrants = wrapped.points.map { (x, y) -> (if (x < 300) 0 else 1) + (if (y < 300) 0 else 2) }.toSet()
        assertEquals("ink all around the circle", 4, quadrants.size)
    }

    @Test
    fun layoutsAndBentOutlinesAreCached() {
        val f = fill()
        val a = TextOnPathEngine.result("HELLO", f, circle)!!
        val b = TextOnPathEngine.result("HELLO", fill(), circle)!!
        assertSame("same inputs reuse the layout", a.first, b.first)
        assertSame("and the bent outline", a.second, b.second)
        val c = TextOnPathEngine.result("HELLO", f, circle.copy(radius = 170f))!!
        assertSame("a new shape keeps the layout", a.first, c.first)
        assertNotSame(a.second.path, c.second.path)
        val d = TextOnPathEngine.result("HELLO", fill(61f), circle)!!
        assertNotSame("a new size lays out again", a.first, d.first)
        // A color change is not a new layout.
        assertSame(d.first, TextOnPathEngine.result("HELLO", fill(61f, Color.BLUE), circle)!!.first)
    }

    @Test
    fun blankTextAndStraightTextDrawNothing() {
        val ink = render {
            assertTrue(TextOnPath.draw(it, "  \n ", fill(), null, circle).isEmpty)
            assertTrue(TextOnPath.draw(it, "HELLO", fill(), null, TextPathSpec()).isEmpty)
        }
        assertTrue(ink.points.isEmpty())
        assertTrue(TextOnPath.bounds("", fill(), null, circle).isEmpty)
    }

    @Test
    fun oneLineWithClustersKeptWhole() {
        val p = fill(40f)
        val l = TextOnPathEngine.layout("A\nB", p)!!
        assertEquals("A B", l.line)
        val lineSep = 0x2028.toChar()
        val paraSep = 0x2029.toChar()
        assertEquals("A B C D E", TextOnPathEngine.oneLine("A\r\nB\tC${lineSep}D${paraSep}E"))
        assertEquals("unchanged text is not copied", "plain", TextOnPathEngine.oneLine("plain"))
        assertEquals(p.measureText("A B"), l.width, 0.5f)
        val emoji = TextOnPathEngine.layout("A👍🏽B", p)!!
        assertEquals("letter, emoji with skin tone, letter", 3, emoji.clusters.size)
        val mid = emoji.clusters[1]
        assertEquals(1, mid.start)
        assertEquals(5, mid.end)
        assertTrue("emoji are placed unbent", mid.rigid)
        assertFalse(emoji.clusters[0].rigid)
        assertTrue(TextOnPathEngine.isEmojiCluster("❤️", 0, 2))
        assertFalse(TextOnPathEngine.isEmojiCluster("1", 0, 1))
        assertFalse(TextOnPathEngine.isEmojiCluster("é", 0, 1))
        // Clusters follow each other along the line.
        val word = TextOnPathEngine.layout("Hello", p)!!
        for (i in 1 until word.clusters.size) assertEquals(word.clusters[i - 1].x1, word.clusters[i].x0, 0.01f)
        assertEquals(word.width, word.clusters.last().x1, 0.01f)
    }

    @Test
    fun mixedDirectionTextKeepsEveryCharacterInVisualOrder() {
        val l = TextOnPathEngine.layout("abc אבג def", fill(40f))!!
        val covered = l.clusters.flatMap { (it.start until it.end).toList() }.sorted()
        assertEquals((0 until l.line.length).toList(), covered)
        for (i in 1 until l.clusters.size) assertEquals("visual order", l.clusters[i - 1].x1, l.clusters[i].x0, 0.5f)
        val hebrew = l.clusters.filter { it.rtl }
        assertEquals(3, hebrew.size)
        assertTrue("right-to-left letters are laid out right to left", hebrew.first().start > hebrew.last().start)
    }

    @Test
    fun letterSpacingWidensTheText() {
        val plain = TextOnPathEngine.layout("HELLO", fill(50f))!!.width
        val spaced = TextOnPathEngine.layout("HELLO", fill(50f).apply { letterSpacing = 0.5f })!!.width
        assertEquals(plain + 5 * 25f, spaced, 3f)
    }

    @Test
    fun guidePathsForEveryShape() {
        assertTrue(TextOnPath.guide(TextPathSpec()).isEmpty)
        for (type in listOf(TextPathType.LINE, TextPathType.CIRCLE, TextPathType.RECT, TextPathType.CURVE)) {
            val spec = TextOnPath.defaultFor(type, Vec2(300f, 300f), 300f, 40f, TextPathSpec())
            val g = TextOnPath.guide(spec)
            assertFalse("$type guide", g.isEmpty)
            val b = RectF()
            g.computeBounds(b, true)
            assertTrue("$type guide bounds $b", b.width() > 100f)
        }
        val b = RectF()
        TextOnPath.guide(circle).computeBounds(b, true)
        assertEquals(RectF(150f, 150f, 450f, 450f), b)
    }

    @Test
    fun brokenOrHugeNumbersDrawSafely() {
        // A corrupt file, a runaway pinch or a typed 1e20: nothing throws, hangs or draws garbage.
        val broken = listOf(
            circle.copy(radius = Float.NaN),
            circle.copy(offset = 1e20f),
            circle.copy(radius = 1f, offset = 3e38f),
            circle.copy(cx = Float.POSITIVE_INFINITY),
            circle.copy(startAngleDeg = Float.NaN, baselineShift = Float.NEGATIVE_INFINITY),
            TextPathSpec(type = TextPathType.RECT, cx = 300f, cy = 300f, width = Float.NaN, height = 1e30f, cornerRadius = Float.NaN),
            TextPathSpec(type = TextPathType.CURVE, x1 = Float.NaN, y1 = 300f, cx1 = 1e38f, cy1 = 0f, cx2 = 400f, cy2 = 0f, x2 = 500f, y2 = 300f),
            TextPathSpec(type = TextPathType.LINE, x1 = 50f, y1 = 300f, x2 = 550f, y2 = 300f, offset = Float.NaN),
        )
        for (base in broken) for (mode in TextPathMode.entries) {
            val spec = base.copy(mode = mode)
            var drawn = RectF()
            render { drawn = TextOnPath.draw(it, "HELLO", fill(40f), stroke(40f, 4f), spec) }
            assertTrue("$spec: bounds $drawn", drawn.isEmpty || floatArrayOf(drawn.left, drawn.top, drawn.right, drawn.bottom).all { it.isFinite() })
            assertEquals(drawn, TextOnPath.bounds("HELLO", fill(40f), stroke(40f, 4f), spec))
            for (h in TextOnPath.handles(spec)) assertTrue("$spec: handle $h", h.x.isFinite() && h.y.isFinite())
            val g = RectF()
            TextOnPath.guide(spec).computeBounds(g, true)
            assertTrue("$spec: guide $g", floatArrayOf(g.left, g.top, g.right, g.bottom).all { it.isFinite() })
        }
        // A huge offset on a circle still puts the text on the circle.
        val ink = render { TextOnPath.draw(it, "HELLO", fill(40f), null, circle.copy(offset = 1e20f)) }
        assertTrue(ink.points.size > 300)
        for ((x, y) in ink.points) assertTrue("ink at ($x, $y)", hypot(x - 300.0, y - 300.0) in 140.0..200.0)
    }

    @Test
    fun bendingThirtyLettersIsFastEnoughToDragAHandle() {
        val f = fill(150f)
        val text = "Thirty characters on a circle"
        assertEquals(29, text.length)
        val base = circle.copy(cx = 2000f, cy = 2000f, radius = 900f)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val t0 = System.nanoTime()
        TextOnPath.draw(canvas, text, f, null, base)
        val first = (System.nanoTime() - t0) / 1e6
        // Warm up, then time new shapes (every frame of a handle drag re-bends the outline).
        repeat(10) { TextOnPath.draw(canvas, text, f, null, base.copy(radius = 800f + it)) }
        val times = (0 until 21).map { i ->
            val s = System.nanoTime()
            TextOnPath.draw(canvas, text, f, null, base.copy(radius = 700f + i * 3f, startAngleDeg = -90f + i))
            (System.nanoTime() - s) / 1e6
        }.sorted()
        val median = times[times.size / 2]
        println("text on path: first layout + bend $first ms, re-bend median $median ms (min ${times.first()}, max ${times.last()})")
        assertTrue("re-bending takes $median ms", median < 50.0)
        assertTrue("first layout takes $first ms", first < 2000.0)
    }
}
