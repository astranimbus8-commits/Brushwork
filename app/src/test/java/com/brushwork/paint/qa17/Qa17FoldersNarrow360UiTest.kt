package com.brushwork.paint.qa17

import com.brushwork.paint.qa17.Qa17FolderRows.BlendForm
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.layers.LayerWindowTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 8, lead decision #7) on a 360 dp phone: a folder row's "Pass through" never
 * ellipsizes. Where one line at 12 sp would (four folders deep), it shows "Pass" over "through" at
 * 11 sp under "100%", inside the 40 dp values, none cut, the row 80 dp tall like every other row;
 * at the top level too, whichever form fits. The tree: [Qa17FolderRows.build] (Folder 1 at depth
 * 0, Folder 5 at depth 4); Qa17FoldersNarrowUiTest checks it at 392 dp with every badge.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.foldersnarrow360sandbox"])
class Qa17FoldersNarrow360UiTest {

    @Test
    fun passThroughSplitsInsteadOfEllipsizingAtDepthZeroAndFour() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("folder rows at 360 dp") {
            lateinit var t: Qa17FolderRows.Tree
            val s = h.editor(Smoke.document(256, 192, layers = 1, whiteBottom = true)) { t = Qa17FolderRows.build(it) }
            val c = s.c
            assertEquals(360f, s.widthDp, 1f)
            click("Open layers (active layer ${c.doc.indexOf(t.a) + 1})", exact = true)
            Smoke.pump(1_200)
            settle()
            val bad = mutableListOf<String>()
            val deep = Qa17FolderRows.checkRow(s, t.f5, 4, FolderLabels.PASS_THROUGH, listOf(LayerWindowTags.BADGE_LOCK), emptyList(), bad, folder = true)
            val top = Qa17FolderRows.checkRow(s, t.f1, 0, FolderLabels.PASS_THROUGH, emptyList(), emptyList(), bad, folder = true)
            // A plain row beside them: the same height.
            Qa17FolderRows.checkRow(s, t.inner, 5, "Normal", listOf(LayerWindowTags.BADGE_FOLDER_LOCK), emptyList(), bad)
            if (deep != BlendForm.SPLIT) bad += "Folder 5 (depth 4): \"Pass through\" shown $deep, not split"
            println("Qa17FoldersNarrow360: Folder 1 (depth 0) $top, Folder 5 (depth 4) $deep")
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
        }
        dog.interrupt()
        h.finish()
    }
}
