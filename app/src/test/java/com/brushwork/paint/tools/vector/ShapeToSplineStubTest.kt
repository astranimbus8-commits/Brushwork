package com.brushwork.paint.tools.vector

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.7 F3 (design §3.6): the `ShapeToSpline` stub declines on `main`, so no shape converts to a
 * path yet. Area C deletes or rewrites this test when it fills the stub.
 */
class ShapeToSplineStubTest {
    @Test
    fun theStubDeclines() {
        assertNull(ShapeToSpline.convert(ShapeObject(), intArrayOf()))
        assertNull(ShapeToSpline.convert(ShapeObject(type = ShapeType.POLYGON, sides = 3), intArrayOf(0)))
    }
}
