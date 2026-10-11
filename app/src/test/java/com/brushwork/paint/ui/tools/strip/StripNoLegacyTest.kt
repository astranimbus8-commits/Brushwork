package com.brushwork.paint.ui.tools.strip

import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (design §3.9, §6): once every area has converted its strips, no tool draws through
 * `StripSpec.legacy` any more, on a raster or a vector layer. Ignored until the integration step
 * of §6 removes the annotation (the lead's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.nolegacysandbox"])
class StripNoLegacyTest {
    @Ignore("v1.8 integration (design §6) removes this: every area converts its strips first")
    @Test
    fun noToolDrawsALegacyStrip() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig().show()
        val legacy = mutableListOf<String>()
        try {
            for (vector in listOf(false, true)) for (id in ToolId.entries) {
                rig.select(id, vector)
                if (rig.spec.legacyContent != null) legacy += "$id on a ${if (vector) "vector" else "raster"} layer"
            }
        } finally {
            rig.close()
        }
        assertTrue("legacy strips left:\n" + legacy.joinToString("\n"), legacy.isEmpty())
    }
}
