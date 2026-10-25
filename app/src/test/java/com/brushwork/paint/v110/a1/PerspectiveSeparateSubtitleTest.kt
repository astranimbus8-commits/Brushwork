package com.brushwork.paint.v110.a1

import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.ui.common.MirrorLabels110
import com.brushwork.paint.ui.symmetry.symmetryRulerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 (R1 (a), J19; the lead's answer to A1's request): Perspective array copies are baked into
 * separate objects, so a Transform tap picks and moves each one on its own. The Perspective
 * array's subtitle in the Symmetry rulers section says so with
 * [MirrorLabels110.PERSPECTIVE_SEPARATE] after v1.7's sentence; every other ruler's subtitle,
 * whose sides stay linked, does not.
 */
class PerspectiveSeparateSubtitleTest {

    @Test
    fun onlyThePerspectiveArraySubtitleSaysItsCopiesAreSeparate() {
        val perspective = symmetryRulerHint(SymmetryType.PERSPECTIVE_ARRAY)
        assertTrue(perspective, perspective.startsWith("Every brush stroke repeats in each cell of a grid in perspective."))
        assertTrue(perspective, perspective.endsWith(MirrorLabels110.PERSPECTIVE_SEPARATE))
        assertEquals(perspective, 1, Regex(Regex.escape(MirrorLabels110.PERSPECTIVE_SEPARATE)).findAll(perspective).count())
        for (type in SymmetryType.entries - SymmetryType.PERSPECTIVE_ARRAY) {
            val hint = symmetryRulerHint(type)
            assertFalse("$type: $hint", hint.contains(MirrorLabels110.PERSPECTIVE_SEPARATE))
            assertFalse("$type: $hint", hint.contains("separate objects"))
        }
    }
}
