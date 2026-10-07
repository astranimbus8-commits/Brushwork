package com.brushwork.paint.tools.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.7 F5 (design §4.6): the `TextTransforms` stub declines on `main`, so text keeps the v1.6
 * pixel lift under Transform. Area D deletes or rewrites this test when it fills the stub.
 */
class TextTransformsStubTest {
    @Test
    fun theStubDeclines() {
        val m = floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f)
        assertFalse(TextTransforms.canMap(m))
        assertNull(TextTransforms.mapped("{}", m))
    }
}
