package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference point (anchor) math and scaling from the center, pure. */
class TransformAnchorMathTest {
    private val eps = 1e-3f

    private fun assertVec(expected: Vec2, actual: Vec2, tol: Float = eps) {
        assertEquals("x of $actual", expected.x, actual.x, tol)
        assertEquals("y of $actual", expected.y, actual.y, tol)
    }

    private fun assertBox(expected: DocBox, actual: DocBox, tol: Float = eps) {
        assertEquals("left of $actual", expected.left, actual.left, tol)
        assertEquals("top of $actual", expected.top, actual.top, tol)
        assertEquals("right of $actual", expected.right, actual.right, tol)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, tol)
    }

    /** 100 x 50 content lifted at (10, 20). */
    private val s = TransformState.identity(10, 20, 100, 50)

    @Test
    fun anchorPointsOnTheBounds() {
        val b = s.bounds()
        assertVec(Vec2(10f, 20f), TransformAnchor.TOP_LEFT.pointOn(b))
        assertVec(Vec2(60f, 45f), TransformAnchor.CENTER.pointOn(b))
        assertVec(Vec2(110f, 70f), TransformAnchor.BOTTOM_RIGHT.pointOn(b))
        assertVec(Vec2(60f, 20f), TransformAnchor.TOP.pointOn(b))
        assertVec(Vec2(10f, 45f), TransformAnchor.LEFT.pointOn(b))
        assertEquals(9, TransformAnchor.GRID.size)
        assertEquals(TransformAnchor.CENTER, TransformAnchor.GRID[4])
        assertEquals(TransformAnchor.CENTER, TransformAnchor.byName("CENTER"))
        assertNull(TransformAnchor.byName("nonsense"))
        assertNull(TransformAnchor.byName(null))
    }

    @Test
    fun scaleFromTheCenterKeepsTheCenter() {
        val c = s.anchorPoint(TransformAnchor.CENTER)
        val half = s.withScalePercentAbout(c, 50f)
        assertEquals(50f, half.width, eps)
        assertEquals(25f, half.height, eps)
        assertVec(Vec2(60f, 45f), half.center())
        assertBox(DocBox(35f, 32.5f, 85f, 57.5f), half.bounds())
        val big = s.withScalePercentAbout(c, 300f)
        assertVec(Vec2(60f, 45f), big.center())
        assertEquals(300f, big.width, eps)
        // Back to 100 %: exactly where it started (whole pixels, pixel-exact copy).
        assertEquals(s, big.withScalePercentAbout(c, 100f))
    }

    @Test
    fun scaleFromTopLeftKeepsTheCorner() {
        val tl = s.anchorPoint(TransformAnchor.TOP_LEFT)
        val half = s.withScalePercentAbout(tl, 50f)
        assertVec(Vec2(10f, 20f), Vec2(half.bounds().left, half.bounds().top))
        assertBox(DocBox(10f, 20f, 60f, 45f), half.bounds())
        val br = s.anchorPoint(TransformAnchor.BOTTOM_RIGHT)
        val twice = s.withScalePercentAbout(br, 200f)
        assertBox(DocBox(-90f, -30f, 110f, 70f), twice.bounds())
    }

    @Test
    fun everyAnchorStaysInPlaceWhenTheSizeChanges() {
        for (a in TransformAnchor.entries) {
            val p = s.anchorPoint(a)
            val w = s.withSizeAbout(p, width = 160f, keepAspect = true)
            assertEquals("$a", 160f, w.width, eps)
            assertEquals("$a", 80f, w.height, eps)
            assertVec(p, w.anchorPoint(a))
            val h = s.withSizeAbout(p, height = 10f)
            assertEquals("$a", 100f, h.width, eps)
            assertEquals("$a", 10f, h.height, eps)
            assertVec(p, h.anchorPoint(a))
        }
        // Sizes never collapse.
        assertEquals(1f, s.withSizeAbout(s.center(), width = -5f).width, eps)
    }

    @Test
    fun rotationTurnsAroundTheReferencePoint() {
        val tl = s.anchorPoint(TransformAnchor.TOP_LEFT)
        val r = s.withRotationAbout(tl, 90f)
        assertEquals(90f, r.rotationDeg, eps)
        // The top-left corner of the content stays at the pivot.
        assertVec(tl, r.corner(0))
        assertBox(DocBox(-40f, 20f, 10f, 120f), r.bounds())
        // With a fixed pivot, going to 30° and back to 0° returns exactly.
        val back = s.withRotationAbout(tl, 30f).withRotationAbout(tl, 0f)
        assertTrue(back.sameGeometry(s))
        // Around the center (the default reference point) the center stays.
        assertVec(s.center(), s.withRotationAbout(s.center(), 33f).center())
    }

    @Test
    fun positionOfTheReferencePoint() {
        val c = s.withAnchorAt(TransformAnchor.CENTER, 200f, 100f)
        assertVec(Vec2(200f, 100f), c.center())
        assertBox(DocBox(150f, 75f, 250f, 125f), c.bounds())
        val br = s.withAnchorAt(TransformAnchor.BOTTOM_RIGHT, x = 300f)
        assertBox(DocBox(200f, 20f, 300f, 70f), br.bounds())
    }

    @Test
    fun cornerHandleFromTheCenterKeepsTheCenter() {
        // Drag the bottom-right corner 20 px right, 10 down, around the center.
        val st = TransformHandles.corner(s, 2, Vec2(110f, 70f), Vec2(130f, 80f), keepAspect = false, fromCenter = true)
        assertVec(Vec2(60f, 45f), st.center())
        assertBox(DocBox(-10f, 10f, 130f, 80f), st.bounds())
        // With keep-aspect: uniform, still centered.
        val u = TransformHandles.corner(s, 2, Vec2(110f, 70f), Vec2(130f, 80f), keepAspect = true, fromCenter = true)
        assertVec(Vec2(60f, 45f), u.center())
        assertEquals(u.width / u.height, 2f, eps)
        // The default keeps the opposite corner.
        val d = TransformHandles.corner(s, 2, Vec2(110f, 70f), Vec2(130f, 80f), keepAspect = false)
        assertBox(DocBox(10f, 20f, 130f, 80f), d.bounds())
    }

    @Test
    fun edgeHandleFromTheCenterKeepsTheCenter() {
        // Right edge (1) 10 px right: both sides move out by 10.
        val st = TransformHandles.edge(s, 1, Vec2(110f, 45f), Vec2(120f, 45f), fromCenter = true)
        assertBox(DocBox(0f, 20f, 120f, 70f), st.bounds())
        val d = TransformHandles.edge(s, 1, Vec2(110f, 45f), Vec2(120f, 45f))
        assertBox(DocBox(10f, 20f, 120f, 70f), d.bounds())
        // Handle / fixed points.
        assertVec(Vec2(110f, 45f), TransformHandles.handlePoint(s, HandleKind.EDGE, 1))
        assertVec(Vec2(10f, 45f), TransformHandles.fixedPoint(s, HandleKind.EDGE, 1, fromCenter = false))
        assertVec(Vec2(60f, 45f), TransformHandles.fixedPoint(s, HandleKind.CORNER, 0, fromCenter = true))
    }

    @Test
    fun docAxisScalingOfQuarterTurnedBoxes() {
        // Turned 90°: the 100 x 50 content is 50 wide and 100 high on the canvas.
        val q = s.rotated90(clockwise = true)
        val b = q.bounds()
        assertEquals(50f, b.width, eps)
        assertEquals(100f, b.height, eps)
        // Twice as wide on the canvas, around its left edge.
        val w = q.scaledAlongDocAxes(Vec2(b.left, b.top), 2f, 1f)
        assertBox(DocBox(b.left, b.top, b.left + 100f, b.bottom), w.bounds())
        // Rotated boxes are left alone.
        val r = s.withRotation(30f)
        assertEquals(r, r.scaledAlongDocAxes(Vec2.ZERO, 2f, 2f))
    }

    @Test
    fun pixelSettling() {
        assertTrue(s.isUnscaled)
        // Unscaled content half a pixel off settles onto whole pixels; scaled content stays exact.
        assertBox(DocBox(11f, 20f, 111f, 70f), s.translated(0.5f, 0f).pixelSettled().bounds())
        val scaled = s.withScalePercentAbout(s.center(), 50f).translated(0.25f, 0f)
        assertEquals(scaled, scaled.pixelSettled())
    }
}
