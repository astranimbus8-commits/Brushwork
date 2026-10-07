package com.brushwork.paint.tools.vector

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.7 F5 (design §4.6): the `ShapeTransforms` stub declines on `main`, so shapes keep the v1.6
 * pixel lift under Transform. Area C deletes or rewrites this test when it fills the stub.
 */
class ShapeTransformsStubTest {
    @Test
    fun theStubDeclines() {
        assertNull(ShapeTransforms.mapped("{}", floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f)))
    }
}
