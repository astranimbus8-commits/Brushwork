package com.brushwork.paint.v110.a6

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Palette
import com.brushwork.paint.ui.vector.ObjectBarRecolorIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R3 row 6): the object bar's Recolor shows the palette (`Icons.Outlined.Palette`), the
 * icon `VectorObjectBar` uses when the editor passes none, no longer v1.9's fill bucket.
 */
@RunWith(RobolectricTestRunner::class)
class RecolorIconIsPaletteTest {
    @Test
    fun recolorShowsThePalette() {
        assertEquals(Icons.Outlined.Palette.name, ObjectBarRecolorIcon.name)
        assertEquals("Outlined.Palette", ObjectBarRecolorIcon.name)
        assertNotEquals("not v1.9's fill bucket", Icons.Outlined.FormatColorFill.name, ObjectBarRecolorIcon.name)
    }
}
